package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.task.TaskRecord;
import dev.learn.agent.manual.task.TaskStatus;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 列出跨会话任务。 */
public final class ListTasksTool implements AgentTool {
    // list_tasks 没有参数。
    private static final Tool DEFINITION = ToolDefinitionFactory.create(
            "list_tasks",
            "列出持久化任务的状态、owner、依赖和是否可开始。",
            Map.of(),
            List.of()
    );

    // 只读查询使用共享 Store。
    private final TaskStore store;

    /** 创建任务列表工具。 */
    public ListTasksTool(TaskStore store) {
        this.store = Objects.requireNonNull(store, "TaskStore 不能为空");
    }

    @Override
    public Tool definition() {
        return DEFINITION;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    /** 返回按 ID 排序的一行摘要。 */
    @Override
    public ToolExecutionResult execute(JsonNode input) {
        try {
            List<TaskRecord> tasks = store.list();
            if (tasks.isEmpty()) {
                return ToolExecutionResult.success("No tasks. Use create_task to add one.");
            }

            // 一行一个任务，方便模型快速判断下一步。
            StringBuilder result = new StringBuilder();
            for (TaskRecord task : tasks) {
                String marker = switch (task.status()) {
                    case PENDING -> "[ ]";
                    case IN_PROGRESS -> "[>]";
                    case COMPLETED -> "[x]";
                };
                result.append(marker)
                        .append(' ')
                        .append(task.id())
                        .append(": ")
                        .append(task.subject())
                        .append(" [")
                        .append(task.status().value())
                        .append(']');
                if (task.status() == TaskStatus.PENDING) {
                    result.append(store.canStart(task.id()) ? " [ready]" : " [blocked]");
                }
                if (task.owner() != null) {
                    result.append(" [owner: ").append(task.owner()).append(']');
                }
                if (!task.blockedBy().isEmpty()) {
                    result.append(" [blockedBy: ")
                            .append(String.join(", ", task.blockedBy()))
                            .append(']');
                }
                result.append('\n');
            }
            return ToolExecutionResult.success(result.toString().stripTrailing());
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException exception) {
            return ToolExecutionResult.failure("Error: " + exception.getMessage());
        }
    }
}
