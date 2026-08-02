package dev.learn.agent.manual.systemprompt;

import java.util.List;

/**
 * 最近一次刷新后可直接发送给 Anthropic 的完整 System Prompt。
 *
 * @param content        完整 system 字符串
 * @param changedItemIds 本次新增、修改或删除的 Item id
 */
public record SystemPrompt(
        String content,
        List<String> changedItemIds
) {}
