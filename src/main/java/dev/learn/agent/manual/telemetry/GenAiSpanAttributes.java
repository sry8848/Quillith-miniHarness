package dev.learn.agent.manual.telemetry;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.util.List;

/** 集中写入当前 GenAI Span 的标准属性和少量 Quillith 诊断属性。 */
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
    private static final String OPERATION_NAME =
            "gen_ai.operation.name";
    private static final String AGENT_NAME =
            "gen_ai.agent.name";
    private static final String CONVERSATION_ID =
            "gen_ai.conversation.id";
    private static final String MEMORY_STORE_ID =
            "gen_ai.memory.store.id";
    private static final String MEMORY_RECORD_COUNT =
            "gen_ai.memory.record.count";
    private static final String MEMORY_RECORDS =
            "gen_ai.memory.records";
    private static final String MEMORY_INPUT_TRUNCATED =
            "quillith.memory.input.truncated";
    private static final String MEMORY_STORE =
            ".memory/llmwiki";

    private GenAiSpanAttributes() {
    }

    /** 记录 message_start 的模型和输入用量。 */
    public static void recordMessageStart(String requestedModel, Message message) {
        Span span = Span.current();
        span.setAttribute(REQUEST_MODEL, requestedModel);
        span.setAttribute(RESPONSE_MODEL, message.model().asString());
        recordUsage(span, message.usage());
    }

    /**
     * 记录一次已经完整返回的非流式模型响应。
     *
     * @param requestedModel 请求使用的模型名称
     * @param message SDK 返回的完整模型响应
     */
    public static void recordCompletedMessage(String requestedModel, Message message) {
        // 1. 非流式响应直接提供完整模型信息和 Token 用量。
        recordMessageStart(requestedModel, message);
        recordResponseId(message.id());
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

    /**
     * 记录一次 Quillith Agent Turn 使用的标准 Agent 属性。
     *
     * @param conversationId 当前 Session 的稳定 ID
     */
    public static void recordAgentInvocation(String conversationId) {
        // 1. 使用标准操作名和会话 ID 表达完整 Agent Turn。
        Span span = Span.current();
        span.setAttribute(OPERATION_NAME, "invoke_agent");
        span.setAttribute(AGENT_NAME, "quillith");
        span.setAttribute(CONVERSATION_ID, conversationId);
    }

    /**
     * 记录 Memory 提取操作及其是否截断了原始输入。
     *
     * @param inputTruncated 提取输入是否超过字符预算并被截断
     */
    public static void recordMemoryCreationStart(boolean inputTruncated) {
        // 1. 提取器负责从对话创建新的长期记忆候选。
        Span span = Span.current();
        span.setAttribute(OPERATION_NAME, "create_memory");
        span.setAttribute(MEMORY_STORE_ID, MEMORY_STORE);

        // 是否截断是 Quillith 提取策略的内部事实，标准 Memory 字段不表达该决策。
        span.setAttribute(MEMORY_INPUT_TRUNCATED, inputTruncated);
    }

    /** 将已经确定执行的 Memory 整理标记为标准 upsert 操作。 */
    public static void recordMemoryOrganizationStart() {
        // 1. 整理可能创建或更新记录，使用标准 upsert_memory 操作。
        Span span = Span.current();
        span.setAttribute(OPERATION_NAME, "upsert_memory");
        span.setAttribute(MEMORY_STORE_ID, MEMORY_STORE);
    }

    /**
     * 记录 Memory 创建操作最终产生的记录。
     *
     * @param recordIds 本次操作尝试创建或更新的稳定记忆 ID
     */
    public static void recordMemoryRecords(List<String> recordIds) {
        // 1. 输出记录统一使用标准 Memory 数量和记录集合字段。
        recordMemoryRecords(Span.current(), recordIds);
    }

    private static void recordUsage(Span span, Usage usage) {
        span.setAttribute(INPUT_TOKENS, usage.inputTokens());
        span.setAttribute(OUTPUT_TOKENS, usage.outputTokens());
        usage.cacheReadInputTokens().ifPresent(value -> span.setAttribute(CACHE_READ_INPUT_TOKENS, value));
        usage.cacheCreationInputTokens().ifPresent(value -> span.setAttribute(CACHE_CREATION_INPUT_TOKENS, value));
    }

    /**
     * 把稳定 Memory ID 编码为标准 gen_ai.memory.records JSON。
     *
     * @param span 接收 Memory 属性的当前业务 Span
     * @param recordIds 本次 Memory 操作涉及的稳定记录 ID
     */
    private static void recordMemoryRecords(
            Span span,
            List<String> recordIds
    ) {
        // 1. 只记录可与 .memory 文件关联的 ID，不把记忆正文写入 Trace。
        ArrayNode records = JsonNodeFactory.instance.arrayNode();
        for (String recordId : recordIds) {
            records.addObject()
                    .put("id", recordId);
        }

        // 评测已明确选择记录 Memory ID；正文仍不进入 Trace，控制证据敏感度。
        // Java Span 属性不支持结构化对象，按标准允许的 JSON 字符串形式保存。
        span.setAttribute(MEMORY_RECORD_COUNT, recordIds.size());
        span.setAttribute(MEMORY_RECORDS, records.toString());
    }
}
