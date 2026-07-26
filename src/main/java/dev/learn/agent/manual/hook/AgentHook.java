package dev.learn.agent.manual.hook;

import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.tool.ToolCall;

import java.util.List;

/**
 * Agent 生命周期的扩展点。
 *
 * 实现类只需重写自己关心的方法，其他方法默认不干预流程。
 */
public interface AgentHook {

    /**
     * 用户消息发送给模型前触发。
     */
    default HookEffect onUserPromptSubmit(String userPrompt) {
        return HookEffect.proceed();
    }

    /**
     * 每次准备调用模型前触发。
     *
     * Hook 只能根据当前消息历史追加上下文，
     * 不能直接修改消息列表，也不能阻止模型调用。
     *
     * @param messages 当前只读消息历史
     * @return 当前 Hook 需要追加的模型上下文
     */
    default HookEffect beforeModelCall(
            List<MessageParam> messages
    ) {
        return HookEffect.proceed();
    }

    /**
     * 工具执行前触发。
     *
     * @return 当前 Hook 对工具调用产生的结构化效果
     */
    default HookEffect beforeToolUse(ToolCall toolCall) {
        return HookEffect.proceed();
    }

    /**
     * 工具执行后触发。
     */
    default HookEffect afterToolUse(
            ToolCall toolCall,
            String output
    ) {
        return HookEffect.proceed();
    }

    /**
     * Agent 准备停止前触发。
     *
     * @return CONTINUE 表示允许停止；BLOCK 表示阻止停止并让模型继续
     */
    default HookEffect onStop(List<MessageParam> messages) {
        return HookEffect.proceed();
    }
}
