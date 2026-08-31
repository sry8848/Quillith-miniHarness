package dev.learn.agent.manual.memory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一管理一次应用会话中的记忆能力和开关状态。
 *
 * 记忆 Provider、按需召回、回合结束提取和整理都通过这里判断是否启用，
 * 避免应用入口在多个阶段重复维护同一个开关。
 */
public final class MemoryRuntime {

    // 当前交互会话中的记忆开关状态；主循环只在单线程修改它。
    private boolean enabled;

    // 记忆能力使用的持久化仓库和模型服务。
    private final MemoryRepository repository;
    private final MemoryRecallService recallService;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;

    /**
     * 创建记忆运行时。
     *
     * @param enabled 初始是否启用记忆
     * @param client 应用共享的模型客户端
     * @param model 记忆相关模型调用使用的模型名称
     * @param paths 当前工作区路径边界
     */
    public MemoryRuntime(
            boolean enabled,
            AnthropicClient client,
            String model,
            WorkspacePathResolver paths
    ) {
        this.enabled = enabled;

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
    }

    /**
     * 返回当前记忆开关状态。
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 修改当前记忆开关状态。
     */
    public void setEnabled(
            boolean enabled
    ) {
        this.enabled = enabled;
    }

    /**
     * 读取供 System Prompt 使用的 MEMORY.md 索引。
     *
     * @return 原始索引文本；记忆关闭或没有索引时为空
     */
    public Optional<String> loadIndex() {
        if (!enabled) {
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

        if (!enabled) {
            return "";
        }

        return recallService.recall(
                query
        );
    }

    /**
     * 保存供回合结束提取使用的原始历史快照。
     *
     * @param history 当前 Agent 历史
     * @return 记忆开启时的不可变快照；关闭时为空列表
     */
    public List<MessageParam> capture(
            List<MessageParam> history
    ) {
        Objects.requireNonNull(
                history,
                "history 不能为 null"
        );

        if (!enabled) {
            return List.of();
        }

        return List.copyOf(
                history
        );
    }

    /**
     * 在用户回合完成后执行提取、保存和必要的整理。
     *
     * @param snapshot 回合开始前保存的历史快照
     * @return 本回合记忆处理结果
     * @throws IOException 记忆文件读写失败
     */
    public MemoryTurnResult completeTurn(
            List<MessageParam> snapshot
    ) throws IOException {
        Objects.requireNonNull(
                snapshot,
                "记忆快照不能为 null"
        );

        if (!enabled) {
            return MemoryTurnResult.NONE;
        }

        String dialogue =
                MemoryDialogueFormatter.format(
                        snapshot
                );

        List<MemoryEntry> extracted =
                extractor.extract(
                        dialogue,
                        repository.list()
                );

        for (MemoryEntry entry : extracted) {
            repository.save(
                    entry
            );
        }

        if (extracted.isEmpty()) {
            return MemoryTurnResult.NONE;
        }

        List<MemoryEntry> consolidated =
                consolidator.consolidateIfNeeded();

        return new MemoryTurnResult(
                extracted.size(),
                consolidated.size()
        );
    }
}
