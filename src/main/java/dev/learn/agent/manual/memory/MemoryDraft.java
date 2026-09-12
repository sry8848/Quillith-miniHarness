package dev.learn.agent.manual.memory;

/**
 * 表示提取模型返回、即将写入近期目录的一条记忆草稿。
 *
 * <p>该类型只描述结构化响应；模型边界校验由 {@link MemoryExtractor} 负责。</p>
 *
 * @param name 记忆主题名称
 * @param description 说明该文件保存什么内容的 canonical description
 * @param body 记忆正文
 */
public record MemoryDraft(
        String name,
        String description,
        String body
) {
}
