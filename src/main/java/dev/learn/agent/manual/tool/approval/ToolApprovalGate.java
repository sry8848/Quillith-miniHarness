package dev.learn.agent.manual.tool.approval;

import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.ToolCall;

import java.util.Objects;
import java.util.Scanner;

/**
 * 在工具真正执行前组合审批策略、Session 审批状态和控制台询问。
 *
 * <p>Gate 不执行工具。它只返回本次调用是否可以继续进入工具注册表。</p>
 */
public final class ToolApprovalGate {

    /* 负责判断工具调用本身是否需要审批。 */
    private final ToolApprovalPolicy policy;

    /* 负责提供每次调用都应读取的当前审批模式。 */
    private final SessionState sessionState;

    /* ASK 模式使用的共享终端输入；BYPASS 模式可以为空。 */
    private final Scanner scanner;

    /**
     * 创建工具审批 Gate。
     *
     * @param policy 审批分类策略
     * @param sessionState 当前 CLI Session 状态
     * @param scanner ASK 模式读取用户选择的 Scanner，BYPASS 模式可以为空
     */
    public ToolApprovalGate(
            ToolApprovalPolicy policy,
            SessionState sessionState,
            Scanner scanner
    ) {
        this.policy =
                Objects.requireNonNull(
                        policy,
                        "ToolApprovalPolicy 不能为空"
                );

        this.sessionState =
                Objects.requireNonNull(
                        sessionState,
                        "SessionState 不能为空"
                );

        if (sessionState.approvalMode() == ToolApprovalMode.ASK) {
            this.scanner =
                    Objects.requireNonNull(
                            scanner,
                            "ASK 模式需要 Scanner"
                    );
        } else {
            this.scanner = scanner;
        }
    }

    /**
     * 判断工具调用是否可以继续执行。
     *
     * <p>无论当前模式是什么，都先调用 Policy；BYPASS 只跳过询问，
     * 不改变 Policy 的分类结果。审批模式在每次调用时从 SessionState 读取。</p>
     *
     * @param toolCall 实际准备执行的完整工具调用
     * @return true 表示允许进入 ToolRegistry，false 表示本次不执行
     */
    public boolean approve(
            ToolCall toolCall
    ) {
        ToolApprovalRequirement requirement =
                policy.requirementFor(
                        Objects.requireNonNull(
                                toolCall,
                                "ToolCall 不能为空"
                        )
                );

        if (requirement
                == ToolApprovalRequirement.NOT_REQUIRED) {
            return true;
        }

        if (sessionState.approvalMode() == ToolApprovalMode.BYPASS) {
            return true;
        }

        return askUser(toolCall);
    }

    /**
     * 串行读取控制台审批结果。
     *
     * <p>父 Agent 和子 Agent 共用一个 Gate，多个并发工具不能同时读取同一个
     * Scanner，因此这里只同步实际询问部分。</p>
     *
     * @param toolCall 待审批的完整工具调用
     * @return 用户明确输入 y 或 yes 时返回 true
     */
    private synchronized boolean askUser(
            ToolCall toolCall
    ) {
        if (scanner == null) {
            throw new IllegalStateException(
                    "ASK 模式需要 Scanner"
            );
        }

        System.out.println();
        System.out.println("工具调用需要审批");
        System.out.println(
                "工具：" + toolCall.name()
        );
        System.out.println(
                "参数：" + toolCall.input()
        );
        System.out.print(
                "是否允许执行？[y/N] "
        );

        String choice =
                scanner.nextLine()
                        .trim();

        return "y".equalsIgnoreCase(choice)
                || "yes".equalsIgnoreCase(choice);
    }
}
