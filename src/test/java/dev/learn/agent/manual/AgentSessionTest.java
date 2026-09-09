package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.cli.ExecRunner;
import dev.learn.agent.manual.cli.InteractiveRunner;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.context.ConversationCompactor;
import java.util.Optional;
import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.memory.MemoryEntry;
import dev.learn.agent.manual.memory.MemoryRepository;
import dev.learn.agent.manual.memory.MemoryType;
import dev.learn.agent.manual.output.StreamOutputPrinter;
import dev.learn.agent.manual.recovery.ModelRequestRecoveryManager;
import dev.learn.agent.manual.session.ConversationState;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.session.SessionTurnJournal;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
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
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AgentSession 保持父用户 Turn 的现有编排行为。
 */
class AgentSessionTest {

    @TempDir
    Path workspace;

    @Test
    void returnsSessionStateId()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of()
                     )) {
            assertEquals(
                    fixture.sessionState()
                            .sessionId(),
                    fixture.agentSession()
                            .sessionId()
            );
        }
    }

    @Test
    void keepsConversationHistoryAcrossSubmissions()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText(),
                                     ProviderResponse.finalText()
                             )
                     )) {
            fixture.agentSession()
                    .submit(
                            "first request"
                    );
            fixture.agentSession()
                    .submit(
                            "second request"
                    );

            assertEquals(
                    2,
                    fixture.requestBodies()
                            .size()
            );
            String secondRequest =
                    fixture.requestBodies()
                            .get(1);
            assertTrue(
                    secondRequest.contains(
                            "first request"
                    ),
                    secondRequest
            );
            assertTrue(
                    secondRequest.contains(
                            "second request"
                    ),
                    secondRequest
            );

            // 1. 两次 submit 各自持久化为一个连续 Turn，不复用上一轮的序号。
            assertEquals(
                    2,
                    fixture.sessionStore().nextTurnSequence(
                            fixture.agentSession().sessionId()
                    )
            );
        }
    }

    @Test
    void blockedPromptDoesNotCallTheModel()
            throws Exception {
        HookRegistry hookRegistry =
                new HookRegistry();
        hookRegistry.registerHook(
                new AgentHook() {
                    @Override
                    public HookEffect onUserPromptSubmit(
                            String userPrompt
                    ) {
                        return HookEffect.block(
                                "blocked for test"
                        );
                    }
                }
        );

        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             hookRegistry,
                             List.of()
                     )) {
            fixture.agentSession()
                    .submit(
                            "blocked request"
                    );

            assertTrue(
                    fixture.requestBodies()
                            .isEmpty()
            );
        }
    }

    @Test
    void retainsFirstUserMessageAndAllowsResumeAfterProviderError()
            throws Exception {
        String sessionId;
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(ProviderResponse.providerError())
                     )) {
            assertThrows(
                    AnthropicServiceException.class,
                    () ->
                            fixture.agentSession()
                                    .submit(
                                            "failed request"
                                    )
            );

            // 1. 首次 Provider 失败后，已接受的用户输入仍是持久化 Session 事实。
            sessionId = fixture.agentSession().sessionId();
            assertEquals(1, fixture.agentSession().listSessions().size());
            try (SessionStore store = new SessionStore(workspace)) {
                assertEquals(
                        List.of("failed request"),
                        store.loadSession(sessionId, workspace.toString()).messages().stream()
                                .map(message -> message.content().asString())
                                .toList()
                );
            }
        }

        try (AgentSessionFixture resumedFixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(ProviderResponse.finalText())
                     )) {
            // 2. 新 Runtime 的 resume 只加载 history，不触发新的 Provider 请求。
            resumedFixture.agentSession().resume(sessionId);
            assertTrue(resumedFixture.requestBodies().isEmpty());

            // 3. 恢复后下一次提交会把失败前的用户消息继续带给 Provider。
            resumedFixture.agentSession().submit("retry request");
            String retryRequest = resumedFixture.requestBodies().getFirst();
            assertTrue(retryRequest.contains("failed request"), retryRequest);
            assertTrue(
                    retryRequest.contains(
                            "retry request"
                    ),
                    retryRequest
            );

            // 1. 恢复后的新 submit 必须从中断 Turn 的下一序号继续。
            assertEquals(
                    2,
                    resumedFixture.sessionStore().nextTurnSequence(sessionId)
            );
        }
    }

    /** 验证 AgentSession 不再丢弃 AgentLoop 已经产生的最终文本。 */
    @Test
    void returnsFinalAssistantText()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText()
                             )
                     )) {
            String output =
                    fixture.agentSession()
                            .submit(
                                    "return the result"
                            );

            assertEquals(
                    "done",
                    output
            );
        }
    }

    /** 验证固定 assistant 不调用 Provider，但会进入下一次真实请求的历史。 */
    @Test
    void recordedTurnCommitsFixedAssistantWithoutCallingProvider()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText()
                             )
                     )) {
            String recordedOutput =
                    fixture.agentSession()
                            .submitRecorded(
                                    "remember this user fact",
                                    "fixed assistant response"
                            );

            assertEquals(
                    "fixed assistant response",
                    recordedOutput
            );
            assertTrue(
                    fixture.requestBodies()
                            .isEmpty()
            );

            fixture.agentSession()
                    .submit(
                            "answer now"
                    );

            assertTrue(fixture.requestBodies().size() >= 1);
            String liveRequest =
                    fixture.requestBodies()
                            .getFirst();
            assertTrue(
                    liveRequest.contains(
                            "remember this user fact"
                    ),
                    liveRequest
            );
            assertTrue(
                    liveRequest.contains(
                            "fixed assistant response"
                    ),
                    liveRequest
            );
        }
    }

    /** 验证新 Session 清空活动上下文，同时保留旧 Session 的持久化事实。 */
    @Test
    void newSessionClearsConversationAndKeepsPreviousSession()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText()
                             )
                     )) {
            fixture.agentSession()
                    .submitRecorded(
                            "old user fact",
                            "old assistant fact"
                    );
            String previousSessionId =
                    fixture.agentSession()
                            .sessionId();

            String newSessionId =
                    fixture.agentSession()
                            .startNewSession();
            fixture.agentSession()
                    .submit(
                            "new session question"
                    );

            assertTrue(
                    !previousSessionId.equals(
                            newSessionId
                    )
            );
            String liveRequest =
                    fixture.requestBodies()
                            .getFirst();
            assertTrue(
                    !liveRequest.contains(
                            "old user fact"
                    ),
                    liveRequest
            );
            assertTrue(
                    !liveRequest.contains(
                            "old assistant fact"
                    ),
                    liveRequest
            );
            assertTrue(
                    liveRequest.contains(
                            "new session question"
                    ),
                    liveRequest
            );

            SessionStore.LoadedSession previousSession =
                    fixture.sessionStore()
                            .loadSession(
                                    previousSessionId,
                                    workspace.toString()
                            );
            assertEquals(
                    2,
                    previousSession.messages()
                            .size()
            );
            assertEquals(
                    workspace,
                    fixture.sessionState()
                            .workspace()
            );
        }
    }

    @Test
    void retainsCompletedToolFactsWhenNextProviderRoundFails()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.toolUse(),
                                     ProviderResponse.providerError()
                             )
                     )) {
            assertThrows(
                    AnthropicServiceException.class,
                    () -> fixture.agentSession().submit("run a tool")
            );

            // 已完成的 tool_use/tool_result 在下一轮 Provider 失败后仍保留在完整 history。
            try (SessionStore store = new SessionStore(workspace)) {
                List<MessageParam> messages = store.loadSession(
                        fixture.agentSession().sessionId(),
                        workspace.toString()
                ).messages();
                assertEquals(3, messages.size());
                assertEquals("run a tool", messages.getFirst().content().asString());
                assertEquals(MessageParam.Role.ASSISTANT, messages.get(1).role());
                assertTrue(messages.get(1).content().asBlockParams().getFirst().isToolUse());
                assertEquals(MessageParam.Role.USER, messages.get(2).role());
                assertTrue(messages.get(2).content().asBlockParams().getFirst().isToolResult());
            }
        }
    }

    @Test
    void providerErrorSkipsMemoryCompletion()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.providerError()
                             ),
                             true
                     )) {
            assertThrows(
                    AnthropicServiceException.class,
                    () ->
                            fixture.agentSession()
                                    .submit(
                                            "failed before memory completion"
                                    )
            );

            assertTrue(fixture.requestBodies().size() >= 1);
        }
    }

    /** 验证正常回答不会等待后台 Memory 提取。 */
    @Test
    void completesMemoryTurnWithStructuredProviderResponse()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText()
                             ),
                             true
                     )) {
            // 1. 主模型完成后只入队；返回路径不等待后台模型请求。
            fixture.agentSession()
                    .submit(
                            "I prefer dark mode"
                    );

            assertEquals(
                    1,
                    fixture.requestBodies()
                            .size()
            );
        }
    }

    /** 验证后台 Memory 失败不反向破坏已经完成的主回答。 */
    @Test
    void keepsCompletedAnswerWhenMemoryConsolidationFails()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.memorySelection(),
                                     ProviderResponse.finalText(),
                                     ProviderResponse.structuredMemory(),
                                     ProviderResponse.providerError()
                             ),
                             true
                     )) {
            MemoryRepository repository =
                    new MemoryRepository(
                            new WorkspacePathResolver(
                                    fixture.sessionState()
                            )
                    );

            // 1. 预置记忆以触发召回，后台提取不属于本轮回答的同步路径。
            for (int index = 0; index < 9; index++) {
                repository.save(
                        new MemoryEntry(
                                "existing-memory-" + index,
                                MemoryType.PROJECT,
                                "Existing memory " + index,
                                "Existing body " + index
                        )
                );
            }

            // 2. 召回成功、后台工作尚未完成时也必须交付主回答。
            String answer =
                    fixture.agentSession()
                            .submit(
                                    "I prefer dark mode"
                            );

            assertEquals(
                    "done",
                    answer
            );
            assertTrue(fixture.requestBodies().size() >= 2);
        }
    }

    /**
     * 验证 ExecRunner 只提交一条任务，并把正常 Turn 映射为退出码 0。
     */
    @Test
    void execRunnerReturnsZeroAfterOneSubmission()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText()
                             )
                     )) {
            int exitCode =
                    new ExecRunner().run(
                            fixture.agentSession(),
                            "single exec request"
                    );

            assertEquals(
                    0,
                    exitCode
            );
            assertEquals(
                    1,
                    fixture.requestBodies()
                            .size()
            );
        }
    }

    /**
     * 验证 ExecRunner 不重复处理诊断，只把不可恢复 Provider Error 映射为退出码 1。
     */
    @Test
    void execRunnerReturnsOneAfterProviderError()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.providerError()
                             )
                     )) {
            int exitCode =
                    new ExecRunner().run(
                            fixture.agentSession(),
                            "failed exec request"
                    );

            assertEquals(
                    1,
                    exitCode
            );
            assertEquals(
                    1,
                    fixture.requestBodies()
                            .size()
            );
        }
    }

    /**
     * 验证 InteractiveRunner 在 AgentSession 已处理 Provider Error 后继续读取下一条输入。
     */
    @Test
    void interactiveRunnerContinuesAfterProviderError()
            throws Exception {
        try (AgentSessionFixture fixture =
                     new AgentSessionFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.providerError(),
                                     ProviderResponse.finalText()
                             )
                     );
             Scanner scanner =
                     new Scanner(
                             new StringReader(
                                     "failed request\nnext request\nexit\n"
                             )
                     )) {
            new InteractiveRunner(
                    scanner
            ).run(
                    fixture.agentSession()
            );

            assertEquals(
                    2,
                    fixture.requestBodies()
                            .size()
            );
        }
    }

    private static void respond(
            HttpExchange exchange,
            List<String> requestBodies,
            List<ProviderResponse> responses
    ) throws IOException {
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
                        : ProviderResponse.providerError();

        byte[] bytes =
                response.body()
                        .getBytes(
                                StandardCharsets.UTF_8
                        );
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        response.contentType()
                );
        exchange.sendResponseHeaders(
                response.status(),
                bytes.length
        );
        try (OutputStream output =
                     exchange.getResponseBody()) {
            output.write(
                    bytes
            );
        }
    }

    /** 手动命令只生成摘要，恢复后下一条请求使用 Checkpoint。 */
    @Test
    void compactCommandAndResumeKeepCanonicalHistory() throws Exception {
        try (AgentSessionFixture fixture = new AgentSessionFixture(workspace, new HookRegistry(),
                List.of(ProviderResponse.summary(), ProviderResponse.finalText()))) {
            assertFalse(fixture.agentSession().compact());
            for (int i = 0; i < 9; i++) fixture.agentSession().submitRecorded("turn-" + i, "answer-" + i);
            var before = fixture.sessionStore().loadSession(fixture.agentSession().sessionId(), workspace.toString());
            new InteractiveRunner(new Scanner("/compact\nexit\n")).run(fixture.agentSession());
            var compacted = fixture.sessionStore().loadSession(fixture.agentSession().sessionId(), workspace.toString());
            assertEquals(before.messages(), compacted.messages());
            assertEquals(17, compacted.checkpoint().throughSeq());
            assertTrue(compacted.checkpoint().context().get(6).content().asString().contains("coverage: 6-7"));
            assertFalse(fixture.agentSession().compact());
            assertEquals(1, fixture.requestBodies().size());
            fixture.agentSession().resume(fixture.agentSession().sessionId());
            fixture.agentSession().submit("next");
            assertTrue(fixture.requestBodies().get(1).contains("previous-summary"));
            assertEquals(20, fixture.sessionStore().loadSession(
                    fixture.agentSession().sessionId(), workspace.toString()).messages().size());
        }
    }

    /** 第九个 Turn 使已很长的会话首次出现中间区，在主请求前自动压缩。 */
    @Test
    void automaticallyCompactsBeforeParentRequest() throws Exception {
        try (AgentSessionFixture fixture = new AgentSessionFixture(workspace, new HookRegistry(),
                List.of(ProviderResponse.summary(), ProviderResponse.finalText()))) {
            for (int i = 0; i < 8; i++) {
                fixture.agentSession().submitRecorded("turn-" + i + "x".repeat(7_000), "answer");
            }
            fixture.agentSession().submit("ninth");
            assertEquals(2, fixture.requestBodies().size());
            assertTrue(fixture.requestBodies().get(1).contains("coverage: 6-7"));
            assertEquals(18, fixture.sessionStore().loadSession(
                    fixture.agentSession().sessionId(), workspace.toString()).messages().size());
        }
    }

    private static final class AgentSessionFixture
            implements AutoCloseable {

        private final List<String> requestBodies =
                Collections.synchronizedList(
                        new ArrayList<>()
                );
        private final HttpServer server;
        private final AnthropicClient client;
        private final BackgroundTaskScheduler backgroundScheduler;
        private final SessionState sessionState;
        private final SessionStore sessionStore;
        private final MemoryRuntime memoryRuntime;
        private final AgentSession agentSession;

        private AgentSessionFixture(
                Path workspace,
                HookRegistry hookRegistry,
                List<ProviderResponse> responses
        ) throws Exception {
            this(
                    workspace,
                    hookRegistry,
                    responses,
                    false
            );
        }

        private AgentSessionFixture(
                Path workspace,
                HookRegistry hookRegistry,
                List<ProviderResponse> responses,
                boolean memoryEnabled
        ) throws Exception {
            server =
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

            client =
                    AnthropicOkHttpClient.builder()
                            .apiKey(
                                    "test-key"
                            )
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress()
                                            .getPort()
                            )
                            .maxRetries(0)
                            .build();
            backgroundScheduler =
                    new BackgroundTaskScheduler();

            sessionState =
                    new SessionState(
                            memoryEnabled,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            workspace
                    );
            sessionStore = new SessionStore(sessionState.agentHome());
            WorkspacePathResolver paths =
                    new WorkspacePathResolver(
                            sessionState
                    );
            SystemPromptManager systemPromptManager =
                    new SystemPromptManager(
                            List.of()
                    );
            memoryRuntime =
                    new MemoryRuntime(
                            sessionState,
                            client,
                            "test-model",
                            paths
                    );
            ConversationCompactor compactor = new ConversationCompactor(client, "test-model", sessionStore, sessionState);
            AgentLoop agentLoop =
                    new AgentLoop(
                            client,
                            "test-model",
                            systemPromptManager,
                            sessionState,
                            new ToolRegistry(),
                            new ToolApprovalGate(
                                    new DefaultToolApprovalPolicy(
                                            paths
                                    ),
                                    sessionState,
                                    null
                            ),
                            hookRegistry,
                            new ContextManager(paths),
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
                                    List.of()
                            ),
                            new SessionTurnJournal(sessionStore, sessionState),
                            Optional.of(compactor)
                    );
            agentSession =
                    new AgentSession(
                            agentLoop,
                            memoryRuntime,
                            hookRegistry,
                            systemPromptManager,
                            sessionState,
                            sessionStore,
                            new ConversationState(),
                            compactor
                    );
        }

        private AgentSession agentSession() {
            return agentSession;
        }

        private SessionState sessionState() {
            return sessionState;
        }

        private List<String> requestBodies() {
            return requestBodies;
        }

        private SessionStore sessionStore() {
            return sessionStore;
        }

        @Override
        public void close() {
            memoryRuntime.close();
            sessionStore.close();
            backgroundScheduler.close();
            client.close();
            server.stop(0);
        }
    }

    private record ProviderResponse(
            int status,
            String contentType,
            String body
    ) {

        /** 返回非流式摘要，用于与主 Agent SSE 请求区分。 */
        private static ProviderResponse summary() {
            return new ProviderResponse(200, "application/json", """
                    {"id":"summary","type":"message","role":"assistant","model":"test-model",
                    "content":[{"type":"text","text":"[消息 6-7] previous-summary"}],
                    "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":10}}
                    """);
        }

        private static ProviderResponse finalText() {
            return new ProviderResponse(
                    200,
                    "text/event-stream",
                    finalTextStream()
            );
        }

        private static ProviderResponse providerError() {
            return new ProviderResponse(
                    500,
                    "application/json",
                    "{\"type\":\"server_error\",\"message\":\"provider failed\"}"
            );
        }

        private static ProviderResponse toolUse() {
            return new ProviderResponse(
                    200,
                    "text/event-stream",
                    toolUseStream()
            );
        }

        /** 返回一条符合 SDK 结构化输出协议的固定 Memory 响应。 */
        private static ProviderResponse structuredMemory() {
            return new ProviderResponse(
                    200,
                    "text/event-stream",
                    structuredMemoryStream()
            );
        }

        /** 返回一次没有相关主题的流式选择结果。 */
        private static ProviderResponse memorySelection() {
            return new ProviderResponse(
                    200,
                    "text/event-stream",
                    memorySelectionStream()
            );
        }
    }

    private static String finalTextStream() {
        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
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

    private static String memorySelectionStream() {
        return textStream(
                "msg-memory-selection",
                "[]"
        );
    }

    private static String structuredMemoryStream() {
        return textStream(
                "msg-memory",
                "{\"memories\":[{\"name\":\"user-interface-preference\","
                        + "\"type\":\"user\","
                        + "\"description\":\"User prefers dark mode\","
                        + "\"body\":\"The user prefers dark mode.\"}]}"
        );
    }

    /** 构造 SDK MessageAccumulator 可以完整识别的文本 SSE。 */
    private static String textStream(
            String messageId,
            String text
    ) {
        String encodedText;
        try {
            // 1. 使用项目已有 JSON 编码器转义结构化文本，避免测试响应本身损坏协议。
            encodedText =
                    ObjectMappers.jsonMapper()
                            .writeValueAsString(
                                    text
                            );
        } catch (JsonProcessingException exception) {
            throw new AssertionError(
                    "无法构造测试 SSE 文本",
                    exception
            );
        }

        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\""
                + messageId
                + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                + "\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":"
                + encodedText
                + "}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    private static String toolUseStream() {
        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"test-model\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"missing_tool\",\"input\":{}}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }
}
