package dev.learn.agent.manual.tool.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.entity.TodoItem;
import dev.learn.agent.manual.tool.entity.TodoState;
import dev.learn.agent.manual.tool.entity.TodoStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 保存并展示 Agent 当前会话中的 TODO 列表。
 *
 * 每次调用都使用模型传入的完整列表替换旧列表。
 * 该工具只负责记录计划，不负责执行计划中的任务。
 */
public final class TodoWriteTool implements AgentTool {

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "todo_write",
                    "开始多步骤任务前，使用此工具创建计划；"
                            + "执行过程中再次调用以更新各任务状态。",
                    Map.of(
                            "todos",
                            JsonValue.from(
                                    Map.of(
                                            "type",
                                            "array",
                                            "description",
                                            "The complete current todo list.",
                                            "items",
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "properties",
                                                    Map.of(
                                                            "content",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "description",
                                                                    "A concrete task to complete."
                                                            ),
                                                            "status",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "description",
                                                                    "The current task status.",
                                                                    "enum",
                                                                    List.of(
                                                                            "pending",
                                                                            "in_progress",
                                                                            "completed"
                                                                    )
                                                            )
                                                    ),
                                                    "required",
                                                    List.of(
                                                            "content",
                                                            "status"
                                                    ),
                                                    "additionalProperties",
                                                    false
                                            )
                                    )
                            )
                    ),
                    List.of("todos")
            );

    private final TodoState todoState;

    /**
     * 创建使用指定会话状态的 TodoWrite 工具。
     */
    public TodoWriteTool(
            TodoState todoState
    ) {
        this.todoState =
                Objects.requireNonNull(
                        todoState,
                        "TodoState 不能为空"
                );
    }

    /**
     * 返回发送给模型的 TodoWrite 工具定义。
     */
    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /**
     * 校验模型提交的完整 TODO 列表，并替换当前列表。
     *
     * @param input 模型生成的 JSON 工具参数
     * @return 返回给模型的执行结果
     */
    @Override
    public ToolExecutionResult execute(
            JsonNode input
    ) {
        JsonNode todosNode =
                input.get("todos");

        if (todosNode == null
                || !todosNode.isArray()) {
            return ToolExecutionResult.failure(
                    "Error: todos must be an array"
            );
        }

        List<TodoItem> updatedTodos =
                new ArrayList<>();

        for (int index = 0;
             index < todosNode.size();
             index++) {
            JsonNode todoNode =
                    todosNode.get(index);

            if (!todoNode.isObject()) {
                return ToolExecutionResult.failure(
                        "Error: todos["
                                + index
                                + "] must be an object"
                );
            }

            JsonNode contentNode =
                    todoNode.get("content");

            if (contentNode == null
                    || !contentNode.isTextual()
                    || contentNode.textValue()
                    .isBlank()) {
                return ToolExecutionResult.failure(
                        "Error: todos["
                                + index
                                + "].content must be a non-blank string"
                );
            }

            JsonNode statusNode =
                    todoNode.get("status");

            if (statusNode == null
                    || !statusNode.isTextual()) {
                return ToolExecutionResult.failure(
                        "Error: todos["
                                + index
                                + "].status must be a string"
                );
            }

            TodoStatus status =
                    switch (statusNode.textValue()) {
                        case "pending" ->
                                TodoStatus.PENDING;
                        case "in_progress" ->
                                TodoStatus.IN_PROGRESS;
                        case "completed" ->
                                TodoStatus.COMPLETED;
                        default -> null;
                    };

            if (status == null) {
                return ToolExecutionResult.failure(
                        "Error: todos["
                                + index
                                + "].status is invalid"
                );
            }

            updatedTodos.add(
                    new TodoItem(
                            contentNode.textValue(),
                            status
                    )
            );
        }

        todoState.replace(
                updatedTodos
        );

        List<TodoItem> currentTodos =
                todoState.currentTodos();

        printCurrentTodos(
                currentTodos
        );

        return ToolExecutionResult.success(
                "Updated "
                        + currentTodos.size()
                        + " tasks"
        );
    }

    /**
     * 在终端展示当前任务进度，方便用户观察 Agent 的执行计划。
     */
    private void printCurrentTodos(  List<TodoItem> currentTodos ) {
        System.out.println();
        System.out.println("当前任务");

        for (TodoItem todo : currentTodos) {
            String icon =
                    switch (todo.status()) {
                        case PENDING -> " ";
                        case IN_PROGRESS -> "→";
                        case COMPLETED -> "✓";
                    };

            System.out.printf(
                    "[%s] %s%n",
                    icon,
                    todo.content()
            );
        }
    }
}
