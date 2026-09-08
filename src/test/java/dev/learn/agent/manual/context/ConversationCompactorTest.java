package dev.learn.agent.manual.context;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.*;
import com.sun.net.httpserver.HttpServer;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.session.*;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SQLite 和本地 HTTP 验证递归输入与提交边界。 */
class ConversationCompactorTest {
    @TempDir Path workspace;

    @Test void recursivelyCompactsWholeTurnsAndRestoresCheckpoint() throws Exception {
        try (Fixture f = new Fixture()) {
            // 1. 每个 Turn 含多个 user 角色消息，确保不能按 role 计数。
            for (int i = 0; i < 8; i++) f.append(i);
            assertFalse(f.compactor.compact(f.conversation));
            assertTrue(f.requests.isEmpty());
            f.append(8);
            List<MessageParam> canonical = List.copyOf(f.conversation.messages());
            assertTrue(f.compactor.compact(f.conversation));
            assertEquals(canonical.subList(0, 9), f.conversation.modelContext().subList(0, 9));
            assertEquals(canonical.subList(12, 27), f.conversation.modelContext().subList(10, 25));
            assertTrue(f.conversation.modelContext().get(9).content().asString().contains("coverage: 9-11"));
            assertTrue(f.requests.getFirst().contains("unique-turn-3"));
            assertFalse(f.requests.getFirst().contains("unique-turn-2"));
            assertFalse(f.compactor.compact(f.conversation));
            assertEquals(1, f.requests.size());

            // 2. 从 Checkpoint 加后续消息重建上下文，再扩大一次 coverage。
            f.append(9);
            var loaded = f.store.loadSession(f.state.sessionId(), workspace.toString());
            List<MessageParam> restored = new ArrayList<>(loaded.checkpoint().context());
            restored.addAll(loaded.messages().subList((int) loaded.checkpoint().throughSeq() + 1,
                    loaded.messages().size()));
            f.conversation.restore(loaded.messages(), restored, loaded.checkpoint().throughSeq());
            assertTrue(f.compactor.compact(f.conversation));
            assertTrue(f.requests.get(1).contains("previous-summary"));
            assertTrue(f.requests.get(1).contains("unique-turn-4"));
            assertFalse(f.requests.get(1).contains("unique-turn-3"));
            assertTrue(f.conversation.modelContext().get(9).content().asString().contains("coverage: 9-14"));
            assertEquals(loaded.messages(), f.store.loadSession(f.state.sessionId(), workspace.toString()).messages());
            assertEquals(29, f.conversation.checkpointThroughSeq());
        }
    }

    @Test void rejectsFailedSummariesWithoutChangingState() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 9; i++) f.append(i);
            var original = List.copyOf(f.conversation.modelContext());
            for (String reason : List.of("max_tokens", "refusal", "end_turn")) {
                f.reason = reason;
                f.summary = reason.equals("end_turn") ? "" : "partial";
                assertThrows(IllegalStateException.class, () -> f.compactor.compact(f.conversation));
                assertEquals(original, f.conversation.modelContext());
                assertNull(f.store.loadSession(f.state.sessionId(), workspace.toString()).checkpoint());
            }
        }
    }

    @Test void validatesCoverageBeforeCallingModel() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 9; i++) f.append(i);
            f.compactor.compact(f.conversation);
            String valid = f.conversation.modelContext().get(9).content().asString();
            for (String malformed : List.of(valid.replace(f.state.sessionId(), "other-session"),
                    valid.replace("coverage: 9-11", "coverage: 9-99"),
                    valid.replace("coverage: 9-11", "coverage: 10-11"))) {
                f.conversation.modelContext().set(9, user(malformed));
                assertThrows(IllegalStateException.class, () -> f.compactor.compact(f.conversation));
            }
            assertEquals(1, f.requests.size());
        }
    }

    /** 构造普通 user 消息。 */
    private static MessageParam user(String text) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(text).build();
    }

    private final class Fixture implements AutoCloseable {
        final SessionState state;
        final SessionStore store;
        final ConversationState conversation = new ConversationState();
        final HttpServer server;
        final AnthropicClient client;
        final ConversationCompactor compactor;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        volatile String reason = "end_turn";
        volatile String summary = "[消息 9-11]\nprevious-summary";

        /** 创建本地摘要服务，捕获完整输入并返回可控文本。 */
        Fixture() throws Exception {
            state = new SessionState(false, ToolApprovalMode.BYPASS, workspace, workspace,
                    List.of(workspace), null);
            store = new SessionStore(workspace);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = ObjectMappers.jsonMapper().writeValueAsBytes(Map.of(
                        "id", "summary-id", "type", "message", "role", "assistant", "model", "test-model",
                        "content", List.of(Map.of("type", "text", "text", summary)), "stop_reason", reason,
                        "usage", Map.of("input_tokens", 10, "output_tokens", 10)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.start();
            client = AnthropicOkHttpClient.builder().apiKey("test-key").maxRetries(0)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            compactor = new ConversationCompactor(client, "test-model", store, state);
        }

        /** 每个用户 Turn 带一对真实工具协议消息，tool_result 不能另计 Turn。 */
        void append(long turn) throws Exception {
            var first = user("unique-turn-" + turn);
            if (turn == 0) store.createSessionWithFirstMessage(state.sessionId(), workspace.toString(), turn, first);
            else store.appendCommitted(state.sessionId(), turn, List.of(first));
            var remaining = List.of(
                    ObjectMappers.jsonMapper().readValue("""
                            {"role":"assistant","content":[{"type":"tool_use","id":"tool-%d","name":"read_file","input":{"path":"a"}}]}
                            """.formatted(turn), MessageParam.class),
                    ObjectMappers.jsonMapper().readValue("""
                            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-%d","content":"tool output"}]}
                            """.formatted(turn), MessageParam.class));
            store.appendCommitted(state.sessionId(), turn, remaining);
            conversation.appendCommitted(first);
            conversation.appendCommitted(remaining);
        }

        @Override public void close() {
            client.close();
            server.stop(0);
            store.close();
        }
    }
}
