package dev.learn.agent.manual.tool.tools;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.tool.*;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** 按 seq 闭区间读取同一工作区中的 canonical Session 消息。 */
public final class GetSessionMessagesTool implements AgentTool {
    private static final Tool DEFINITION = ToolDefinitionFactory.create(
            "get_session_messages",
            "Read canonical session messages by inclusive, zero-based seq range. "
                    + "Use session_id and [消息 X-Y] from a compaction summary. "
                    + "Returns seq, turn_seq and full protocol messages in ascending order.",
            Map.of("session_id", ToolDefinitionFactory.stringProperty("Persistent session ID."),
                    "from_seq", ToolDefinitionFactory.nonNegativeIntegerProperty("Inclusive first seq."),
                    "to_seq", ToolDefinitionFactory.nonNegativeIntegerProperty("Inclusive last seq.")),
            List.of("session_id", "from_seq", "to_seq"));
    private final SessionStore store;
    private final SessionState state;

    /** 使用父 Runtime 的 Store 和当前工作区状态。 */
    public GetSessionMessagesTool(SessionStore store, SessionState state) {
        this.store = store;
        this.state = state;
    }

    /** @return 模型可调用的只读工具定义 */
    @Override public Tool definition() { return DEFINITION; }

    /** @return 查询不修改 Session，允许并发读取 */
    @Override public boolean isConcurrencySafe() { return true; }

    /** 校验模型输入，返回包含序号和完整消息协议的 JSON。 */
    @Override public ToolExecutionResult execute(JsonNode input) {
        // 1. 模型参数属于外部输入，避免 asLong 把缺失或小数静默转换为 0。
        JsonNode session = input.get("session_id");
        if (session == null || !session.isTextual() || session.asText().isBlank()) {
            throw new NonRetryableToolException("session_id must be a non-empty string");
        }
        long from = sequence(input, "from_seq");
        long to = sequence(input, "to_seq");

        // 2. Store 统一校验范围和工作区；JSON 使用 SDK 映射规则保留协议字段。
        var messages = store.getSessionMessages(session.asText(), state.workspace().toString(), from, to);
        try {
            // SDK 映射器不采用普通 record 的 JsonProperty 重命名，外层字段显式命名。
            var records = messages.stream().map(row -> Map.of(
                    "seq", row.seq(), "turn_seq", row.turnSeq(), "message", row.message())).toList();
            return ToolExecutionResult.success(ObjectMappers.jsonMapper().writeValueAsString(records));
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException("无法序列化 Session 查询结果", exception);
        }
    }

    /** 读取一个可表示为 long 的非负整数参数。 */
    private static long sequence(JsonNode input, String name) {
        JsonNode value = input.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new NonRetryableToolException(name + " must be a non-negative integer");
        }
        return value.longValue();
    }
}
