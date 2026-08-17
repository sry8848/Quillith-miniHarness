package dev.learn.agent.manual.task;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一条持久化任务。
 *
 * @param id 任务 ID，同时也是文件名
 * @param subject 简短主题
 * @param description 可为空的详细说明
 * @param status 当前状态
 * @param owner 认领者；pending 时为空
 * @param blockedBy 必须先完成的任务 ID
 */
public record TaskRecord(
        String id,
        String subject,
        String description,
        TaskStatus status,
        String owner,
        List<String> blockedBy
) {
    // 固定 ID 格式阻止模型把路径片段当作任务 ID。
    private static final Pattern ID_PATTERN =
            Pattern.compile("task_[0-9a-f]{8}");

    /** 校验一条来自创建流程或磁盘的任务。 */
    public TaskRecord {
        id = requireId(id);
        subject = Objects.requireNonNull(subject, "任务主题不能为空").trim();
        description = Objects.requireNonNull(description, "任务说明不能为空引用").trim();
        status = Objects.requireNonNull(status, "任务状态不能为空");
        blockedBy = List.copyOf(Objects.requireNonNull(blockedBy, "任务依赖不能为空"));

        // 主题是任务能够被识别的最小信息。
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("任务主题不能为空");
        }

        // owner 与状态必须一起变化，避免磁盘出现半个状态转换。
        if (status == TaskStatus.PENDING && owner != null) {
            throw new IllegalArgumentException("pending 任务不能包含 owner");
        }
        if (status != TaskStatus.PENDING) {
            owner = Objects.requireNonNull(owner, "任务 owner 不能为空").trim();
            if (owner.isEmpty()) {
                throw new IllegalArgumentException("任务 owner 不能为空");
            }
        }

        // 依赖 ID 将参与文件读取，必须使用同一安全格式。
        blockedBy.forEach(TaskRecord::requireId);
    }

    /** 校验并返回任务 ID。 */
    static String requireId(String id) {
        String value = Objects.requireNonNull(id, "任务 ID 不能为空");
        if (!ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("非法任务 ID：" + value);
        }
        return value;
    }
}
