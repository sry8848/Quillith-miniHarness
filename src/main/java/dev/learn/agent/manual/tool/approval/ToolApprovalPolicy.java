package dev.learn.agent.manual.tool.approval;

import dev.learn.agent.manual.tool.ToolCall;

/**
 * 在工具执行前判断一次完整工具调用是否需要审批。
 *
 * <p>策略只负责分类，不负责读取用户输入、不负责执行工具，
 * 也不感知当前使用的是 ASK 还是 BYPASS 模式。</p>
 */
@FunctionalInterface
public interface ToolApprovalPolicy {

    /**
     * 判断完整工具调用的审批要求。
     *
     * @param toolCall 实际准备执行的完整工具调用
     * @return REQUIRED 或 NOT_REQUIRED
     */
    ToolApprovalRequirement requirementFor(
            ToolCall toolCall
    );
}
