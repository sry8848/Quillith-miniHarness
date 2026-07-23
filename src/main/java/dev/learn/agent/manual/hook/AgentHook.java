package dev.learn.agent.manual.hook;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.List;
import java.util.Optional;

/**
 * Agent 生命周期的扩展点。
 *
 * 实现类只需重写自己关心的方法，其他方法默认不干预流程。
 */
public interface AgentHook {

    /**
     * 用户消息发送给模型前触发。
     */
    default void onUserPromptSubmit(String userPrompt) {
    }

    /**
     * 工具执行前触发。
     *
     * @return empty 表示允许执行；有值表示拒绝执行，值中保存拒绝原因
     */
    default Optional<String> beforeToolUse(ToolUseBlock toolUse) {
        return Optional.empty();
    }

    /**
     * 工具执行后触发。
     */
    default void afterToolUse(ToolUseBlock toolUse, String output) {
    }

    /**
     * Agent 准备停止前触发。
     *
     * @return empty 表示允许停止；有值表示继续执行，值中保存追加给模型的消息
     */
    default Optional<String> onStop(List<MessageParam> messages) {
        return Optional.empty();
    }
}
