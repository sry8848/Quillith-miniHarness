package dev.learn.agent.manual.systemprompt;

import dev.learn.agent.manual.AgentState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按生命周期刷新 System Prompt Item，并组装完整 Anthropic system。
 */
public final class SystemPromptManager {

    // 保存 Provider 和最近一次成功加载的 Item。
    private final List<SystemPromptProvider> providers;
    private final Map<String, SystemPromptItem> items = new HashMap<>();
    private SystemPrompt current = new SystemPrompt("", List.of());

    /**
     * 创建一个只管理指定 Provider 的 SystemPromptManager。
     *
     * @param providers 当前 Agent 启用的 Prompt Provider
     * @throws IllegalArgumentException Provider id 重复时抛出
     */
    public SystemPromptManager(List<SystemPromptProvider> providers) {
        // 复制注册列表，避免外部在运行中改变能力集合。
        this.providers = List.copyOf(providers);
        Set<String> ids = new HashSet<>();

        // [边界：两个模块声明相同 id → 刷新时会覆盖彼此的 Prompt 内容]
        for (SystemPromptProvider provider : this.providers) {
            if (!ids.add(provider.id())) {
                throw new IllegalArgumentException(
                        "Duplicate system prompt provider id: " + provider.id()
                );
            }
        }
    }

    /**
     * 刷新指定生命周期及所有更短生命周期的 Item。
     *
     * @param refreshScope 本次发生变化的生命周期
     * @param agentState Provider 读取的当前 Session 真实状态
     * @return 刷新后重新组装的完整 System Prompt
     */
    public SystemPrompt refreshFrom(
            RefreshScope refreshScope,
            AgentState agentState
    ) {
        // 记录本次实际新增、修改或删除的 Item。
        List<String> changedItemIds = new ArrayList<>();

        // [核心] 只调用本级以及更短生命周期的 Provider。
        for (SystemPromptProvider provider : providers) {
            if (provider.scope().ordinal() < refreshScope.ordinal()) {
                continue;
            }
            refreshItem(provider, agentState, changedItemIds);
        }

        // [核心] 按稳定顺序添加标签并重新组装完整 system 字符串。
        String content = items.values().stream()
                .sorted(
                        Comparator.comparingInt(SystemPromptItem::order)
                                .thenComparing(SystemPromptItem::id)
                )
                .map(this::render)
                .collect(Collectors.joining("\n\n"));

        // 保存并返回调用方可以直接使用的最新 System Prompt。
        current = new SystemPrompt(content, List.copyOf(changedItemIds));
        return current;
    }

    /**
     * 返回最近一次刷新得到的完整 System Prompt。
     *
     * @return 当前 System Prompt；尚未刷新时内容为空
     */
    public SystemPrompt current() {
        return current;
    }

    /**
     * 根据 Provider 的最新内容新增、更新或删除一个 Item。
     */
    private void refreshItem(
            SystemPromptProvider provider,
            AgentState agentState,
            List<String> changedItemIds
    ) {
        // 读取旧 Item 和能力模块当前提供的最新内容。
        SystemPromptItem oldItem = items.get(provider.id());
        Optional<String> loaded = provider.load(agentState);

        // [核心] Provider 返回空值时删除之前存在的完整 section。
        if (loaded.isEmpty()) {
            if (oldItem != null) {
                items.remove(provider.id());
                changedItemIds.add(provider.id());
            }
            return;
        }

        // 内容没有变化时保留原 Item，不记录本次变更。
        String newContent = loaded.get();
        if (oldItem != null
                && Objects.equals(oldItem.content(), newContent)) {
            return;
        }

        // [核心] 新 Item 或内容变化时，用最新内容替换当前 Item。
        items.put(
                provider.id(),
                new SystemPromptItem(
                        provider.id(),
                        provider.scope(),
                        provider.order(),
                        newContent
                )
        );
        changedItemIds.add(provider.id());
    }

    /**
     * 使用 Item id 为正文添加清晰的 section 边界。
     */
    private String render(SystemPromptItem item) {
        // 标签只用于组织 Prompt，不引入 XML 解析。
        return "<" + item.id() + ">\n"
                + item.content()
                + "\n</" + item.id() + ">";
    }
}
