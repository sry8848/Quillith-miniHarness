// 声明模型请求恢复分派测试所属的包。
package dev.learn.agent.manual.recovery;

// 引入 SDK 异常和 JUnit 测试类型。
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证模型请求恢复 Manager 的顺序分派契约。
 */
class ModelRequestRecoveryManagerTest {

    /**
     * Given 多个 Handler 按优先级注册，When 第一个匹配 Handler 返回 true，Then 只执行它。
     */
    @Test
    void shouldUseFirstMatchingHandlerInRegistrationOrder() {
        // 记录每个 Handler 是否真正收到本次异常。
        List<String> calls =
                new ArrayList<>();
        ModelRequestRecoveryHandler first =
                handler(
                        "first",
                        calls,
                        true,
                        true
                );
        ModelRequestRecoveryHandler second =
                handler(
                        "second",
                        calls,
                        true,
                        true
                );

        ModelRequestRecoveryManager manager =
                new ModelRequestRecoveryManager(
                        List.of(
                                first,
                                second
                        )
                );

        // 第一个匹配 Handler 应独占本次恢复决定。
        assertTrue(
                manager.tryRecover(
                        exception(),
                        new ModelRequestRecoveryState(
                                new ArrayList<>()
                        )
                )
        );
        assertEquals(
                List.of(
                        "first"
                ),
                calls
        );
    }

    /**
     * Given 第一个匹配 Handler 无法恢复，When Manager 完成分派，Then 不继续尝试后续 Handler。
     */
    @Test
    void shouldStopAfterFirstMatchingHandlerReturnsFalse() {
        // 记录分派停止位置。
        List<String> calls =
                new ArrayList<>();
        ModelRequestRecoveryManager manager =
                new ModelRequestRecoveryManager(
                        List.of(
                                handler(
                                        "first",
                                        calls,
                                        true,
                                        false
                                ),
                                handler(
                                        "second",
                                        calls,
                                        true,
                                        true
                                )
                        )
                );

        // 第一个匹配 Handler 的 false 直接表示最终不可恢复。
        assertFalse(
                manager.tryRecover(
                        exception(),
                        new ModelRequestRecoveryState(
                                new ArrayList<>()
                        )
                )
        );
        assertEquals(
                List.of(
                        "first"
                ),
                calls
        );
    }

    /**
     * Given 没有 Handler 支持异常，When Manager 尝试恢复，Then 返回 false 交由调用方抛出原异常。
     */
    @Test
    void shouldReturnFalseWhenNoHandlerSupportsException() {
        // 注册一个明确不匹配的 Handler。
        ModelRequestRecoveryManager manager =
                new ModelRequestRecoveryManager(
                        List.of(
                                handler(
                                        "unsupported",
                                        new ArrayList<>(),
                                        false,
                                        true
                                )
                        )
                );

        // 未知异常不进入任何恢复策略。
        assertFalse(
                manager.tryRecover(
                        exception(),
                        new ModelRequestRecoveryState(
                                new ArrayList<>()
                        )
                )
        );
    }

    /**
     * 创建测试用 Handler，避免测试把 Manager 的分派契约和具体恢复策略混在一起。
     */
    private static ModelRequestRecoveryHandler handler(
            String name,
            List<String> calls,
            boolean supports,
            boolean recovered
    ) {
        return new ModelRequestRecoveryHandler() {
            @Override
            public boolean supports(
                    AnthropicServiceException exception
            ) {
                return supports;
            }

            @Override
            public boolean tryRecover(
                    AnthropicServiceException exception,
                    ModelRequestRecoveryState state
            ) {
                calls.add(
                        name
                );
                return recovered;
            }
        };
    }

    /**
     * 创建带根 code 的 SDK 服务端异常。
     */
    private static AnthropicServiceException exception() {
        return BadRequestException.builder()
                .headers(
                        Headers.builder()
                                .build()
                )
                .body(
                        JsonValue.from(
                                Map.of(
                                        "code",
                                        "DataInspectionFailed"
                                )
                        )
                )
                .build();
    }
}
