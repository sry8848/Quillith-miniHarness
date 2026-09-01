package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.cli.ExecRunner;
import dev.learn.agent.manual.cli.InteractiveRunner;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.output.StreamOutputPrinter;
import dev.learn.agent.manual.recovery.ModelRequestRecoveryManager;
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
 * 验证 ManualAgent 保持父用户 Turn 的现有编排行为。
 */
class ManualAgentTest {

    @TempDir
    Path workspace;

    @Test
    void keepsConversationHistoryAcrossSubmissions()
            throws Exception {
        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText(),
                                     ProviderResponse.finalText()
                             )
                     )) {
            fixture.manualAgent()
                    .submit(
                            "first request"
                    );
            fixture.manualAgent()
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

        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
                             workspace,
                             hookRegistry,
                             List.of()
                     )) {
            fixture.manualAgent()
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
    void rollsBackAnUnrecoverableProviderError()
            throws Exception {
        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.providerError(),
                                     ProviderResponse.finalText()
                             )
                     )) {
            assertThrows(
                    AnthropicServiceException.class,
                    () ->
                            fixture.manualAgent()
                                    .submit(
                                            "failed request"
                                    )
            );
            fixture.manualAgent()
                    .submit(
                            "retry request"
                    );

            assertEquals(
                    2,
                    fixture.requestBodies()
                            .size()
            );
            String retryRequest =
                    fixture.requestBodies()
                            .get(1);
            assertFalse(
                    retryRequest.contains(
                            "failed request"
                    ),
                    retryRequest
            );
            assertTrue(
                    retryRequest.contains(
                            "retry request"
                    ),
                    retryRequest
            );
        }
    }

    @Test
    void providerErrorSkipsMemoryCompletion()
            throws Exception {
        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
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
                            fixture.manualAgent()
                                    .submit(
                                            "failed before memory completion"
                                    )
            );

            assertEquals(
                    1,
                    fixture.requestBodies()
                            .size()
            );
        }
    }

    /**
     * 验证 ExecRunner 只提交一条任务，并把正常 Turn 映射为退出码 0。
     */
    @Test
    void execRunnerReturnsZeroAfterOneSubmission()
            throws Exception {
        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.finalText()
                             )
                     )) {
            int exitCode =
                    new ExecRunner().run(
                            fixture.manualAgent(),
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
        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
                             workspace,
                             new HookRegistry(),
                             List.of(
                                     ProviderResponse.providerError()
                             )
                     )) {
            int exitCode =
                    new ExecRunner().run(
                            fixture.manualAgent(),
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
     * 验证 InteractiveRunner 在 ManualAgent 已处理 Provider Error 后继续读取下一条输入。
     */
    @Test
    void interactiveRunnerContinuesAfterProviderError()
            throws Exception {
        try (ManualAgentFixture fixture =
                     new ManualAgentFixture(
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
                    fixture.manualAgent()
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

    private static final class ManualAgentFixture
            implements AutoCloseable {

        private final List<String> requestBodies =
                Collections.synchronizedList(
                        new ArrayList<>()
                );
        private final HttpServer server;
        private final AnthropicClient client;
        private final BackgroundTaskScheduler backgroundScheduler;
        private final ManualAgent manualAgent;

        private ManualAgentFixture(
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

        private ManualAgentFixture(
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

            WorkspacePathResolver paths =
                    new WorkspacePathResolver(
                            workspace
                    );
            AgentState agentState =
                    new AgentState(
                            memoryEnabled,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            workspace
                    );
            SystemPromptManager systemPromptManager =
                    new SystemPromptManager(
                            List.of()
                    );
            MemoryRuntime memoryRuntime =
                    new MemoryRuntime(
                            memoryEnabled,
                            client,
                            "test-model",
                            paths
                    );
            AgentLoop agentLoop =
                    new AgentLoop(
                            client,
                            "test-model",
                            systemPromptManager,
                            agentState,
                            new ToolRegistry(),
                            new ToolApprovalGate(
                                    new DefaultToolApprovalPolicy(
                                            paths
                                    ),
                                    ToolApprovalMode.BYPASS,
                                    null
                            ),
                            hookRegistry,
                            new ContextManager(
                                    client,
                                    "test-model",
                                    paths
                            ),
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
                            )
                    );
            manualAgent =
                    new ManualAgent(
                            agentLoop,
                            memoryRuntime,
                            hookRegistry,
                            systemPromptManager,
                            agentState
                    );
        }

        private ManualAgent manualAgent() {
            return manualAgent;
        }

        private List<String> requestBodies() {
            return requestBodies;
        }

        @Override
        public void close() {
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
}
