package dev.learn.agent.manual.hook;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个 Hook 对当前生命周期事件产生的结构化影响。
 *
 * Hook 方法不再通过 null、Optional<String> 或 void 表达不同含义。
 * 所有效果都通过这个类型返回，再由 HookRegistry 统一组合。
 */
public record HookEffect(
        Decision decision,
        String reason,
        JsonNode updatedInput,
        String updatedOutput,
        List<String> additionalContexts
) {

    /**
     * 控制当前生命周期动作是否继续。
     *
     * CONTINUE 表示不阻止当前动作。
     * BLOCK 在 PreToolUse 中表示阻止工具执行，
     * 在 Stop 中表示阻止 Agent 停止。
     */
    public enum Decision {
        CONTINUE,
        BLOCK
    }

    /**
     * 校验并保存一个 HookEffect。
     */
    public HookEffect {
        Objects.requireNonNull(
                decision,
                "HookEffect.decision 不能为空"
        );

        if (decision == Decision.BLOCK
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "BLOCK 必须提供非空 reason"
            );
        }

        if (decision == Decision.CONTINUE
                && reason != null) {
            throw new IllegalArgumentException(
                    "CONTINUE 不应提供阻止原因"
            );
        }

        if (updatedInput != null
                && !updatedInput.isObject()) {
            throw new IllegalArgumentException(
                    "updatedInput 必须是 JSON 对象"
            );
        }

        Objects.requireNonNull(
                additionalContexts,
                "additionalContexts 不能为空"
        );

        for (String context : additionalContexts) {
            if (context == null || context.isBlank()) {
                throw new IllegalArgumentException(
                        "additionalContext 不能为空"
                );
            }
        }

        additionalContexts =
                List.copyOf(
                        additionalContexts
                );
    }

    /**
     * 不干预当前流程。
     */
    public static HookEffect proceed() {
        return new HookEffect(
                Decision.CONTINUE,
                null,
                null,
                null,
                List.of()
        );
    }

    /**
     * 阻止当前生命周期动作。
     */
    public static HookEffect block(
            String reason
    ) {
        return new HookEffect(
                Decision.BLOCK,
                reason,
                null,
                null,
                List.of()
        );
    }

    /**
     * 替换工具输入。
     */
    public static HookEffect updateInput(
            JsonNode input
    ) {
        return new HookEffect(
                Decision.CONTINUE,
                null,
                Objects.requireNonNull(
                        input,
                        "更新后的工具输入不能为空"
                ),
                null,
                List.of()
        );
    }

    /**
     * 替换工具输出。
     */
    public static HookEffect updateOutput(
            String output
    ) {
        return new HookEffect(
                Decision.CONTINUE,
                null,
                null,
                Objects.requireNonNull(
                        output,
                        "更新后的工具输出不能为空"
                ),
                List.of()
        );
    }

    /**
     * 向模型追加一段上下文。
     */
    public static HookEffect addContext(
            String context
    ) {
        return new HookEffect(
                Decision.CONTINUE,
                null,
                null,
                null,
                List.of(context)
        );
    }

    /**
     * 按注册顺序组合两个效果。
     *
     * 组合规则：
     * 1. BLOCK 一旦出现就不会被后续 CONTINUE 覆盖；
     * 2. 第一个阻止原因获胜，结果保持稳定；
     * 3. 后一个输入或输出修改覆盖前一个；
     * 4. 附加上下文全部保留。
     */
    public HookEffect and(
            HookEffect next
    ) {
        Objects.requireNonNull(
                next,
                "待组合的 HookEffect 不能为空"
        );

        Decision combinedDecision =
                decision == Decision.BLOCK
                        || next.decision == Decision.BLOCK
                        ? Decision.BLOCK
                        : Decision.CONTINUE;

        String combinedReason =
                reason != null
                        ? reason
                        : next.reason;

        JsonNode combinedInput =
                next.updatedInput != null
                        ? next.updatedInput
                        : updatedInput;

        String combinedOutput =
                next.updatedOutput != null
                        ? next.updatedOutput
                        : updatedOutput;

        List<String> combinedContexts =
                new ArrayList<>(
                        additionalContexts
                );

        combinedContexts.addAll(
                next.additionalContexts
        );

        return new HookEffect(
                combinedDecision,
                combinedReason,
                combinedInput,
                combinedOutput,
                combinedContexts
        );
    }
}
