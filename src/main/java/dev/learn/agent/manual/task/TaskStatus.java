package dev.learn.agent.manual.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 持久化任务的三个状态。 */
public enum TaskStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    // JSON 中保存的稳定状态值。
    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    /** 返回 JSON 使用的状态值。 */
    @JsonValue
    public String value() {
        return value;
    }

    /** 从 JSON 状态值恢复枚举。 */
    @JsonCreator
    public static TaskStatus fromValue(String value) {
        // 任务文件只接受教程定义的三个状态。
        for (TaskStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("非法任务状态：" + value);
    }
}
