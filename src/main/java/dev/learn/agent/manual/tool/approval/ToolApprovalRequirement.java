package dev.learn.agent.manual.tool.approval;

/**
 * 描述一次工具调用在执行前是否需要用户审批。
 *
 * <p>它是审批策略的分类结果，不代表用户最终是否同意，
 * 也不代表工具最终是否执行成功。</p>
 */
public enum ToolApprovalRequirement {

    /**
     * 当前工具调用不需要用户审批。
     */
    NOT_REQUIRED,

    /**
     * 当前工具调用需要审批；是否真正询问由审批模式决定。
     */
    REQUIRED
}
