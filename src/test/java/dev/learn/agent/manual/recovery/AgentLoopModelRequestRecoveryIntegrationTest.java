// 声明 AgentLoop 模型请求恢复集成测试所属的包。
package dev.learn.agent.manual.recovery;

// 引入真实 Anthropic SDK 客户端、JDK HTTP 测试服务和 Agent 运行依赖。
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.learn.agent.manual.AgentLoop;
import dev.learn.agent.manual.AgentState;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.output.StreamOutputPrinter;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.tool.approval.DefaultToolApprovalPolicy;
import dev.learn.agent.manual.tool.approval.ToolApprovalGate;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从真实 HTTP 响应边界验证 AgentLoop 的模型请求级恢复链路。
 */
class AgentLoopModelRequestRecoveryIntegrationTest {

    // JUnit 为 ContextManager 提供不污染仓库的真实工作区。
    @TempDir
    Path workspace;

    /**
     * Given 模型先返回 tool_use、随后拒绝 Tool Result、最后正常结束，When AgentLoop 执行，Then 只执行一次 Tool 并使用替换后的结果重试。
     */
    @Test
    void shouldRecoverARejectedToolResultThroughTheRealRequestLoop()
            throws Exception {
        // 服务端脚本先返回 Tool Use，再拒绝结果，最后返回最终文本。
        try (AgentFixture fixture =
                     new AgentFixture(
                             workspace,
                             List.of(
                                     ProviderResponse.toolUse(),
                                     ProviderResponse.contentRejection(),
                                     ProviderResponse.finalText()
                             )
                     )) {
            // 让 AgentLoop 从普通用户输入开始，pending Tool Result 必须由真实第一轮产生。
            List<MessageParam> messages =
                    fixture.userMessages();

            String result =
                    fixture.agentLoop()
                            .run(
                                    messages,
                                    ""
                            );

            // 验证 AgentLoop 仍然只有一条主循环，第三次请求返回最终文本。
            assertEquals(
                    "done",
                    result
            );
            assertEquals(
                    3,
                    fixture.requestBodies()
                            .size()
            );

            // Tool 是真实执行一次后提交结果，恢复请求不能再次执行它。
            assertEquals(
                    1,
                    fixture.toolExecutions()
                            .get()
            );

            String recoveryRequest =
                    fixture.requestBodies()
                            .get(
                            3 - 1
                            );
            assertTrue(
                    recoveryRequest.contains(
                            "The tool completed"
                    ),
                    recoveryRequest
            );
            assertFalse(
                    recoveryRequest.contains(
                            "sensitive original result"
                    )
            );
            assertTrue(
                    recoveryRequest.contains(
                            "tool-1"
                    )
            );
            assertTrue(
                    recoveryRequest.contains(
                            "test_tool"
                    )
            );
        }
    }

    /**
     * Given Provider 直接拒绝没有 Tool Result 的首次请求，When AgentLoop 执行，Then 原异常立即抛出且只发送一次请求。
     */
    @Test
    void shouldThrowOriginalExceptionWhenNoPendingToolResultExists()
            throws Exception {
        // 只返回一次请求级 Content Rejection，不伪造任何已执行工具。
        try (AgentFixture fixture =
                     new AgentFixture(
                             workspace,
                             List.of(
                                     ProviderResponse.contentRejection()
                             )
                     )) {
            List<MessageParam> messages =
                    fixture.userMessages();

            AnthropicServiceException thrown =
                    assertThrows(
                            AnthropicServiceException.class,
                            () -> fixture.agentLoop()
                                    .run(
                                            messages,
                                            ""
                                    )
                    );

            // 没有可安全替换的 pending 结果时，不重试也不修改用户输入。
            assertEquals(
                    400,
                    thrown.statusCode()
            );
            assertEquals(
                    1,
                    fixture.requestBodies()
                            .size()
            );
            assertEquals(
                    0,
                    fixture.toolExecutions()
                            .get()
            );
            assertEquals(
                    1,
                    messages.size()
            );
        }
    }

    /**
     * Given Tool Result 连续被拒绝三次，When AgentLoop 执行，Then 第三次拒绝原样抛出且不重复执行 Tool。
     */
    @Test
    void shouldThrowAfterContentRecoveryLimitIsExhausted()
            throws Exception {
        // 第一轮产生真实 Tool Result，随后连续三次触发同一请求级错误。
        try (AgentFixture fixture =
                     new AgentFixture(
                             workspace,
                             List.of(
                                     ProviderResponse.toolUse(),
                                     ProviderResponse.contentRejection(),
                                     ProviderResponse.contentRejection(),
                                     ProviderResponse.contentRejection()
                             )
                     )) {
            AnthropicServiceException thrown =
                    assertThrows(
                            AnthropicServiceException.class,
                            () -> fixture.agentLoop()
                                    .run(
                                            fixture.userMessages(),
                                            ""
                                    )
                    );

            // 两次恢复后第三次拒绝直接结束，不能再发第四次请求。
            assertEquals(
                    400,
                    thrown.statusCode()
            );
            assertEquals(
                    4,
                    fixture.requestBodies()
                            .size()
            );
            assertEquals(
                    1,
                    fixture.toolExecutions()
                            .get()
            );
            assertTrue(
                    fixture.requestBodies()
                            .get(
                                    3 - 1
                            )
                            .contains(
                                    "The tool completed"
                            )
            );
        }
    }

