package dev.learn.agent.manual.tool.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.task.TaskRecord;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 创建跨会话任务。 */
public final class CreateTaskTool implements AgentTool {
    // Schema 负责告诉模型参数形状。
    private static final Tool DEFINITION = ToolDefinitionFactory.create(
            "create_task",
            "创建跨会话持久化任务；当前会话的执行清单仍使用 todo_write。",
            Map.of(
                    "subject", JsonValue.from(Map.of("type", "string", "minLength", 1)),
                    "description", ToolDefinitionFactory.stringProperty("任务详细说明。"),
                    "blockedBy", JsonValue.from(Map.of(
                            "type", "array",
                            "items", Map.of("type", "string", "pattern", "^task_[0-9a-f]{8}$")
                    ))
            ),
            List.of("subject")
    );

    // 任务规则和磁盘操作都由 Store 负责。
    private final TaskStore store;

    /** 创建任务工具。 */
    public CreateTaskTool(TaskStore store) {
        this.store = Objects.requireNonNull(store, "TaskStore 不能为空");
    }

    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /** 读取模型参数并创建任务。 */
    @Override
    public ToolExecutionResult execute(JsonNode input) {
        try {
            // 外部输入只校验实际会读取的字段类型。
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("tool input must be an object");
            }
            JsonNode subjectNode = input.get("subject");
            if (subjectNode == null || !subjectNode.isTextual()) {
                throw new IllegalArgumentException("subject must be a string");
            }

            JsonNode descriptionNode = input.get("description");
            if (descriptionNode != null && !descriptionNode.isTextual()) {
                throw new IllegalArgumentException("description must be a string");
            }
            String description = descriptionNode == null ? "" : descriptionNode.textValue();

            JsonNode blockedByNode = input.get("blockedBy");
            List<String> blockedBy = new ArrayList<>();
            if (blockedByNode != null) {
                if (!blockedByNode.isArray()) {
                    throw new IllegalArgumentException("blockedBy must be an array");
                }
                for (JsonNode dependency : blockedByNode) {
                    if (!dependency.isTextual()) {
                        throw new IllegalArgumentException("blockedBy must contain strings");
                    }
                    blockedBy.add(dependency.textValue());
                }
            }

            TaskRecord task = store.create(subjectNode.textValue(), description, blockedBy);
            String result = "Created " + task.id() + ": " + task.subject();
            if (!task.blockedBy().isEmpty()) {
                result += " (blockedBy: " + String.join(", ", task.blockedBy()) + ")";
            }
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException exception) {
            return ToolExecutionResult.failure("Error: " + exception.getMessage());
        }
    }
}
