package dev.learn.agent.manual.systemprompt;

import dev.learn.agent.manual.AgentState;

import java.util.Optional;

/**
 * 提供不依赖具体能力的 Agent 基础身份说明。
 */
public final class IdentitySystemPromptProvider
        implements SystemPromptProvider {

    // 保存父 Agent 或子 Agent 各自固定的身份说明。
    private final String content;

    private IdentitySystemPromptProvider(
            String content
    ) {
        this.content = content;
    }

    /**
     * 创建父 Agent 的身份 Provider。
     *
     * @return 父 Agent 身份 Provider
     */
    public static IdentitySystemPromptProvider parent() {
        // 身份只描述通用职责，不重复任何具体工具能力。
        return new IdentitySystemPromptProvider(
                "你是一个编程智能体。请使用当前可用的能力完成用户任务。"
        );
    }

    /**
     * 创建子 Agent 的身份 Provider。
     *
     * @return 子 Agent 身份 Provider
     */
    public static IdentitySystemPromptProvider subagent() {
        // 子 Agent 只完成已委派任务，不声明它没有注册的能力。
        return new IdentitySystemPromptProvider(
                "你是一个编程子智能体。只完成交给你的子任务，"
                        + "必要时使用当前可用的工具，返回简洁且基于事实的结论。"
                        + "不要尝试继续委派任务。"
        );
    }

    /**
     * 返回身份 section 的稳定 id。
     */
    @Override
    public String id() {
        return "identity";
    }

    /**
     * 身份只在应用启动或配置重载时刷新。
     */
    @Override
    public RefreshScope scope() {
        return RefreshScope.APPLICATION;
    }

    /**
     * 身份放在完整 System Prompt 的最前面。
     */
    @Override
    public int order() {
        return 100;
    }

    /**
     * 返回当前 Agent 已经确定的身份说明。
     */
    @Override
    public Optional<String> load(
            AgentState agentState
    ) {
        return Optional.of(content);
    }
}
