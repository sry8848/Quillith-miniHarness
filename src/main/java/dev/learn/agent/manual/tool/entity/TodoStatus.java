package dev.learn.agent.manual.tool.tools;

/**
 * 一项 todo_ 在当前 Agent 任务中的执行状态。
 */
public enum TodoStatus {

    /**
     * 尚未开始。
     */
    PENDING,

    /**
     * 当前正在执行。
     */
    IN_PROGRESS,

    /**
     * 已经完成。
     */
    COMPLETED
}