    /**
     * Given 一条恢复链已耗尽后 Provider 接受新的 tool_use，When 新批次再次被拒绝，Then 新 State 允许重新恢复。
     */
    @Test
    void shouldStartANewRecoveryChainAfterAnAcceptedRequest()
            throws Exception {
        // 第一批连续拒绝两次后成功返回第二批工具调用，验证旧计数不会泄漏。
        try (AgentFixture fixture =
                     new AgentFixture(
                             workspace,
                             List.of(
                                     ProviderResponse.toolUse(
                                             "tool-1"
                                     ),
                                     ProviderResponse.contentRejection(),
                                     ProviderResponse.contentRejection(),
                                     ProviderResponse.toolUse(
                                             "tool-2"
                                     ),
                                     ProviderResponse.contentRejection(),
                                     ProviderResponse.finalText()
                             )
                     )) {
            String result =
                    fixture.agentLoop()
                            .run(
                                    fixture.userMessages(),
                                    ""
                            );

            // 第二批仍能恢复，说明成功请求已经替换了上一条耗尽的 State。
            assertEquals(
                    "done",
                    result
            );
            assertEquals(
                    6,
                    fixture.requestBodies()
                            .size()
            );
            assertEquals(
                    2,
                    fixture.toolExecutions()
                            .get()
            );
            assertTrue(
                    fixture.requestBodies()
                            .get(
                                    5 - 1
                            )
                            .contains(
                                    "The tool completed"
                            )
            );
        }
    }

    /**
     * 按请求序号返回脚本化的真实 Provider 响应。
     */
    private static void respond(
            HttpExchange exchange,
            List<String> requestBodies,
            List<ProviderResponse> responses
    ) throws IOException {
        // 读取 SDK 发出的完整 JSON 请求体，供测试直接检查实际请求内容。
        requestBodies.add(
                new String(
                        exchange.getRequestBody()
                                .readAllBytes(),
                        StandardCharsets.UTF_8
                )
        );
        int responseIndex =
                requestBodies.size() - 1;
        ProviderResponse response =
                responseIndex < responses.size()
                        ? responses.get(
                        responseIndex
                )
                        : ProviderResponse.unexpectedRequest();

        // 将脚本中当前序号的 HTTP 状态和响应体原样交给真实 SDK。
        writeResponse(
                exchange,
                response.status(),
                response.contentType(),
                response.body()
        );
    }

    /**
     * 为集成测试组装真实 AgentLoop 和本地 HTTP Provider。
     */
    private final class AgentFixture
            implements AutoCloseable {

        // 记录 Provider 实际收到的 JSON 请求。
        private final List<String> requestBodies =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        // 记录真实 Tool 执行次数。
        private final AtomicInteger toolExecutions =
                new AtomicInteger();

        // 管理本地测试 Provider 的 HTTP 服务。
        private final HttpServer server;

        // 使用真实 Anthropic SDK 发起模型请求。
        private final AnthropicClient client;

        // 管理 AgentLoop 可能创建的后台任务线程。
        private final BackgroundTaskScheduler backgroundScheduler;

        // 暴露给测试方法的完整 AgentLoop。
        private final AgentLoop agentLoop;

        private AgentFixture(
                Path workspace,
                List<ProviderResponse> responses
        ) throws Exception {
            // 创建随机端口的本地 Provider，并按脚本处理所有请求。
            this.server =
                    HttpServer.create(
                            new InetSocketAddress(
                                    "127.0.0.1",
                                    0
                            ),
                            0
                    );
            server.createContext(
                    "/",
                    exchange -> respond(
                            exchange,
                            requestBodies,
                            responses
                    )
            );
            server.start();

            // 使用真实 SDK HTTP 客户端连接本地 Provider，关闭 SDK 自身重试。
            this.client =
                    AnthropicOkHttpClient.builder()
                            .apiKey(
                                    "test-key"
                            )
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress()
                                            .getPort()
                            )
                            .maxRetries(
                                    0
                            )
                            .build();
            this.backgroundScheduler =
                    new BackgroundTaskScheduler();

            // 组装与生产 AgentLoop 相同的依赖边界，只把 Provider 指向本地服务。
            ToolRegistry toolRegistry =
                    new ToolRegistry();
            toolRegistry.register(
                    new CountingTool(
                            toolExecutions
                    )
            );
            WorkspacePathResolver paths =
                    new WorkspacePathResolver(
                            workspace
                    );
            ContextManager contextManager =
                    new ContextManager(
                            client,
                            "test-model",
                            paths
                    );
            AgentState agentState =
                    new AgentState(
                            false,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            workspace
                    );
            ToolApprovalGate approvalGate =
                    new ToolApprovalGate(
                            new DefaultToolApprovalPolicy(
                                    paths
                            ),
                            agentState,
                            null
                    );
            this.agentLoop =
                    new AgentLoop(
                            client,
                            "test-model",
                            new SystemPromptManager(
                                    List.of()
                            ),
                            agentState,
                            toolRegistry,
                            approvalGate,
                            new HookRegistry(),
                            contextManager,
                            new StreamOutputPrinter(
                                    new PrintStream(
                                            new ByteArrayOutputStream(),
                                            true,
                                            StandardCharsets.UTF_8
                                    )
                            ),
                            backgroundScheduler,
                            3,
                            new ModelRequestRecoveryManager(
                                    List.of(
                                            new ContentRejectionRecoveryHandler()
                                    )
                            )
                    );
        }

