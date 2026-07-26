package dev.learn.agent.manual.tool;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * 统一创建 Anthropic 工具定义，减少每个工具重复的 Schema 构造代码。
 */
public final class ToolDefinitionFactory {

    private ToolDefinitionFactory() {
    }

    public static Tool create(
            String name,
            String description,
            Map<String, JsonValue> properties,
            List<String> required
    ) {
        Tool.InputSchema.Properties schemaProperties =
                Tool.InputSchema.Properties.builder()
                        .additionalProperties(properties)
                        .build();

        Tool.InputSchema inputSchema =
                Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(schemaProperties)
                        .required(required)
                        .build();

        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    public static JsonValue stringProperty(
            String description
    ) {
        return JsonValue.from(
                Map.of(
                        "type", "string",
                        "description", description
                )
        );
    }

    public static JsonValue positiveIntegerProperty(
            String description
    ) {
        return JsonValue.from(
                Map.of(
                        "type", "integer",
                        "minimum", 1,
                        "description", description
                )
        );
    }
}
