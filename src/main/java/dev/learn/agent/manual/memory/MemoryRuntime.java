package dev.learn.agent.manual.memory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一管理一次应用会话中的记忆能力，并消费 SessionState 中的开关状态。
 *
 * 记忆 Provider、按需召回、回合结束提取和整理都通过这里判断是否启用，
 * 避免应用入口在多个阶段重复维护同一个开关。
 */
public final class MemoryRuntime implements AutoCloseable {

    // 当前 CLI Session 的记忆开关由 SessionState 唯一持有。
    private final SessionState sessionState;

    // 记忆能力使用的持久化仓库和模型服务。
    private final MemoryRepository repository;
    private final MemoryRecallService recallService;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;
    private final MemoryWorkStore workStore;
    private final MemoryBackgroundProcessor backgroundProcessor;

    /**
     * 创建记忆运行时。
     *
     * @param sessionState 当前 CLI Session 状态
     * @param client 应用共享的模型客户端
     * @param model 记忆相关模型调用使用的模型名称
     * @param paths 当前工作区路径边界
     */
    public MemoryRuntime(
            SessionState sessionState,
            AnthropicClient client,
            String model,
            WorkspacePathResolver paths
    ) {
        this.sessionState = Objects.requireNonNull(
                sessionState,
                "SessionState 不能为空"
        );

        this.repository =
                new MemoryRepository(
                        paths
                );

        MemorySelector selector =
                new MemorySelector(
                        client,
                        model
                );

        this.recallService =
                new MemoryRecallService(
                        repository,
                        selector
                );

        this.extractor =
                new MemoryExtractor(
                        client,
                        model
                );

        this.consolidator =
                new MemoryConsolidator(
                        client,
                        model,
                        repository
                );

        try {
            this.workStore = new MemoryWorkStore(sessionState.agentHome());
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化记忆后台工作库", exception);
        }
        this.backgroundProcessor = new MemoryBackgroundProcessor(workStore, extractor, repository,
                consolidator, sessionState::memoryEnabled);
        backgroundProcessor.start();
    }

    /**
     * 读取供 System Prompt 使用的 MEMORY.md 索引。
     *
     * @return 原始索引文本；记忆关闭或没有索引时为空
     */
    public Optional<String> loadIndex() {
        if (!sessionState.memoryEnabled()) {
            return Optional.empty();
        }

        try {
            String index =
                    repository.readIndex();

            if (index.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(index);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法读取 .memory/MEMORY.md",
                    exception
            );
        }
    }

    /**
     * 召回与当前用户请求相关的记忆正文。
     *
     * @param query 当前用户请求
     * @return 临时注入本轮模型请求的记忆上下文
     * @throws IOException 记忆目录读取失败
     */
    public String recall(
            String query
    ) throws IOException {
        Objects.requireNonNull(
                query,
                "query 不能为 null"
        );

        if (!sessionState.memoryEnabled()) {
            return "";
        }

        return recallService.recall(
                query
        );
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
        backgroundProcessor.close();
    }
}
