// 声明 Content Rejection 恢复策略测试所属的包。
package dev.learn.agent.manual.recovery;

// 引入 SDK 消息、错误体和 JUnit 测试类型。
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Content Rejection Handler 的识别和消息原子替换契约。
 */
class ContentRejectionRecoveryHandlerTest {

    /**
     * Given 一批完整 pending Tool Result，When Provider 返回明确根 code，Then 整批结果被替换且事实保留。
     */
    @Test
    void shouldReplaceCompletePendingToolResultBatch() {
        // 构造真实 assistant tool_use 与 user tool_result 配对消息。
        List<MessageParam> messages =
                messagesWithToolResult(
                        "tool-1"
                );
        ModelRequestRecoveryState state =
                new ModelRequestRecoveryState(
                        messages
                );
        state.recordPendingToolResults(
                List.of(
                        "tool-1"
                )
        );
        ContentRejectionRecoveryHandler handler =
                new ContentRejectionRecoveryHandler();

        // 明确的输入拒绝应由 Handler 自己完成恢复修改。
        assertTrue(
                handler.tryRecover(
                        exception(
                                "DataInspectionFailed"
                        ),
                        state
                )
        );
        assertEquals(
                1,
                state.contentRejectionRecoveryCount()
        );

        // assistant 调用事实和 tool_use_id 不因替换结果而丢失。
        assertTrue(
                messages.get(0)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .isToolUse()
        );
        ToolResultBlockParam result =
                messages.get(1)
                        .content()
                        .asBlockParams()
                        .get(0)
                        .asToolResult();
        assertEquals(
                "tool-1",
                result.toolUseId()
        );
        assertTrue(
                result.isError()
                        .orElse(false)
        );
        assertTrue(
                result.content()
                        .orElseThrow()
                        .asString()
                        .contains(
                                "provider rejected"
                        )
        );
    }

    /**
     * Given 没有 pending 结果或批次不完整，When Handler 尝试恢复，Then 不修改历史也不增加次数。
     */
    @Test
    void shouldLeaveHistoryUnchangedWhenBatchCannotBeLocated() {
        // 没有 pending ID 时不能猜测用户输入的来源。
        List<MessageParam> messages =
                messagesWithToolResult(
                        "tool-1"
                );
        ModelRequestRecoveryState state =
                new ModelRequestRecoveryState(
                        messages
                );
        String before =
                messages.toString();
        ContentRejectionRecoveryHandler handler =
                new ContentRejectionRecoveryHandler();

        assertFalse(
                handler.tryRecover(
                        exception(
                                "DataInspectionFailed"
                        ),
                        state
                )
        );
        assertEquals(
                before,
                messages.toString()
        );
        assertEquals(
                0,
                state.contentRejectionRecoveryCount()
        );

        // 只匹配部分 ID 时整批操作仍保持原子性。
        state.recordPendingToolResults(
                List.of(
                        "tool-1",
                        "tool-2"
                )
        );
        assertFalse(
                handler.tryRecover(
                        exception(
                                "DataInspectionFailed"
                        ),
                        state
                )
        );
        assertEquals(
                before,
                messages.toString()
        );
    }

    /**
     * Given 错误 code 不符合根字段契约，When Handler 判断异常，Then 不支持该异常。
     */
    @Test
    void shouldOnlySupportExactRootDataInspectionCode() {
        // 其他字段位置或近似名称都不能触发请求级恢复。
        ContentRejectionRecoveryHandler handler =
                new ContentRejectionRecoveryHandler();

        assertTrue(
                handler.supports(
                        exception(
                                "DataInspectionFailed"
                        )
                )
        );
        assertFalse(
                handler.supports(
                        exception(
                                "data_inspection_failed"
                        )
                )
        );
        assertFalse(
                handler.supports(
                        BadRequestException.builder()
                                .headers(
                                        Headers.builder()
                                                .build()
                                )
                                .body(
                                        JsonValue.from(
                                                Map.of(
                                                        "error",
                                                        Map.of(
                                                                "type",
                                                                "DataInspectionFailed"
                                                        )
                                                )
                                        )
                                )
                                .build()
                )
        );
    }

    /**
     * Given 恢复次数已达到上限，When Handler 再次处理，Then 保持历史不变并返回 false。
     */
    @Test
    void shouldStopAfterRecoveryLimit() {
        // 预先记录两次已经完成的恢复，模拟同一连续错误链的耗尽状态。
        List<MessageParam> messages =
                messagesWithToolResult(
                        "tool-1"
                );
        ModelRequestRecoveryState state =
                new ModelRequestRecoveryState(
                        messages
                );
        state.recordPendingToolResults(
                List.of(
                        "tool-1"
                )
        );
        state.incrementContentRejectionRecoveryCount();
        state.incrementContentRejectionRecoveryCount();
        String before =
                messages.toString();

        assertFalse(
                new ContentRejectionRecoveryHandler()
                        .tryRecover(
                                exception(
                                        "DataInspectionFailed"
                                ),
                                state
                        )
        );
        assertEquals(
                before,
                messages.toString()
        );
    }

    /**
     * 构造包含 assistant tool_use 和 user tool_result 的消息历史。
     */
    private static List<MessageParam> messagesWithToolResult(
            String toolUseId
    ) {
        return new ArrayList<>(
                List.of(
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.ASSISTANT
                                )
                                .contentOfBlockParams(
                                        List.of(
                                                ContentBlockParam.ofToolUse(
                                                        ToolUseBlockParam.builder()
                                                                .id(
                                                                        toolUseId
                                                                )
                                                                .name(
                                                                        "test_tool"
                                                                )
                                                                .input(
                                                                        ToolUseBlockParam.Input.builder()
                                                                                .build()
                                                                )
                                                                .build()
                                                )
                                        )
                                )
                                .build(),
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.USER
                                )
                                .contentOfBlockParams(
                                        List.of(
                                                ContentBlockParam.ofToolResult(
                                                        ToolResultBlockParam.builder()
                                                                .toolUseId(
                                                                        toolUseId
                                                                )
                                                                .content(
                                                                        "sensitive original result"
                                                                )
                                                                .build()
                                                )
                                        )
                                )
                                .build()
                )
        );
    }

    /**
     * 创建带指定根 code 的服务端异常。
     */
    private static AnthropicServiceException exception(
            String code
    ) {
        return BadRequestException.builder()
                .headers(
                        Headers.builder()
                                .build()
                )
                .body(
                        JsonValue.from(
                                Map.of(
                                        "code",
                                        code
                                )
                        )
                )
                .build();
    }
}
