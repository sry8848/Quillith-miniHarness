// 声明记忆模块中的结构化响应类型。
package dev.learn.agent.manual.memory;

// 引入批量记忆需要的集合类型。
import java.util.List;

/**
 * 表示一次记忆提取返回的完整结构化结果。
 *
 * <p>使用外层对象包装列表，是因为 Anthropic Java SDK 的
 * {@code outputConfig(Class<T>)} 需要一个可以在运行时取得的具体类型，
 * 而不能直接表示 {@code List<MemoryDraft>} 的完整泛型信息。</p>
 *
 * @param memories 本轮提取出的记忆草稿；没有值得保存的内容时为空列表
 */
public record MemoryBatch(
        List<MemoryDraft> memories
) {
}