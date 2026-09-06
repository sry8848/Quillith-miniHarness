package dev.learn.agent.manual.session;

/**
 * 表示 Session durable 事实无法写入的 Harness 系统故障。
 */
public final class SessionPersistenceException extends RuntimeException {

    /**
     * 创建保留底层存储异常的持久化故障。
     *
     * @param message 当前持久化操作的上下文
     * @param cause SQLite 或 JSON 存储失败原因
     */
    public SessionPersistenceException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
