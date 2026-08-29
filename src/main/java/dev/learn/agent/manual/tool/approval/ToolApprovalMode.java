package dev.learn.agent.manual.tool.approval;

/**
 * 控制需要审批的工具调用是否真正询问用户。
 */
public enum ToolApprovalMode {

    /**
     * 需要审批的工具调用也直接放行。
     */
    BYPASS,

    /**
     * 只有审批策略返回 REQUIRED 时才询问用户。
     */
    ASK
}
