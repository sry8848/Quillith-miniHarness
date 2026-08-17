package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.task.TaskRecord;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 认领一个可以开始的 pending 任务。 */
public final class ClaimTaskTool implements AgentTool {
    // owner 由应用绑定，不暴露给模型。
    private static final Tool DEFINITION = ToolDefinitionFactory.create(
            "claim_task",
            "认领依赖已完成的 pending 任务。",
            Map.of("task_id", ToolDefinitionFactory.stringProperty("待认领任务 ID。")),
            List.of("task_id")
    );

    // 共享任务存储。
    private final TaskStore store;

    // Harness 信任的执行者身份。
    private final String owner;

    /** 创建绑定 owner 的认领工具。 */
    public ClaimTaskTool(TaskStore store, String owner) {
        this.store = Objects.requireNonNull(store, "TaskStore 不能为空");
        this.owner = requireOwner(owner);
    }

    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /** 执行 pending 到 in_progress 转换。 */
    @Override
    public ToolExecutionResult execute(JsonNode input) {
        try {
            JsonNode taskId = input == null ? null : input.get("task_id");
            if (taskId == null || !taskId.isTextual()) {
                throw new IllegalArgumentException("task_id must be a string");
            }
            TaskRecord task = store.claim(taskId.textValue(), owner);
            return ToolExecutionResult.success(
                    "Claimed " + task.id() + " (" + task.subject() + ") [owner: " + task.owner() + "]"
            );
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException exception) {
            return ToolExecutionResult.failure("Error: " + exception.getMessage());
        }
    }

    /** 校验应用装配时提供的 owner。 */
    private static String requireOwner(String owner) {
        String value = Objects.requireNonNull(owner, "任务 owner 不能为空").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("任务 owner 不能为空");
        }
        return value;
    }
}
