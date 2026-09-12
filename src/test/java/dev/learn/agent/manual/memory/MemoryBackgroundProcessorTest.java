package dev.learn.agent.manual.memory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证后台记忆的恢复、周期触发和显式收尾批次。 */
class MemoryBackgroundProcessorTest {

    @TempDir
    Path workspace;

    /** 验证启动时立即恢复，并注册五分钟与两小时两个不同周期。 */
    @Test
    void startsRecoveryAndRegistersBothSchedules() throws Exception {
        try (RecordingScheduler scheduler = new RecordingScheduler();
             ProcessorFixture fixture = new ProcessorFixture(workspace, List.of(extractionSuccess()))) {
            fixture.enqueue("task-1");
            MemoryBackgroundProcessor processor = fixture.processor(scheduler);

            // 1. start 只登记工作；测试手工运行五分钟任务，不真实等待时钟。
            processor.start();
            assertEquals(1, scheduler.immediateTasks().size());
            assertEquals(
                    List.of(Duration.ofMinutes(5), Duration.ofHours(2)),
                    scheduler.scheduledTasks().stream()
                            .map(ScheduledTask::delay)
                            .toList()
            );

            // 2. 五分钟任务复用正常批次，能够消费持久化的 pending task。
            scheduler.scheduledTasks().getFirst().command().run();
            assertEquals(0, fixture.workStore().pendingTaskCount());
        }
    }

    /** 验证数量整理发生在完整 extraction 批次之后。 */
    @Test
    void consolidatesOnlyAfterWholePendingBatch() throws Exception {
        List<ProviderResponse> responses = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            responses.add(extractionSuccess());
        }
        responses.add(providerError());

