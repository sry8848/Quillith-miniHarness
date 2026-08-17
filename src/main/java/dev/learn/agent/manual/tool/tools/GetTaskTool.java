package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.learn.agent.manual.task.TaskRecord;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 读取一个跨会话任务的完整内容。 */
public final class GetTaskTool implements AgentTool {
    // 普通 JsonMapper 能正确序列化 Java record。
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // get_task 只接收任务 ID。
    private static final Tool DEFINITION = ToolDefinitionFactory.create(
            "get_task",
            "读取持久化任务的完整 JSON。",
            Map.of("task_id", ToolDefinitionFactory.stringProperty("任务 ID。")),
            List.of("task_id")
    );

    // 只读查询使用共享 Store。
    private final TaskStore store;

    /** 创建任务详情工具。 */
    public GetTaskTool(TaskStore store) {
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

    /** 返回完整任务 JSON。 */
    @Override
    public ToolExecutionResult execute(JsonNode input) {
        try {
            JsonNode taskId = input == null ? null : input.get("task_id");
            if (taskId == null || !taskId.isTextual()) {
                throw new IllegalArgumentException("task_id must be a string");
            }
            TaskRecord task = store.get(taskId.textValue());
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(task);
            return ToolExecutionResult.success(json);
        } catch (JsonProcessingException | IllegalArgumentException
                 | IllegalStateException | UncheckedIOException exception) {
            return ToolExecutionResult.failure("Error: " + exception.getMessage());
        }
    }
}
