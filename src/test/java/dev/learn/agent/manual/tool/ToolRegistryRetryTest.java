package dev.learn.agent.manual.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Harness 根据 Tool 异常语义执行统一重试。
 */
class ToolRegistryRetryTest {

    /** 可重试异常后成功时，Harness 应重试一次并返回成功。 */
    @Test
    void retriesRetryableFailureThenReturnsSuccess() {
        AtomicInteger executionCount =
                new AtomicInteger();
        ToolRegistry registry =
                retryRegistry();
        registry.register(
                scriptedTool(
                        executionCount,
                        attempt -> {
                            if (attempt == 1) {
                                throw new RetryableToolException(
                                        "temporary timeout"
                                );
                            }
                            return ToolExecutionResult.success(
                                    "read complete"
                            );
                        }
                )
        );

        ToolExecutionResult executionResult =
                registry.execute(
                        sampleCall()
                );

        assertEquals(
                2,
                executionCount.get()
        );
        assertFalse(
                executionResult.error()
        );
        assertEquals(
                "read complete",
                executionResult.content()
        );
    }

    /** 三种不同可重试异常仍必须共享同一个三次总尝试预算。 */
    @Test
    void stopsAfterThreeDifferentRetryableFailures() {
        AtomicInteger executionCount =
                new AtomicInteger();
        ToolRegistry registry =
                retryRegistry();
        registry.register(
                scriptedTool(
                        executionCount,
                        attempt -> {
                            throw new RetryableToolException(
                                    switch (attempt) {
                                        case 1 -> "timeout";
                                        case 2 -> "file busy";
                                        default -> "temporary IO failure";
                                    }
                            );
                        }
                )
        );

        ToolExecutionResult executionResult =
                registry.execute(
                        sampleCall()
                );

        assertEquals(
                3,
                executionCount.get()
        );
        assertTrue(
                executionResult.error()
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "exception_type: RetryableToolException"
                        )
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "attempts: 3"
                        )
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "retry_exhausted: true"
                        )
        );
    }

    /** 不可重试异常不能消耗额外 attempts。 */
    @Test
    void stopsImmediatelyAfterNonRetryableFailure() {
        AtomicInteger executionCount =
                new AtomicInteger();
        ToolRegistry registry =
                retryRegistry();
        registry.register(
                scriptedTool(
                        executionCount,
                        attempt -> {
                            throw new NonRetryableToolException(
                                    "invalid path"
                            );
                        }
                )
        );

        ToolExecutionResult executionResult =
                registry.execute(
                        sampleCall()
                );

        assertEquals(
                1,
                executionCount.get()
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "attempts: 1"
                        )
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "retry_exhausted: false"
                        )
        );
    }

    /** 可重试失败后遇到不可重试失败时，Harness 不能继续第三次执行。 */
    @Test
    void stopsWhenSecondAttemptBecomesNonRetryable() {
        AtomicInteger executionCount =
                new AtomicInteger();
        ToolRegistry registry =
                retryRegistry();
        registry.register(
                scriptedTool(
                        executionCount,
                        attempt -> {
                            if (attempt == 1) {
                                throw new RetryableToolException(
                                        "temporary timeout"
                                );
                            }
                            throw new NonRetryableToolException(
                                    "permission denied"
                            );
                        }
                )
        );

        ToolExecutionResult executionResult =
                registry.execute(
                        sampleCall()
                );

        assertEquals(
                2,
                executionCount.get()
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "exception_type: NonRetryableToolException"
                        )
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "attempts: 2"
                        )
        );
    }

    /** 未分类 RuntimeException 必须默认按不可重试处理。 */
    @Test
    void stopsImmediatelyAfterUnknownRuntimeFailure() {
        AtomicInteger executionCount =
                new AtomicInteger();
        ToolRegistry registry =
                retryRegistry();
        registry.register(
                scriptedTool(
                        executionCount,
                        attempt -> {
                            throw new IllegalStateException(
                                    "unexpected state"
                            );
                        }
                )
        );

        ToolExecutionResult executionResult =
                registry.execute(
                        sampleCall()
                );

        assertEquals(
                1,
                executionCount.get()
        );
        assertTrue(
                executionResult.content()
                        .contains(
                                "exception_type: IllegalStateException"
                        )
        );
    }

    /** 退避必须在 jitter 为零时按指数增长并受最大值限制。 */
    @Test
    void calculatesBoundedExponentialBackoff() {
        ToolRegistry registry =
                new ToolRegistry(
                        new ToolRetryPolicy(
                                3,
                                Duration.ofMillis(500),
                                Duration.ofSeconds(1),
                                0.0d
                        )
                );

        assertEquals(
                Duration.ofMillis(500),
                registry.retryDelayAfter(1)
        );
        assertEquals(
                Duration.ofSeconds(1),
                registry.retryDelayAfter(2)
        );
        assertEquals(
                Duration.ofSeconds(1),
                registry.retryDelayAfter(3)
        );
    }

    /** 创建不等待真实时间的固定重试策略。 */
    private static ToolRegistry retryRegistry() {
        return new ToolRegistry(
                new ToolRetryPolicy(
                        3,
                        Duration.ZERO,
                        Duration.ZERO,
                        0.0d
                )
        );
    }

    /** 创建供注册表执行的固定 Tool Call。 */
    private static ToolCall sampleCall() {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();
        return new ToolCall(
                "tool-use-1",
                "sample",
                input
        );
    }

    /** 创建按当前 attempts 返回预设结果的样例 Tool。 */
    private static AgentTool scriptedTool(
            AtomicInteger executionCount,
            IntFunction<ToolExecutionResult> behavior
    ) {
        return new AgentTool() {
            @Override
            public com.anthropic.models.messages.Tool definition() {
                return ToolDefinitionFactory.create(
                        "sample",
                        "Test tool.",
                        Map.of(),
                        List.of()
                );
            }

            @Override
            public ToolExecutionResult execute(
                    com.fasterxml.jackson.databind.JsonNode input
            ) {
                return behavior.apply(
                        executionCount.incrementAndGet()
                );
            }
        };
    }
}