        try (ProcessorFixture fixture = new ProcessorFixture(workspace, responses)) {
            for (int index = 0; index < 6; index++) {
                fixture.enqueue("task-" + index);
            }
            MemoryBackgroundProcessor processor = fixture.processor();

            // 1. 六项 extraction 全部完成后，才发出第七个 consolidation 请求。
            assertEquals(0, processor.runPendingAndWait());
            assertEquals(7, fixture.requestBodies().size());
            assertEquals(6, fixture.workStore().maintenanceState().unconsolidatedCount());
        }
    }

    /** 验证两小时维护不要求达到五条即可整理。 */
    @Test
    void timedMaintenanceConsolidatesAnyPositiveCount() throws Exception {
        try (RecordingScheduler scheduler = new RecordingScheduler();
             ProcessorFixture fixture = new ProcessorFixture(workspace, List.of(consolidationSuccess()))) {
            fixture.createExistingMemory();
            fixture.enqueue("completed-task");
            fixture.workStore().completeTaskAndIncrement("completed-task", 1);
            MemoryBackgroundProcessor processor = fixture.processor(scheduler);
            processor.start();

            // 1. 手工运行两小时任务，验证 count=1 也会整理并清零。
            scheduler.scheduledTasks().get(1).command().run();
            assertEquals(0, fixture.workStore().maintenanceState().unconsolidatedCount());
            assertEquals(1, fixture.requestBodies().size());

            // 整理同样会重新生成记忆正文，因此必须复用中文输出约束。
            assertTrue(fixture.requestBodies().getFirst().contains("简体中文"));
        }
    }

    /** 验证下一次显式批次只重试上次失败后仍保留的任务。 */
    @Test
    void retriesOnlyRemainingTaskOnNextExplicitBatch() throws Exception {
        try (ProcessorFixture fixture = new ProcessorFixture(
                workspace,
                List.of(extractionSuccess(), providerError(), extractionSuccess())
        )) {
            fixture.enqueue("task-1");
            fixture.enqueue("task-2");
            MemoryBackgroundProcessor processor = fixture.processor();

            // 1. 第一批删除成功任务并保留失败任务，不在方法内部循环重试。
            assertEquals(1, processor.runPendingAndWait());
            assertEquals(2, fixture.requestBodies().size());
            assertEquals(1, fixture.workStore().pendingTaskCount());

            // 2. 第二次手工触发只产生一个请求，证明已完成任务不会重新执行。
            assertEquals(0, processor.runPendingAndWait());
            assertEquals(3, fixture.requestBodies().size());
            assertEquals(0, fixture.workStore().pendingTaskCount());
        }
    }

    /** 验证提取请求声明 JSON 协议，并要求记忆自然语言内容使用中文。 */
    @Test
    void extractionPromptDeclaresJsonOutputAndChineseContent() throws Exception {
        try (ProcessorFixture fixture = new ProcessorFixture(
                workspace,
                List.of(extractionSuccess())
        )) {
            fixture.enqueue("task-1");

            // 1. 执行一次真实 SDK 请求构造，并由本地 Provider 返回固定成功流。
            assertEquals(0, fixture.processor().runPendingAndWait());

            // 百炼会拒绝提示词中没有 JSON 关键词的 output_config 请求，
            // 因此直接验证发送给 Provider 的最终请求体，而不是只检查本地常量。
            assertTrue(fixture.requestBodies().getFirst().contains("JSON"));

            // 自然语言字段需要显式约束，否则英文历史会让模型继续生成英文记忆。
            assertTrue(fixture.requestBodies().getFirst().contains("简体中文"));
        }
    }

    /** 创建一项只含最小用户消息的冻结 extraction task。 */
    private static MemoryExtractionTask task(String taskId) {
        MessageParam message = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("remember this")
                .build();
        return new MemoryExtractionTask(taskId, List.of(message), List.of(message));
    }

    /** 返回一条成功创建 Memory 的结构化流。 */
    private static ProviderResponse extractionSuccess() {
        return new ProviderResponse(200, "text/event-stream", textStream(
                "extract",
                "{\"needModelContext\":false,\"memories\":[{\"name\":\"travel-note\","
                        + "\"type\":\"user\",\"description\":\"Travel note\","
                        + "\"body\":\"The user shared a travel note.\"}]}"
        ));
    }

    /** 返回一次保留空集合的成功整理响应。 */
    private static ProviderResponse consolidationSuccess() {
        return new ProviderResponse(
                200,
                "text/event-stream",
                textStream(
                        "consolidate",
                        "{\"memories\":[{\"name\":\"existing-memory\","
                                + "\"type\":\"project\",\"description\":\"Existing memory\","
                                + "\"body\":\"Existing body.\"}]}"
                )
        );
    }

    /** 返回不会被 SDK 自动重试的固定 Provider 错误。 */
    private static ProviderResponse providerError() {
        return new ProviderResponse(
                500,
                "application/json",
                "{\"type\":\"server_error\",\"message\":\"provider failed\"}"
        );
    }

    /** 构造 SDK MessageAccumulator 可以解析的文本 SSE。 */
    private static String textStream(String messageId, String content) {
        String encodedContent;
        try {
            encodedContent = ObjectMappers.jsonMapper().writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("无法构造测试 SSE", exception);
        }

        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"" + messageId
                + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                + "\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":" + encodedContent + "}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    /** 持有测试服务器、真实 Memory 组件和临时持久化状态。 */
    private static final class ProcessorFixture implements AutoCloseable {

        private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        private final List<ProviderResponse> responses;
        private final HttpServer server;
        private final AnthropicClient client;
        private final MemoryWorkStore workStore;
        private final MemoryExtractor extractor;
        private final MemoryRepository repository;
        private final MemoryConsolidator consolidator;
        private MemoryBackgroundProcessor processor;

        private ProcessorFixture(Path workspace, List<ProviderResponse> responses) throws Exception {
            this.responses = List.copyOf(responses);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
            client = AnthropicOkHttpClient.builder()
                    .apiKey("test-key")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .maxRetries(0)
                    .build();

            SessionState sessionState = new SessionState(
                    true,
                    ToolApprovalMode.BYPASS,
                    workspace,
                    workspace,
                    List.of(workspace),
                    workspace
            );
            WorkspacePathResolver paths = new WorkspacePathResolver(sessionState);
            workStore = new MemoryWorkStore(workspace);
            extractor = new MemoryExtractor(client, "test-model");
            repository = new MemoryRepository(paths);
            consolidator = new MemoryConsolidator(client, "test-model", repository);
        }

        /** 创建使用真实后台线程的处理器。 */
        private MemoryBackgroundProcessor processor() {
            processor = new MemoryBackgroundProcessor(
                    workStore,
                    extractor,
                    repository,
                    consolidator,
                    () -> true
            );
            return processor;
        }

        /** 创建使用记录型测试时钟的处理器。 */
        private MemoryBackgroundProcessor processor(RecordingScheduler scheduler) {
            processor = new MemoryBackgroundProcessor(
                    workStore,
                    extractor,
                    repository,
                    consolidator,
                    () -> true,
                    scheduler
            );
            return processor;
        }

        /** 持久化一项待处理工作。 */
        private void enqueue(String taskId) {
            workStore.enqueue(task(taskId));
        }

        /** 创建两小时整理所需的一条既有 Memory。 */
        private void createExistingMemory() throws IOException {
            repository.createNew(List.of(new MemoryEntry(
                    "existing-memory",
                    MemoryType.PROJECT,
                    "Existing memory",
                    "Existing body."
            )));
        }

        private MemoryWorkStore workStore() {
            return workStore;
        }

        private List<String> requestBodies() {
            return requestBodies;
        }

        /** 按请求顺序返回固定 Provider 响应。 */
        private void respond(HttpExchange exchange) throws IOException {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int responseIndex = requestBodies.size() - 1;
            ProviderResponse response = responseIndex < responses.size()
                    ? responses.get(responseIndex)
                    : providerError();
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        /** 关闭测试拥有的后台线程和本地 Provider。 */
        @Override
        public void close() {
            if (processor != null) {
                processor.close();
            }
            client.close();
            server.stop(0);
        }
    }

    /** 记录 executor 收到的立即任务和固定延迟任务。 */
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {

        private final List<Runnable> immediateTasks = new ArrayList<>();
        private final List<ScheduledTask> scheduledTasks = new ArrayList<>();

        private RecordingScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            immediateTasks.add(command);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command,
                long initialDelay,
                long delay,
                TimeUnit unit
        ) {
            scheduledTasks.add(new ScheduledTask(command, Duration.ofMillis(unit.toMillis(delay))));
            // 测试手工运行记录的任务；该占位 Future 不会在用例期间触发。
            return super.schedule(() -> { }, 1, TimeUnit.DAYS);
        }

        private List<Runnable> immediateTasks() {
            return immediateTasks;
        }

        private List<ScheduledTask> scheduledTasks() {
            return scheduledTasks;
        }
    }

    /** 一项被记录但由测试手工触发的周期任务。 */
    private record ScheduledTask(Runnable command, Duration delay) { }

    /** 本地 Provider 的固定 HTTP 响应。 */
    private record ProviderResponse(int status, String contentType, String body) { }
}
