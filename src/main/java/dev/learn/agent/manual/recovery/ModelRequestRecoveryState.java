// 声明模型请求恢复状态所在的包。
package dev.learn.agent.manual.recovery;

// 引入模型消息和集合类型。
import com.anthropic.models.messages.MessageParam;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 保存一条连续模型请求错误恢复链所需的最小执行状态。
 *
 * <p>模型请求被 Provider 接受后，调用方应直接创建新的 State，
 * 不通过本类重置旧恢复链。</p>
 */
public final class ModelRequestRecoveryState {

    // 保存 AgentLoop 当前维护的真实消息历史，Handler 会直接在此列表上完成恢复修改。
    private final List<MessageParam> messages;

    // 保存当前连续 Content Rejection 恢复链已经使用的次数。
    private int contentRejectionRecoveryCount;

    // 保存最近一批尚未被后续模型请求接受的 Tool Result ID。
    private Set<String> pendingToolUseIds = Set.of();

    /**
     * 创建一条新的模型请求恢复链。
     *
     * @param messages AgentLoop 当前维护的可修改消息历史
     * @throws NullPointerException 消息历史为 null 时抛出
     */
    public ModelRequestRecoveryState(
            List<MessageParam> messages
    ) {
        // 保留调用方的可修改列表，保证 Handler 的恢复结果直接进入下一次请求。
        this.messages =
                Objects.requireNonNull(
                        messages,
                        "messages 不能为空"
                );
    }

    /**
     * 返回 AgentLoop 当前维护的真实消息历史。
     *
     * @return 可修改的消息历史
     */
    public List<MessageParam> messages() {
        return messages;
    }

    /**
     * 返回当前连续 Content Rejection 恢复次数。
     *
     * @return 已使用的恢复次数
     */
    public int contentRejectionRecoveryCount() {
        return contentRejectionRecoveryCount;
    }

    /**
     * 记录一次已经完成的 Content Rejection 上下文恢复。
     */
    public void incrementContentRejectionRecoveryCount() {
        // 只由实际完成消息替换的 Handler 调用，AgentLoop 不维护具体错误计数。
        contentRejectionRecoveryCount++;
    }

    /**
     * 返回最近一批待确认 Tool Result 的 ID。
     *
     * @return 不可修改的 Tool Use ID 集合
     */
    public Set<String> pendingToolUseIds() {
        return pendingToolUseIds;
    }

    /**
     * 记录一批等待后续模型请求确认的 Tool Result。
     *
     * @param toolUseIds 本批 Tool Result 对应的 Tool Use ID
     * @throws NullPointerException 参数或元素为 null 时抛出
     */
    public void recordPendingToolResults(
            Collection<String> toolUseIds
    ) {
        // 使用快照隔离调用方临时集合，避免后续清空局部列表改变恢复状态。
        this.pendingToolUseIds =
                Set.copyOf(
                        Objects.requireNonNull(
                                toolUseIds,
                                "toolUseIds 不能为空"
                        )
                );
    }
}
