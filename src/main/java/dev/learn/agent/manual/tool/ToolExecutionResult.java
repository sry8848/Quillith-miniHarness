package dev.learn.agent.manual.tool;

import java.util.Objects;

/**
 * 一次工具执行返回给 Agent 主循环的结构化结果。
 *
 * @param content 返回给模型的工具输出
 * @param error 是否执行失败
 */
public record ToolExecutionResult(
        String content,
        boolean error
) {

    /**
     * 校验工具执行结果。
     */
    public ToolExecutionResult {
        Objects.requireNonNull(
                content,
                "工具结果内容不能为空"
        );
    }

    /**
     * 创建成功结果。
     *
     * @param content 返回给模型的工具输出
     * @return 成功的工具执行结果
     */
    public static ToolExecutionResult success(
            String content
    ) {
        return new ToolExecutionResult(
                content,
                false
        );
    }

    /**
     * 创建失败结果。
     *
     * @param content 返回给模型的错误说明
     * @return 失败的工具执行结果
     */
    public static ToolExecutionResult failure(
            String content
    ) {
        return new ToolExecutionResult(
                content,
                true
        );
    }
}
