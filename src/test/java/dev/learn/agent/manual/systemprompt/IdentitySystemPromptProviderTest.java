package dev.learn.agent.manual.systemprompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证父 Agent 的默认回答语言约束。 */
class IdentitySystemPromptProviderTest {

    /** 默认回答语言是简体中文，但用户明确指定语言时由更具体请求覆盖。 */
    @Test
    void parentIdentityRequiresSimplifiedChineseByDefault() {
        String content = IdentitySystemPromptProvider.parent()
                .load(null)
                .orElseThrow();

        assertTrue(content.contains("除非用户明确指定其他语言，否则使用简体中文回答"));
    }

    /** 整理 Agent 使用中文说明时间字段和折叠边界。 */
    @Test
    void memoryOrganizerIdentityUsesChineseMemoryRules() {
        String content = IdentitySystemPromptProvider.memoryOrganizer()
                .load(null)
                .orElseThrow();

        // 1. 只检查稳定语义，不把整段 Prompt 文案固化进测试。
        assertTrue(content.contains("简体中文"));
        assertTrue(content.contains("created_at"));
        assertTrue(content.contains("目录在父 index 中折叠"));
        assertTrue(content.contains("提交由程序负责"));
    }
}
