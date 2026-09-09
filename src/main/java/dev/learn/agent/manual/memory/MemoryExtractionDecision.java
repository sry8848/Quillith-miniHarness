package dev.learn.agent.manual.memory;

import java.util.List;

/**
 * 提取器首轮判断：直接给出记忆，或请求已冻结的模型上下文。
 *
 * @param needModelContext 是否需要第二次模型调用
 * @param memories 已提取的记忆草稿
 */
public record MemoryExtractionDecision(
        boolean needModelContext,
        List<MemoryDraft> memories
) {
}
