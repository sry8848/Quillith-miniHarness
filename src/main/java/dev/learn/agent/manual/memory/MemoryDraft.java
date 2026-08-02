// 声明记忆模块中的结构化响应类型。
package dev.learn.agent.manual.memory;

/**
 * 表示模型返回的一条待保存记忆。
 *
 * <p>该类型只描述模型响应的数据结构，不承担业务校验。
 * 名称、类型、内容等领域约束，会在转换为 {@link MemoryEntry} 时统一检查。</p>
 *
 * @param name 记忆主题名称
 * @param type 记忆类型的字符串表示
 * @param description 记忆摘要
 * @param body 记忆正文
 */
public record MemoryDraft(
        String name,
        String type,
        String description,
        String body
) {
}