        private List<MessageParam> userMessages() {
            // 每个场景从同一条普通用户消息开始，避免预先伪造 pending 状态。
            return new ArrayList<>(
                    List.of(
                            MessageParam.builder()
                                    .role(
                                            MessageParam.Role.USER
                                    )
                                    .content(
                                            "run test tool"
                                    )
                                    .build()
                    )
            );
        }

        private List<String> requestBodies() {
            return requestBodies;
        }

        private AtomicInteger toolExecutions() {
            return toolExecutions;
        }

        private AgentLoop agentLoop() {
            return agentLoop;
        }

        @Override
        public void close() {
            // 释放本地客户端、后台执行器和 HTTP 服务，避免测试资源泄漏。
            backgroundScheduler.close();
            client.close();
            server.stop(
                    0
            );
        }
    }

    /**
     * 描述本地 Provider 对一次请求返回的 HTTP 响应。
     */
    private record ProviderResponse(
            int status,
            String contentType,
            String body
    ) {

        private static ProviderResponse toolUse() {
            return toolUse(
                    "tool-1"
            );
        }

        private static ProviderResponse toolUse(
                String toolUseId
        ) {
            return new ProviderResponse(
                    200,
                    "text/event-stream",
                    toolUseStream(
                            toolUseId
                    )
            );
        }

        private static ProviderResponse contentRejection() {
            return new ProviderResponse(
                    400,
                    "application/json",
                    "{\"code\":\"DataInspectionFailed\",\"message\":\"tool result rejected\"}"
            );
        }

        private static ProviderResponse finalText() {
            return new ProviderResponse(
                    200,
                    "text/event-stream",
                    finalTextStream()
            );
        }

        private static ProviderResponse unexpectedRequest() {
            return new ProviderResponse(
                    500,
                    "application/json",
                    "{\"error\":\"unexpected request\"}"
            );
        }
    }

    /**
     * 写出 HTTP 测试 Provider 响应。
     */
    private static void writeResponse(
            HttpExchange exchange,
            int status,
            String contentType,
            String body
    ) throws IOException {
        // 使用完整响应长度，让 SDK 按正常 HTTP 响应读取 SSE 内容。
        byte[] bytes =
                body.getBytes(
                        StandardCharsets.UTF_8
                );
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        contentType
                );
        exchange.sendResponseHeaders(
                status,
                bytes.length
        );
        try (OutputStream output =
                     exchange.getResponseBody()) {
            output.write(
                    bytes
            );
        }
    }

    /**
     * 构造第一轮包含完整 tool_use 的 Anthropic SSE 响应。
     */
    private static String toolUseStream() {
        return toolUseStream(
                "tool-1"
        );
    }

    /**
     * 构造指定工具 ID 的第一轮 tool_use SSE 响应。
     */
    private static String toolUseStream(
            String toolUseId
    ) {
        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\""
                + toolUseId
                + "\",\"name\":\"test_tool\",\"input\":{}}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    /**
     * 构造第三轮正常结束的文本 SSE 响应。
     */
    private static String finalTextStream() {
        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-3\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    /**
     * 提供一个只计数且返回固定结果的真实 AgentTool。
     */
    private static final class CountingTool
            implements AgentTool {

        // 记录 AgentLoop 实际执行工具的次数。
        private final AtomicInteger executions;

        private CountingTool(
                AtomicInteger executions
        ) {
            this.executions =
                    executions;
        }

        @Override
        public Tool definition() {
            return ToolDefinitionFactory.create(
                    "test_tool",
                    "A test tool used by the integration test.",
                    Map.of(
                            "value",
                            JsonValue.from(
                                    Map.of(
                                            "type",
                                            "string"
                                    )
                            )
                    ),
                    List.of()
            );
        }

        @Override
        public ToolExecutionResult execute(
                JsonNode input
        ) {
            // 返回原始结果，之后应由 Content Handler 在恢复请求中替换。
            executions.incrementAndGet();
            return ToolExecutionResult.success(
                    "sensitive original result"
            );
        }
    }
}
