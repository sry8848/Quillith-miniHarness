package dev.learn.agent.manual.session;

import com.anthropic.models.messages.MessageParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 保存完整会话历史和当前模型上下文两种内存视图。
 */
public final class ConversationState {

    private final List<MessageParam> messages = new ArrayList<>();
    private final List<MessageParam> modelContext = new ArrayList<>();
    private long checkpointThroughSeq = -1;

    /**
     * 追加一条已经正式提交的消息。
     *
     * @param message 已经持久化的协议消息
     */
    public void appendCommitted(
            MessageParam message
    ) {
        Objects.requireNonNull(message, "message 不能为空");

        // 1. 正式消息同时进入展示历史和下一次请求上下文。
        messages.add(message);
        modelContext.add(message);
    }

    /**
     * 追加一批已经正式提交的消息。
     *
     * @param committedMessages 已经持久化的协议消息
     */
    public void appendCommitted(
            List<MessageParam> committedMessages
    ) {
        Objects.requireNonNull(committedMessages, "committedMessages 不能为空");

        // 1. 保持批次中 assistant/tool_result 协议对的原始顺序。
        for (MessageParam message : committedMessages) {
            appendCommitted(message);
        }
    }

    /**
     * 用已经压缩的活动上下文替换模型视图。
     *
     * @param compactedContext 压缩后的模型上下文
     * @param throughSeq 该上下文已经吸收的完整历史最大序号
     */
    public void replaceModelContext(
            List<MessageParam> compactedContext,
            long throughSeq
    ) {
        Objects.requireNonNull(compactedContext, "compactedContext 不能为空");

        // 1. 先复制输入，调用方可能正传入当前 modelContext 本身。
        List<MessageParam> replacement = List.copyOf(compactedContext);

        // 2. 再整体替换，避免请求构建读取到半份压缩结果。
        modelContext.clear();
        modelContext.addAll(replacement);
        checkpointThroughSeq = throughSeq;
    }

    /**
     * 一次性恢复两份会话视图。
     *
     * @param restoredMessages 完整 committed history
     * @param restoredModelContext 当前活动上下文
     * @param throughSeq 恢复 Checkpoint 的覆盖序号；没有 Checkpoint 时为 -1
     */
    public void restore(
            List<MessageParam> restoredMessages,
            List<MessageParam> restoredModelContext,
            long throughSeq
    ) {
        Objects.requireNonNull(restoredMessages, "restoredMessages 不能为空");
        Objects.requireNonNull(restoredModelContext, "restoredModelContext 不能为空");

        // 1. 加载完成后统一替换，失败前调用方仍保有旧会话状态。
        messages.clear();
        messages.addAll(restoredMessages);
        modelContext.clear();
        modelContext.addAll(restoredModelContext);
        checkpointThroughSeq = throughSeq;
    }

    /**
     * 返回完整 committed history 的可修改内部列表。
     *
     * @return 当前完整消息列表
     */
    public List<MessageParam> messages() {
        return messages;
    }

    /**
     * 返回当前模型请求使用的可修改上下文。
     *
     * @return 当前活动上下文列表
     */
    public List<MessageParam> modelContext() {
        return modelContext;
    }

    /**
     * 返回当前 Checkpoint 已覆盖的完整历史序号。
     *
     * @return 没有 Checkpoint 时为 -1
     */
    public long checkpointThroughSeq() {
        return checkpointThroughSeq;
    }

    /**
     * 返回完整历史中最新消息的序号。
     *
     * @return 历史为空时为 -1
     */
    public long latestSequence() {
        return messages.size() - 1L;
    }
}
