// 声明解析事件所在的包。
package dev.learn.agent.manual.output;

// 引入 Anthropic 工具调用类型和项目内部工具结果类型。
import com.anthropic.models.messages.ToolUseBlock;
import dev.learn.agent.manual.tool.ToolExecutionResult;

// 引入事件契约校验所需的 JDK 类型。
import java.util.Objects;

/**
 * AgentLoop 从原始模型事件和工具执行结果中提取出的打印事件。
 *
 * <p>文本类事件只使用 {@code text}，工具调用事件只使用 {@code toolUse}，
 * 工具结果事件同时携带 {@code toolUse} 和 {@code toolResult}。
 * 通过静态工厂方法构造事件，可以让调用方不必手工维护这些字段组合。</p>
 *
 * @param type 事件类型
 * @param text 文本或 thinking 增量，仅文本类事件使用
 * @param toolUse 完整工具调用，仅工具调用和工具结果事件使用
 * @param toolResult 工具执行结果，仅工具结果事件使用
 */
public record ParsedEvent(
        ParsedEventType type,
        String text,
        ToolUseBlock toolUse,
        ToolExecutionResult toolResult
) {

    /**
     * 校验不同事件类型所需要的载荷契约。
     */
    public ParsedEvent {
        Objects.requireNonNull(
                type,
                "解析事件类型不能为空"
        );

        switch (type) {
            case TEXT_DELTA, THINKING_DELTA -> Objects.requireNonNull(
                    text,
                    "文本类解析事件的 text 不能为空"
            );
            case TOOL_CALL_COMPLETED -> Objects.requireNonNull(
                    toolUse,
                    "工具调用解析事件的 toolUse 不能为空"
            );
            case TOOL_RESULT -> {
                Objects.requireNonNull(
                        toolUse,
                        "工具结果解析事件的 toolUse 不能为空"
                );
                Objects.requireNonNull(
                        toolResult,
                        "工具结果解析事件的 toolResult 不能为空"
                );
            }
            case MESSAGE_STOPPED -> {
                // 消息结束事件不携带额外载荷。
            }
        }
    }

    /**
     * 创建普通文本增量事件。
     *
     * @param text 本次文本增量
     * @return 普通文本事件
     */
    public static ParsedEvent textDelta(
            String text
    ) {
        return new ParsedEvent(
                ParsedEventType.TEXT_DELTA,
                text,
                null,
                null
        );
    }

    /**
     * 创建 thinking 增量事件。
     *
     * @param text 本次 thinking 增量
     * @return thinking 事件
     */
    public static ParsedEvent thinkingDelta(
            String text
    ) {
        return new ParsedEvent(
                ParsedEventType.THINKING_DELTA,
                text,
                null,
                null
        );
    }

    /**
     * 创建完整工具调用事件。
     *
     * @param toolUse 已完成输入解析的工具调用
     * @return 工具调用事件
     */
    public static ParsedEvent toolCallCompleted(
            ToolUseBlock toolUse
    ) {
        return new ParsedEvent(
                ParsedEventType.TOOL_CALL_COMPLETED,
                null,
                toolUse,
                null
        );
    }

    /**
     * 创建工具结果事件。
     *
     * @param toolUse 对应的完整工具调用
     * @param toolResult 工具执行结果
     * @return 工具结果事件
     */
    public static ParsedEvent toolResult(
            ToolUseBlock toolUse,
            ToolExecutionResult toolResult
    ) {
        return new ParsedEvent(
                ParsedEventType.TOOL_RESULT,
                null,
                toolUse,
                toolResult
        );
    }

    /**
     * 创建消息结束事件。
     *
     * @return 消息结束事件
     */
    public static ParsedEvent messageStopped() {
        return new ParsedEvent(
                ParsedEventType.MESSAGE_STOPPED,
                null,
                null,
                null
        );
    }
}
