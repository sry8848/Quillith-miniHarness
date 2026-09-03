package dev.learn.agent.manual.tool;

/**
 * 表示重新完整执行同一 Tool Call 仍然安全且可能成功的失败。
 *
 * <p>Tool 负责把内部暂时性错误转换为本异常；Harness 只根据本异常决定重试，
 * 不理解 Tool 内部的 IO、网络或业务错误类型。</p>
 */
public final class RetryableToolException
        extends RuntimeException {

    /**
     * 创建带有可见失败说明的可重试异常。
     *
     * @param message 供最终 Tool Result 使用的失败说明
     */
    public RetryableToolException(
            String message
    ) {
        super(message);
    }

    /**
     * 创建保留底层原因的可重试异常。
     *
     * @param message 供最终 Tool Result 使用的失败说明
     * @param cause 底层暂时性错误
     */
    public RetryableToolException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
