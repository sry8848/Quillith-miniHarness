// 声明 Content Rejection 恢复处理器所在的包。
package dev.learn.agent.manual.recovery;

// 引入 SDK 错误体、消息块和 JSON 解析类型。
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 恢复 Provider 在模型请求输入阶段拒绝 Tool Result 的情况。
 *
 * <p>本 Handler 只把 Body 根字段 {@code code=DataInspectionFailed}
 * 视为自己的错误。恢复时要求最近一批 Tool Result 的 ID 全部位于同一条
 * user 消息中，然后一次性替换整批结果正文；assistant 的 tool_use 和工具
 * 执行事实不会被删除。</p>
 */
public final class ContentRejectionRecoveryHandler
        implements ModelRequestRecoveryHandler {

    // 限制同一条连续请求恢复链最多发起两次恢复请求。
    private static final int MAX_RECOVERIES =
            2;

    // 使用固定说明替换可能无法安全交付的原始工具结果。
    private static final String TOOL_RESULT_RECOVERY_MESSAGE =
            "The tool completed, but the provider rejected its result "
                    + "before it reached the model. Do not repeat the same "
                    + "broad request. Narrow the query, reduce the result "
                    + "scope, or choose another tool.";

    /**
     * 判断 Provider Body 根 code 是否为明确的内容检查失败。
     *
     * @param exception 当前模型请求异常
     * @return 根 code 精确为 DataInspectionFailed 时返回 true
     */
    @Override
    public boolean supports(
            AnthropicServiceException exception
    ) {
        // 该错误必须有可解析的 JSON Body，避免把同名消息或其他字段误判为可恢复。
        JsonNode body =
                bodyNode(
                        Objects.requireNonNull(
                                exception,
                                "exception 不能为空"
                        ).body()
                );
        return body != null
                && "DataInspectionFailed".equals(
                textField(
                        body,
                        "code"
                )
        );
    }

    /**
     * 替换最近一批完整 Tool Result，并记录一次恢复。
     *
     * @param exception 当前模型请求异常
     * @param state 当前连续错误恢复链状态
     * @return 已完成安全替换且可以重试时返回 true
     */
    @Override
    public boolean tryRecover(
            AnthropicServiceException exception,
            ModelRequestRecoveryState state
    ) {
        // 恢复规则只在支持该错误且两个参数有效时继续执行。
        Objects.requireNonNull(
                exception,
                "exception 不能为空"
        );
        Objects.requireNonNull(
                state,
                "state 不能为空"
        );

        // 次数耗尽时保持历史原样，直接让 AgentLoop 抛出原异常。
        if (state.contentRejectionRecoveryCount()
                >= MAX_RECOVERIES) {
            return false;
        }

        // 没有最近待确认结果时无法证明哪个输入需要替换，不能盲目重试。
        Set<String> pendingIds =
                state.pendingToolUseIds();
        if (pendingIds.isEmpty()) {
            return false;
        }

        // 只有整批结果都能在同一条 user 消息中定位时才一次性提交修改。
        if (!replacePendingToolResults(
                state.messages(),
                pendingIds
        )) {
            return false;
        }

        // 消息替换完成后才消耗恢复次数，失败不会污染下一次诊断。
        state.incrementContentRejectionRecoveryCount();
        return true;
    }

    /**
     * 在最近的 user 消息中原子替换整批 Tool Result。
     */
    private static boolean replacePendingToolResults(
            List<MessageParam> messages,
            Set<String> expectedIds
    ) {
        // 从历史尾部向前寻找最近一条携带 block 参数的 user 消息。
        for (int messageIndex = messages.size() - 1;
             messageIndex >= 0;
             messageIndex--) {
            MessageParam message =
                    messages.get(
                            messageIndex
                    );
            if (!MessageParam.Role.USER.equals(
                    message.role()
            ) || !message.content().isBlockParams()) {
                continue;
            }

            // 先在副本中完成全部替换，确保批次不完整时不会写入半成品。
            List<ContentBlockParam> originalBlocks =
                    message.content()
                            .asBlockParams();
            List<ContentBlockParam> updatedBlocks =
                    new ArrayList<>(
                            originalBlocks
                    );
            int replacedCount =
                    0;

            // 只替换目标 ID，保留其他 block 的顺序和内容。
            for (int blockIndex = 0;
                 blockIndex < originalBlocks.size();
                 blockIndex++) {
                ContentBlockParam block =
                        originalBlocks.get(
                                blockIndex
                        );
                if (!block.isToolResult()
                        || !expectedIds.contains(
                        block.asToolResult()
                                .toolUseId()
                )) {
                    continue;
                }

                // 保留 tool_use_id，只替换模型可见正文并标记为错误结果。
                ToolResultBlockParam updatedResult =
                        block.asToolResult()
                                .toBuilder()
                                .content(
                                        TOOL_RESULT_RECOVERY_MESSAGE
                                )
                                .isError(
                                        true
                                )
                                .build();
                updatedBlocks.set(
                        blockIndex,
                        ContentBlockParam.ofToolResult(
                                updatedResult
                        )
                );
                replacedCount++;
            }

            // 本批每个 ID 都命中后才替换整条消息，避免模型收到半批工具协议。
            if (replacedCount
                    == expectedIds.size()) {
                messages.set(
                        messageIndex,
                        message.toBuilder()
                                .contentOfBlockParams(
                                        updatedBlocks
                                )
                                .build()
                );
                return true;
            }
        }

        return false;
    }

    /**
     * 把 SDK 错误体转换为 JSON 节点。
     */
    private static JsonNode bodyNode(
            JsonValue body
    ) {
        if (body == null
                || body.isMissing()
                || body.isNull()) {
            return null;
        }

        try {
            return body.convert(
                    JsonNode.class
            );
        } catch (RuntimeException ignored) {
            // 非 JSON Body 不满足本 Handler 的明确错误契约。
            return null;
        }
    }

    /**
     * 读取 JSON 对象中的文本字段。
     */
    private static String textField(
            JsonNode object,
            String fieldName
    ) {
        if (object == null
                || !object.isObject()) {
            return "";
        }

        JsonNode field =
                object.get(
                        fieldName
                );
        return field == null
                || !field.isTextual()
                ? ""
                : field.textValue();
    }
}
