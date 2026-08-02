package dev.learn.agent.manual.systemprompt;

/**
 * 一段最终进入 Anthropic system 的提示词内容。
 *
 * @param id      稳定身份，同时作为最终 section 标签名
 * @param scope   内容的刷新生命周期
 * @param order   section 的排列顺序
 * @param content 不包含外层标签的正文
 */
public record SystemPromptItem(
        String id,
        RefreshScope scope,
        int order,
        String content
) {}
