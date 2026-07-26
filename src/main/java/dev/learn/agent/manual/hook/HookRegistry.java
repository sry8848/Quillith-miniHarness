package dev.learn.agent.manual.hook;

import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
     * 按参数顺序注册一组 Hook。
     *
     * Hook 的触发顺序与这里的参数顺序一致。
     *
     * @param hooks 需要注册的 Hook
     */
    public void registerAll(
            AgentHook... hooks
    ) {
        Objects.requireNonNull(
                hooks,
                "Hook 数组不能为空"
        );

        for (AgentHook hook : hooks) {
            registerHook(hook);
        }
    }

    /**
     * 通知所有 Hook：用户提交了消息。
     */
    public HookEffect triggerUserPromptSubmit(
            String userPrompt
    ) {
        HookEffect combined =
                HookEffect.proceed();

        for (AgentHook hook : hooks) {
            HookEffect effect =
                    Objects.requireNonNull(
                            hook.onUserPromptSubmit(
                                    userPrompt
                            ),
                            "UserPromptSubmit Hook 不能返回 null"
                    );

            if (effect.updatedInput() != null
                    || effect.updatedOutput() != null) {
                throw new IllegalStateException(
                        "UserPromptSubmit 不能修改工具输入或输出"
                );
            }

            combined =
                    combined.and(effect);
        }

        return combined;
    }

    /**
     * 在每次模型请求前触发全部 Hook。
     *
     * Hook 可以通过 additionalContexts 追加上下文，
     * 但不能阻止模型调用，也不能修改工具输入或输出。
     */
    public HookEffect triggerBeforeModelCall(
            List<MessageParam> messages
    ) {
        List<MessageParam> readOnlyMessages =
                List.copyOf(messages);

        HookEffect combined =
                HookEffect.proceed();

        for (AgentHook hook : hooks) {
            HookEffect effect =
                    Objects.requireNonNull(
                            hook.beforeModelCall(
                                    readOnlyMessages
                            ),
                            "BeforeModelCall Hook 不能返回 null"
                    );

            if (effect.decision()
                    == HookEffect.Decision.BLOCK) {
                throw new IllegalStateException(
                        "BeforeModelCall 不能阻止模型调用"
                );
            }

            if (effect.updatedInput() != null
                    || effect.updatedOutput() != null) {
                throw new IllegalStateException(
                        "BeforeModelCall 不能修改工具输入或输出"
                );
            }

            combined =
                    combined.and(effect);
        }

        return combined;
    }

    /**
     * 在工具执行前按注册顺序执行全部 Hook。
     *
     * 输入修改会立即传给下一个 Hook，
     * 因此多个修改结果的顺序是确定的。
     * 即使某个 Hook 阻止执行，后续日志或审计 Hook 仍会运行。
     */
    public HookEffect triggerBeforeToolUse(
            ToolCall toolCall
    ) {
        ToolCall currentToolCall =
                Objects.requireNonNull(
                        toolCall,
                        "ToolCall 不能为空"
                );

        HookEffect combined =
                HookEffect.proceed();

        for (AgentHook hook : hooks) {
            HookEffect effect =
                    Objects.requireNonNull(
                            hook.beforeToolUse(
                                    currentToolCall
                            ),
                            "PreToolUse Hook 不能返回 null"
                    );

            if (effect.updatedOutput() != null) {
                throw new IllegalStateException(
                        "PreToolUse 不能修改尚未产生的工具输出"
                );
            }

            combined =
                    combined.and(effect);

            if (effect.updatedInput() != null) {
                currentToolCall =
                        currentToolCall.withInput(
                                effect.updatedInput()
                        );
            }
        }

        return combined;
    }

    /**
     * 通知所有 Hook：工具已经执行完成。
     */
    public HookEffect triggerAfterToolUse(
            ToolCall toolCall,
            String output
    ) {
        Objects.requireNonNull(
                toolCall,
                "ToolCall 不能为空"
        );

        String currentOutput =
                Objects.requireNonNull(
                        output,
                        "工具输出不能为空"
                );

        HookEffect combined =
                HookEffect.proceed();

        for (AgentHook hook : hooks) {
            HookEffect effect =
                    Objects.requireNonNull(
                            hook.afterToolUse(
                                    toolCall,
                                    currentOutput
                            ),
                            "PostToolUse Hook 不能返回 null"
                    );

            if (effect.updatedInput() != null) {
                throw new IllegalStateException(
                        "PostToolUse 不能修改已经使用过的工具输入"
                );
            }

            combined =
                    combined.and(effect);

            if (effect.updatedOutput() != null) {
                currentOutput =
                        effect.updatedOutput();
            }
        }

        return combined;
    }

    /**
     * Agent 准备停止前执行全部 Hook。
     * 第一个 BLOCK 原因获胜，但所有清理和统计 Hook 都会执行。
     */
    public HookEffect triggerStop(
            List<MessageParam> messages
    ) {
        List<MessageParam> readOnlyMessages = List.copyOf(messages);

        HookEffect combined =
                HookEffect.proceed();

        for (AgentHook hook : hooks) {
            HookEffect effect =
                    Objects.requireNonNull(
                            hook.onStop(
                                    readOnlyMessages
                            ),
                            "Stop Hook 不能返回 null"
                    );

            if (effect.updatedInput() != null
                    || effect.updatedOutput() != null) {
                throw new IllegalStateException(
                        "Stop 不能修改工具输入或输出"
                );
            }

            combined =
                    combined.and(effect);
        }

        return combined;
    }
}
