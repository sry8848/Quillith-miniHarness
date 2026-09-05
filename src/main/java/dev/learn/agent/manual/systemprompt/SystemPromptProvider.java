package dev.learn.agent.manual.systemprompt;

import dev.learn.agent.manual.SessionState;

import java.util.Optional;

/**
 * 由能力模块实现，负责生成自己拥有的 System Prompt 内容。
 */
public interface SystemPromptProvider {

    /**
     * 返回 Provider 拥有的稳定 Item id。
     */
    String id();

    /**
     * 返回该内容需要刷新的生命周期。
     */
    RefreshScope scope();

    /**
     * 返回该 section 在完整 System Prompt 中的顺序。
     */
    int order();

    /**
     * 根据当前真实状态生成模型可见内容。
     *
     * @param sessionState 当前 Session 的真实状态
     * @return 当前内容；空值表示删除已有 Item
     */
    Optional<String> load(
            SessionState sessionState
    );
}
