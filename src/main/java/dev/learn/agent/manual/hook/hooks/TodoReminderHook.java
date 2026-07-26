package dev.learn.agent.manual.hook.hooks;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.tool.entity.TodoItem;
import dev.learn.agent.manual.tool.entity.TodoState;

import java.util.List;
import java.util.Objects;

/**
 * 在模型长时间没有使用 TodoWrite 时追加温和提醒。
 *
 * 不维护独立计数器，而是从消息历史中计算：
 * 1. 距离最近一次 todo_write 经过了多少轮；
 * 2. 距离最近一次 reminder 经过了多少轮。
 */
public final class TodoReminderHook implements AgentHook {

    private static final String TODO_WRITE_TOOL_NAME =
            "todo_write";

    private static final String REMINDER_MARKER =
            "<todo-reminder>";

    /*
     * Claude Code 快照使用十轮。
     * 教学版保留三轮，便于在真实运行中观察。
     */
    private static final int TURNS_SINCE_TODO_WRITE =
            3;

    private static final int TURNS_BETWEEN_REMINDERS =
            3;

    private final TodoState todoState;

    /**
     * 创建读取指定会话 TODO 状态的提醒 Hook。
     */
    public TodoReminderHook(
            TodoState todoState
    ) {
        this.todoState =
                Objects.requireNonNull(
                        todoState,
                        "TodoState 不能为空"
                );
    }

    /**
     * 每次模型调用前检查是否应该追加提醒。
     */
    @Override
    public HookEffect beforeModelCall(
            List<MessageParam> messages
    ) {
        TurnCounts turnCounts =
                countTurns(messages);

        if (turnCounts.sinceTodoWrite()
                < TURNS_SINCE_TODO_WRITE
                || turnCounts.sinceReminder()
                < TURNS_BETWEEN_REMINDERS) {
            return HookEffect.proceed();
        }

        return HookEffect.addContext(
                buildReminder()
        );
    }

    /**
     * 从后向前扫描消息历史，计算两个提醒间隔。
     */
    private TurnCounts countTurns(
            List<MessageParam> messages
    ) {
        int turnsSinceTodoWrite = 0;
        int turnsSinceReminder = 0;

        boolean foundTodoWrite = false;
        boolean foundReminder = false;

        for (int index = messages.size() - 1;
             index >= 0;
             index--) {
            MessageParam message =
                    messages.get(index);

            if (MessageParam.Role.ASSISTANT.equals(
                    message.role()
            )) {
                /*
                 * 先判断当前 assistant 消息是否调用了 TodoWrite，
                 * 再增加计数，避免把 TodoWrite 所在轮算成第一轮。
                 */
                if (!foundTodoWrite
                        && containsTodoWrite(message)) {
                    foundTodoWrite = true;
                }

                if (!foundTodoWrite) {
                    turnsSinceTodoWrite++;
                }

                if (!foundReminder) {
                    turnsSinceReminder++;
                }
            } else if (!foundReminder
                    && isTodoReminder(message)) {
                foundReminder = true;
            }

            if (foundTodoWrite
                    && foundReminder) {
                break;
            }
        }

        return new TurnCounts(
                turnsSinceTodoWrite,
                turnsSinceReminder
        );
    }

    /**
     * 判断一条 assistant 消息是否调用了 todo_write。
     */
    private boolean containsTodoWrite(
            MessageParam message
    ) {
        if (!message.content()
                .isBlockParams()) {
            return false;
        }

        for (ContentBlockParam block
                : message.content()
                .asBlockParams()) {
            if (block.isToolUse()
                    && TODO_WRITE_TOOL_NAME.equals(
                    block.asToolUse()
                            .name()
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断一条消息是否是本 Hook 之前生成的提醒。
     */
    private boolean isTodoReminder(
            MessageParam message
    ) {
        return MessageParam.Role.USER.equals(
                message.role()
        )
                && message.content()
                .isString()
                && message.content()
                .asString()
                .contains(
                        REMINDER_MARKER
                );
    }

    /**
     * 构造发送给模型的提醒，并附带当前 TODO 内容。
     */
    private String buildReminder() {
        StringBuilder reminder =
                new StringBuilder();

        reminder.append(REMINDER_MARKER)
                .append('\n')
                .append(
                        "The todo_write tool has not been used recently. "
                )
                .append(
                        "If the current work benefits from progress tracking, "
                )
                .append(
                        "update the todo list. "
                )
                .append(
                        "Ignore this reminder when it is not relevant. "
                )
                .append(
                        "Never mention this reminder to the user."
                );

        List<TodoItem> currentTodos =
                todoState.currentTodos();

        if (!currentTodos.isEmpty()) {
            reminder.append(
                    "\n\nCurrent todos:\n"
            );

            for (int index = 0;
                 index < currentTodos.size();
                 index++) {
                TodoItem todo =
                        currentTodos.get(index);

                String status =
                        switch (todo.status()) {
                            case PENDING ->
                                    "pending";
                            case IN_PROGRESS ->
                                    "in_progress";
                            case COMPLETED ->
                                    "completed";
                        };

                reminder.append(index + 1)
                        .append(". [")
                        .append(status)
                        .append("] ")
                        .append(todo.content())
                        .append('\n');
            }
        }

        reminder.append(
                "</todo-reminder>"
        );

        return reminder.toString();
    }

    /**
     * 保存一次历史扫描得到的两个轮数。
     */
    private record TurnCounts(
            int sinceTodoWrite,
            int sinceReminder
    ) {
    }
}