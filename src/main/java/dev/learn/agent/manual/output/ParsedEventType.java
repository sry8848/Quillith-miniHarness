// 声明流式输出事件类型所在的包。
package dev.learn.agent.manual.output;

/**
 * AgentLoop 已经识别、可以交给打印器处理的事件类型。
 */
public enum ParsedEventType {

    /**
     * 模型普通文本的一个增量。
     */
    TEXT_DELTA,

    /**
     * 模型 thinking 内容的一个增量。
     */
    THINKING_DELTA,

    /**
     * 工具调用参数已经完整解析完成。
     */
    TOOL_CALL_COMPLETED,

    /**
     * 工具执行已经得到结果。
     */
    TOOL_RESULT,

    /**
     * 一次模型消息已经收到 message_stop。
     */
    MESSAGE_STOPPED
}
