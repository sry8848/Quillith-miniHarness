package dev.learn.agent.manual.hook;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 保存并按注册顺序触发 Agent 的所有 Hook。
 */
public final class HookRegistry {

    private final List<AgentHook> hooks = new ArrayList<>();

    /**
     * 注册一个 Hook。
     */
    public void registerHook(AgentHook hook) {
        hooks.add(Objects.requireNonNull(hook, "AgentHook 不能为空"));
    }

    /**
     * 通知所有 Hook：用户提交了消息。
     */
    public void triggerUserPromptSubmit(String userPrompt) {
        for (AgentHook hook : hooks) {
            hook.onUserPromptSubmit(userPrompt);
        }
    }

    /**
     * 在工具执行前依次检查。
     * 第一个拒绝结果会立即终止后续检查。
     */
    public Optional<String> triggerBeforeToolUse(ToolUseBlock toolUse) {
        for (AgentHook hook : hooks) {
            Optional<String> rejection = hook.beforeToolUse(toolUse);

            if (rejection.isPresent()) {
                return rejection;
            }
        }

        return Optional.empty();
    }

    /**
     * 通知所有 Hook：工具已经执行完成。
     */
    public void triggerAfterToolUse(ToolUseBlock toolUse, String output) {
        for (AgentHook hook : hooks) {
            hook.afterToolUse(toolUse, output);
        }
    }

    /**
     * Agent 准备停止前依次检查。
     * 第一个要求继续的结果会立即终止后续检查。
     */
    public Optional<String> triggerStop(List<MessageParam> messages) {
        List<MessageParam> readOnlyMessages = List.copyOf(messages);

        for (AgentHook hook : hooks) {
            Optional<String> continuation = hook.onStop(readOnlyMessages);

            if (continuation.isPresent()) {
                return continuation;
            }
        }

        return Optional.empty();
    }
}
