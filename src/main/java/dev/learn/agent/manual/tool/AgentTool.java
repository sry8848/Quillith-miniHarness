package dev.learn.agent.manual.tool;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个可以提供给模型调用的工具。
 */
public interface AgentTool {

    /**
     * 返回发送给模型的工具定义。
     *
     * 定义包含：
     * 工具名称、功能描述和 JSON 参数结构。
     */
    Tool definition();

    /**
     * 判断该工具是否可以与其他并发安全工具同时执行。
     *
     * 未明确声明的工具默认独占执行，避免新增工具意外绕过副作用边界。
     *
     * @return 可以安全并发时返回 true，否则返回 false
     */
    default boolean isConcurrencySafe() {
        // 默认按存在副作用处理，只有实现类确认只读后才能主动放开。
        return false;
    }

    /**
     * 根据本次输入选择工具进入的调度路径。
     *
     * @param input 模型生成的 JSON 参数
     * @return 工具调用的执行模式，默认进入前台调度器
     */
    default ToolExecutionMode executionMode(
            JsonNode input
    ) {
        // 只有明确支持后台语义的工具才可以脱离前台顺序调度。
        return ToolExecutionMode.FOREGROUND;
    }

    /**
     * 执行模型请求的工具操作。
     *
     * @param input 模型生成的 JSON 参数
     * @return 包含输出内容和失败状态的工具执行结果
     */
    ToolExecutionResult execute(JsonNode input);
}
