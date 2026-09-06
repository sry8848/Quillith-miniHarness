package dev.learn.agent.manual.session;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.util.List;
import java.util.Objects;

/**
 * 把父 AgentLoop 的流式事实写入当前 Session。
 */
public final class SessionTurnJournal implements TurnJournal {

    private final SessionStore store;
    private final SessionState sessionState;

    /** 创建绑定当前 SessionState 的持久化 Journal。 */
    public SessionTurnJournal(SessionStore store, SessionState sessionState) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState 不能为空");
    }

    @Override
    public void recordClosedAssistantContent(List<ContentBlockParam> assistantContent) {
        store.recordClosedAssistantContent(sessionState.sessionId(), assistantContent);
    }

    @Override
    public void recordToolUse(List<ContentBlockParam> assistantContent, String toolUseId) {
        store.recordToolUse(sessionState.sessionId(), assistantContent, toolUseId);
    }

    @Override
    public void markToolRunning(String toolUseId) {
        store.markToolRunning(sessionState.sessionId(), toolUseId);
    }

    @Override
    public void completeTool(String toolUseId, ToolExecutionResult result) {
        store.completeTool(sessionState.sessionId(), toolUseId, result);
    }

    @Override
    public void appendCommitted(List<MessageParam> messages) {
        store.appendCommitted(sessionState.sessionId(), messages);
    }

    @Override
    public void commitCompletedTurn(List<MessageParam> messages) {
        store.commitCompletedTurn(sessionState.sessionId(), messages);
    }

    @Override
    public void saveContextCheckpoint(ConversationState state) {
        store.saveContextCheckpoint(
                sessionState.sessionId(),
                state.checkpointThroughSeq(),
                state.modelContext()
        );
    }
}
