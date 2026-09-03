package dev.learn.agent.manual.tool;

/**
 * 表示重新执行相同 Tool Call 没有意义或可能带来风险的失败。
 *
 * <p>Tool 用本异常表达参数、权限、前置状态和业务校验等最终失败语义；
 * Harness 收到后必须立即停止当前调用。</p>
 */
public final class NonRetryableToolException
        extends RuntimeException {

    /**
     * 创建带有可见失败说明的不可重试异常。
     *
     * @param message 供最终 Tool Result 使用的失败说明
     */
    public NonRetryableToolException(
            String message
    ) {
        super(message);
    }

    /**
     * 创建保留底层原因的不可重试异常。
     *
     * @param message 供最终 Tool Result 使用的失败说明
     * @param cause 底层最终失败原因
     */
    public NonRetryableToolException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
