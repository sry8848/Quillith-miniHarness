package dev.learn.agent.manual.telemetry;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/** 集中写入当前 GenAI Span 的低基数属性。 */
public final class GenAiSpanAttributes {

    private static final String REQUEST_MODEL =
            "gen_ai.request.model";
    private static final String RESPONSE_MODEL =
            "gen_ai.response.model";
    private static final String INPUT_TOKENS =
            "gen_ai.usage.input_tokens";
    private static final String OUTPUT_TOKENS =
            "gen_ai.usage.output_tokens";
    private static final String CACHE_READ_INPUT_TOKENS =
            "gen_ai.usage.cache_read.input_tokens";
    private static final String CACHE_CREATION_INPUT_TOKENS =
            "gen_ai.usage.cache_creation.input_tokens";
    private static final String FINISH_REASONS =
            "gen_ai.response.finish_reasons";
    private static final String RESPONSE_ID =
            "gen_ai.response.id";
    private static final String TOOL_CALL_ID =
            "gen_ai.tool.call.id";

    private GenAiSpanAttributes() {
    }

    /** 记录 message_start 的模型和输入用量。 */
    public static void recordMessageStart(String requestedModel, Message message) {
        Span span = Span.current();
        span.setAttribute(REQUEST_MODEL, requestedModel);
        span.setAttribute(RESPONSE_MODEL, message.model().asString());
        recordUsage(span, message.usage());
    }

    /** 记录 message_delta 的输出用量和停止原因。 */
    public static void recordMessageDelta(MessageDeltaUsage usage, String finishReason) {
        Span span = Span.current();
        span.setAttribute(OUTPUT_TOKENS, usage.outputTokens());
        usage.inputTokens().ifPresent(value -> span.setAttribute(INPUT_TOKENS, value));
        usage.cacheReadInputTokens().ifPresent(value -> span.setAttribute(CACHE_READ_INPUT_TOKENS, value));
        usage.cacheCreationInputTokens().ifPresent(value -> span.setAttribute(CACHE_CREATION_INPUT_TOKENS, value));
        if (finishReason != null) {
            span.setAttribute(FINISH_REASONS, finishReason);
        }
    }

    /** 记录 Provider HTTP 响应关联 ID。 */
    public static void recordResponseId(String responseId) {
        Span.current().setAttribute(RESPONSE_ID, responseId);
    }

    /** 记录 Tool Span 与 Provider Tool Call 的因果关联。 */
    public static void recordToolCall(ToolUseBlock toolUse) {
        Span.current().setAttribute(TOOL_CALL_ID, toolUse.id());
    }

    /** 标记已被转换为稳定返回值的 LLM 流失败。 */
    public static void markLlmFailure() {
        Span.current().setStatus(StatusCode.ERROR);
    }

    private static void recordUsage(Span span, Usage usage) {
        span.setAttribute(INPUT_TOKENS, usage.inputTokens());
        span.setAttribute(OUTPUT_TOKENS, usage.outputTokens());
        usage.cacheReadInputTokens().ifPresent(value -> span.setAttribute(CACHE_READ_INPUT_TOKENS, value));
        usage.cacheCreationInputTokens().ifPresent(value -> span.setAttribute(CACHE_CREATION_INPUT_TOKENS, value));
    }
}
