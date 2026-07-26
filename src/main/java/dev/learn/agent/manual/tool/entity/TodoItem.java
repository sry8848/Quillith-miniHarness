package dev.learn.agent.manual.tool.entity;

import java.util.Objects;

/**
 * Agent 当前计划中的一项任务。
 *
 * @param content 任务内容
 * @param status  当前执行状态
 */
public record TodoItem(
        String content,
        TodoStatus status
) {

    /**
     * 保证进入内部任务列表的数据符合基本契约。
     */
    public TodoItem {
        if (content == null
                || content.isBlank()) {
            throw new IllegalArgumentException(
                    "TODO 内容不能为空"
            );
        }

        Objects.requireNonNull(
                status,
                "TODO 状态不能为空"
        );
    }
}