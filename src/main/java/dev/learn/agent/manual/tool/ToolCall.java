package dev.learn.agent.manual.tool;

import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 一次已经通过模型协议解析的工具调用。
 *
 * ToolUseBlock 由 Anthropic SDK 创建且不能直接替换其中的输入。
 * ToolCall 把工具调用转换成项目内部值对象，使 Hook 修改输入后，
 * 后续 Hook 和真正的工具执行都能使用新输入。
 */
public record ToolCall(
        String id,
        String name,
        JsonNode input
) {

    /**
     * 校验工具调用的内部契约。
     */
    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "ToolCall.id 不能为空"
            );
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "ToolCall.name 不能为空"
            );
        }

        Objects.requireNonNull(
                input,
                "ToolCall.input 不能为空"
        );

        if (!input.isObject()) {
            throw new IllegalArgumentException(
                    "ToolCall.input 必须是 JSON 对象"
            );
        }
    }

    /**
     * 把 Anthropic SDK 的工具请求转换成内部工具调用。
     */
    public static ToolCall from(
            ToolUseBlock toolUse
    ) {
        Objects.requireNonNull(
                toolUse,
                "ToolUseBlock 不能为空"
        );

        return new ToolCall(
                toolUse.id(),
                toolUse.name(),
                toolUse._input()
                        .convert(JsonNode.class)
        );
    }

    /**
     * 保留工具 id 和名称，只替换输入。
     */
    public ToolCall withInput(
            JsonNode updatedInput
    ) {
        return new ToolCall(
                id,
                name,
                updatedInput
        );
    }
}
