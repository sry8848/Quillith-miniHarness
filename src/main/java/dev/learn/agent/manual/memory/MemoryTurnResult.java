package dev.learn.agent.manual.memory;

/**
 * 记录一个用户回合结束后的记忆处理结果。
 *
 * @param savedCount 本回合保存的记忆数量
 * @param consolidatedCount 整理后保留的记忆数量
 */
public record MemoryTurnResult(
        int savedCount,
        int consolidatedCount
) {

    // 记忆关闭或本回合没有新记忆时的统一结果。
    public static final MemoryTurnResult NONE =
            new MemoryTurnResult(
                    0,
                    0
            );
}
