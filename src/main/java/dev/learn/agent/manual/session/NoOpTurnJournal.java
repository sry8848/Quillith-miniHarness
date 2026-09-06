package dev.learn.agent.manual.session;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.util.List;

/**
 * 子 Agent 使用的空 Journal，防止中间历史进入父 Session。
 */
public final class NoOpTurnJournal implements TurnJournal {

    @Override
    public void recordClosedAssistantContent(List<ContentBlockParam> assistantContent) {
    }

    @Override
    public void recordToolUse(List<ContentBlockParam> assistantContent, String toolUseId) {
    }

    @Override
    public void markToolRunning(String toolUseId) {
    }

    @Override
    public void completeTool(String toolUseId, ToolExecutionResult result) {
    }

    @Override
    public void appendCommitted(List<MessageParam> messages) {
    }

    @Override
    public void commitCompletedTurn(List<MessageParam> messages) {
    }

    @Override
    public void saveContextCheckpoint(ConversationState state) {
    }
}
