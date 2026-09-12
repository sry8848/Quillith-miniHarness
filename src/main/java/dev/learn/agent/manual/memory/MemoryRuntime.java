package dev.learn.agent.manual.memory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一管理一次应用会话中的记忆能力，并消费 SessionState 中的开关状态。
 *
 * 根索引 Provider、回合结束提取和整理都通过这里判断是否启用，
 * 避免应用入口在多个阶段重复维护同一个开关。
 */
public final class MemoryRuntime implements AutoCloseable {

    // 当前 CLI Session 的记忆开关由 SessionState 唯一持有。
    private final SessionState sessionState;

    // 提取程序只通过薄 Store 写近期候选和读取根索引。
    private final LlmwikiStore llmwikiStore;
    private final MemoryExtractor extractor;
    private final MemoryOrganizer organizer;
    private final MemoryWorkStore workStore;
    private final MemoryBackgroundProcessor backgroundProcessor;

    /**
     * 创建记忆运行时。
     *
     * @param sessionState 当前 CLI Session 状态
     * @param client 应用共享的模型客户端
     * @param model 记忆相关模型调用使用的模型名称
     * @param paths 当前工作区路径边界
     * @param bashExecutable 当前运行环境已经解析的 Bash 可执行文件
     */
    public MemoryRuntime(
            SessionState sessionState,
            AnthropicClient client,
            String model,
            WorkspacePathResolver paths,
            Path bashExecutable
    ) {
        this.sessionState = Objects.requireNonNull(
                sessionState,
                "SessionState 不能为空"
        );

        this.extractor =
                new MemoryExtractor(
                        client,
                        model
                );

        try {
            // 1. 先建立规范文件根和整理 Agent，再启动可能立即恢复任务的后台线程。
            this.llmwikiStore = new LlmwikiStore(paths);
            this.organizer = new MemoryOrganizer(client, model, llmwikiStore,
                    bashExecutable, sessionState.agentHome());
            this.workStore = new MemoryWorkStore(sessionState.agentHome());
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化记忆运行时", exception);
        }
        this.backgroundProcessor = new MemoryBackgroundProcessor(workStore, extractor, llmwikiStore,
                organizer, sessionState::memoryEnabled);
        backgroundProcessor.start();
    }

    /**
     * 读取供 System Prompt 使用的 llmwiki 根索引。
     *
     * @return 原始索引文本；记忆关闭或没有索引时为空
     */
    public Optional<String> loadIndex() {
        if (!sessionState.memoryEnabled()) {
            return Optional.empty();
        }

        try {
            return llmwikiStore.readRootIndex();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法读取 .memory/llmwiki/index.md",
                    exception
            );
        }
    }

    /**
     * 持久化本轮记忆提取工作并唤醒后台线程。
     *
     * @param turnContext 当前 Turn 的已提交消息
     * @param modelContext 当前模型实际使用的压缩上下文
     */
    public void enqueue(List<MessageParam> turnContext, List<MessageParam> modelContext) {
        Objects.requireNonNull(turnContext, "turnContext 不能为 null");
        Objects.requireNonNull(modelContext, "modelContext 不能为 null");
        if (!sessionState.memoryEnabled()) {
            return;
        }

        // 1. 先 durable 再唤醒，进程退出也不会丢失已完成回答对应的提取工作。
        workStore.enqueue(new MemoryExtractionTask(UUID.randomUUID().toString(), turnContext, modelContext));
        backgroundProcessor.wake();
    }

    /** 更新记忆开关；重新启用时恢复队列消费。 */
    public void setEnabled(boolean enabled) {
        sessionState.setMemoryEnabled(enabled);
        if (enabled) {
            backgroundProcessor.wake();
        }
    }

    /**
     * 显式执行并等待一次正常后台批次。
     *
     * @return 批次结束后仍待处理的 extraction task 数量
     */
    public int runPendingAndWait() {
        return backgroundProcessor.runPendingAndWait();
    }

    /** 关闭后台执行器，不删除已持久化工作。 */
    @Override
    public void close() {
        // 1. 先阻止后台线程再进入整理循环，然后关闭整理 Agent 的进程资源。
        backgroundProcessor.close();
        organizer.close();
    }
}
