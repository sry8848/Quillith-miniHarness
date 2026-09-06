package dev.learn.agent.manual.session;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.util.List;

/**
 * 记录 AgentLoop 在流和工具执行期间产生的可恢复事实。
 */
public interface TurnJournal {

    /** 保存已经完整关闭的 assistant 内容。 */
    void recordClosedAssistantContent(List<ContentBlockParam> assistantContent);

    /** 保存完整 tool_use 与 NOT_STARTED。 */
    void recordToolUse(List<ContentBlockParam> assistantContent, String toolUseId);

    /** 在真实工具副作用前标记 RUNNING。 */
    void markToolRunning(String toolUseId);

    /** 保存最终工具结果。 */
    void completeTool(String toolUseId, ToolExecutionResult result);

    /** 追加不需要清理 in-flight 的正式历史消息。 */
    void appendCommitted(List<MessageParam> messages);

    /** 原子提交正式历史并清理 in-flight。 */
    void commitCompletedTurn(List<MessageParam> messages);

    /** 保存最新的压缩模型上下文。 */
    void saveContextCheckpoint(ConversationState state);
}
