package dev.learn.agent.manual.session;

import com.anthropic.models.messages.MessageParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证 SQLite Session 的完整历史和 Context Checkpoint 基本契约。 */
class SessionStoreTest {

    @TempDir
    Path agentHome;

    @Test
    void restoresCheckpointAndLaterCommittedMessages() throws Exception {
        SessionStore store = new SessionStore(agentHome);
        MessageParam first = user("first request");
        MessageParam second = user("second request");

        // 1. 空 Store 不创建可展示 Session。
        assertEquals(List.of(), store.listSessions());

        // 2. 首条消息创建 Session，后续消息只追加完整 history。
        store.createSessionWithFirstMessage("session-1", "workspace", first);
        store.saveContextCheckpoint("session-1", 0, List.of(first));
        store.appendCommitted("session-1", List.of(second));

        // 3. 重开后仍能以 Checkpoint 加后续消息恢复活动上下文。
        SessionStore.LoadedSession loaded = store.loadSession("session-1", "workspace");
        assertEquals(List.of(first, second), loaded.messages());
        assertEquals(0, loaded.checkpoint().throughSeq());
        assertEquals(List.of(first), loaded.checkpoint().context());
        assertNull(loaded.inflight());
        assertEquals("first request", store.listSessions().getFirst().firstUserMessage());
    }

    /** 构造普通用户消息。 */
    private static MessageParam user(String text) {
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(text)
                .build();
    }
}
