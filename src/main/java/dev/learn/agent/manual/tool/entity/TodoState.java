package dev.learn.agent.manual.tool.tools;

import java.util.List;
import java.util.Objects;

/**
 * 保存当前 Agent 会话的 TODO 状态。
 *
 * TodoWriteTool 负责更新它，
 * TodoReminderHook 负责读取它。
 */
public final class TodoState {

    private List<TodoItem> currentTodos =
            List.of();

    /**
     * 返回当前 TODO 的不可变快照。
     */
    public List<TodoItem> currentTodos() {
        return currentTodos;
    }

    /**
     * 使用完整的新列表替换当前 TODO。
     *
     * @param todos 已经通过工具边界校验的新列表
     */
    public void replace(
            List<TodoItem> todos
    ) {
        currentTodos =
                List.copyOf(
                        Objects.requireNonNull(
                                todos,
                                "TODO 列表不能为空"
                        )
                );
    }
}