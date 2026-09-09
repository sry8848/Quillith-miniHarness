package dev.learn.agent.manual.memory;

import com.anthropic.models.messages.MessageParam;

import java.util.List;
import java.util.Objects;

/**
 * 一项已经持久化、可在后台重复执行的记忆提取工作。
 *
 * @param taskId 工作的稳定标识
 * @param turnContext 本轮已提交的完整消息
 * @param modelContext 当前模型使用的压缩上下文
 */
public record MemoryExtractionTask(
        String taskId,
        List<MessageParam> turnContext,
        List<MessageParam> modelContext
) {
    /** 创建不可被调用方后续修改的工作快照。 */
    public MemoryExtractionTask {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }

        // 1. 入队后不再读取实时会话状态，避免后台任务看到其他 Turn 的内容。
        turnContext = List.copyOf(Objects.requireNonNull(turnContext, "turnContext 不能为空"));
        modelContext = List.copyOf(Objects.requireNonNull(modelContext, "modelContext 不能为空"));
    }
}
