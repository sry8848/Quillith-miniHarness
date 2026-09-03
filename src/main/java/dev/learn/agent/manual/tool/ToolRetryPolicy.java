package dev.learn.agent.manual.tool;

import java.time.Duration;
import java.util.Objects;

/**
 * 定义 Harness 对一次 Tool Call 使用的统一重试预算和等待参数。
 *
 * <p>该策略不判断具体异常是否可重试；可重试性仍由 Tool 抛出的异常类型决定。</p>
 *
 * @param maxAttempts 同一 Tool Call 的最大总执行次数
 * @param initialDelay 第一次可重试失败后的基础等待时间
 * @param maxDelay 单次等待时间上限
 * @param jitterRatio 相对基础等待时间的随机抖动比例
 */
public record ToolRetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double jitterRatio
) {

    private static final int DEFAULT_MAX_ATTEMPTS =
            3;
    private static final Duration DEFAULT_INITIAL_DELAY =
            Duration.ofMillis(500);
    private static final Duration DEFAULT_MAX_DELAY =
            Duration.ofSeconds(1);
    private static final double DEFAULT_JITTER_RATIO =
            0.20d;

    /**
     * 校验重试策略的数值边界。
     */
    public ToolRetryPolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "maxAttempts 必须大于 0"
            );
        }

        Objects.requireNonNull(
                initialDelay,
                "initialDelay 不能为空"
        );
        Objects.requireNonNull(
                maxDelay,
                "maxDelay 不能为空"
        );

        if (initialDelay.isNegative()
                || maxDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "重试等待时间不能为负数"
            );
        }

        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException(
                    "initialDelay 不能大于 maxDelay"
            );
        }

        if (jitterRatio < 0.0d
                || jitterRatio > 1.0d) {
            throw new IllegalArgumentException(
                    "jitterRatio 必须位于 0 到 1 之间"
            );
        }
    }

    /**
     * 返回当前应用装配使用的默认重试策略。
     *
     * @return 首版固定的三次总尝试、五百毫秒起始、一秒上限策略
     */
    public static ToolRetryPolicy defaults() {
        return new ToolRetryPolicy(
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_INITIAL_DELAY,
                DEFAULT_MAX_DELAY,
                DEFAULT_JITTER_RATIO
        );
    }
}
