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
     * 执行模型请求的工具操作。
     *
     * @param input 模型生成的 JSON 参数
     * @return 包含输出内容和失败状态的工具执行结果
     */
    ToolExecutionResult execute(JsonNode input);
}
