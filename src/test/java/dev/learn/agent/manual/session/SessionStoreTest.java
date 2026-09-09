package dev.learn.agent.manual.session;

import com.anthropic.models.messages.MessageParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        store.createSessionWithFirstMessage("session-1", "workspace", 0, first);
        store.saveContextCheckpoint("session-1", 0, List.of(first));
        store.appendCommitted("session-1", 1, List.of(second));

        // 3. 重开后仍能以 Checkpoint 加后续消息恢复活动上下文。
        SessionStore.LoadedSession loaded = store.loadSession("session-1", "workspace");
        assertEquals(List.of(first, second), loaded.messages());
        assertEquals(0, loaded.checkpoint().throughSeq());
        assertEquals(List.of(first), loaded.checkpoint().context());
        assertNull(loaded.inflight());
        assertEquals(1, loaded.latestTurnSeq());
        assertEquals(2, store.nextTurnSequence("session-1"));
        assertEquals("first request", store.listSessions().getFirst().firstUserMessage());
    }

    /**
     * 验证恢复封口直接复用最后一条 committed message 的 Turn 序号。
     */
    @Test
    void commitsRecoveredMessagesWithLatestTurnSequence() throws Exception {
        SessionStore store = new SessionStore(agentHome);
        MessageParam first = user("interrupted request");
        MessageParam repaired = user("stream interrupted");

        // 1. 先保存当前 Turn 的用户输入和空 in-flight，模拟尚未封口的模型流。
        store.createSessionWithFirstMessage("session-1", "workspace", 0, first);
        store.recordClosedAssistantContent("session-1", List.of());
        SessionStore.LoadedSession loaded = store.loadSession("session-1", "workspace");
        assertNotNull(loaded.inflight());

        // 2. 恢复消息使用加载时读取的最后 Turn 序号，而不是创建新的 Turn。
        store.commitCompletedTurn("session-1", loaded.latestTurnSeq(), List.of(repaired));

        assertEquals(0, store.loadSession("session-1", "workspace").latestTurnSeq());
        assertEquals(1, store.nextTurnSequence("session-1"));
    }

    /** 验证后台提取只读取当前 Turn 的 canonical 消息。 */
    @Test
    void readsMessagesForOneCommittedTurn() throws Exception {
        SessionStore store = new SessionStore(agentHome);
        MessageParam first = user("first request");
        MessageParam reminder = user("<system-reminder>remember this</system-reminder>");
        MessageParam second = user("second request");

        // 1. 同一 Turn 的隐藏提醒与普通消息都必须完整返回。
        store.createSessionWithFirstMessage("session-1", "workspace", 0, first);
        store.appendCommitted("session-1", 0, List.of(reminder));
        store.appendCommitted("session-1", 1, List.of(second));

        assertEquals(List.of(first, reminder), store.listTurnMessages("session-1", "workspace", 0));
        assertEquals(List.of(second), store.listTurnMessages("session-1", "workspace", 1));
    }

    /** 构造普通用户消息。 */
    private static MessageParam user(String text) {
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(text)
                .build();
    }
}
