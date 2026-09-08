package dev.learn.agent.manual;

import dev.learn.agent.manual.tool.approval.ToolApprovalMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 保存一个 CLI Session 的唯一语义状态、策略状态和路径上下文。
 *
 * <p>当前 Turn 序号、记忆开关和审批模式允许在运行期间改变；路径上下文在
 * Session 创建时确定。父 Agent、SubAgent 以及它们的组件都应读取同一个实例。</p>
 */
public final class SessionState {

    /* Session ID 是会话身份的唯一事实来源，只在显式新建或恢复 Session 时切换。 */
    private String sessionId;

    /* 当前正在执行的 submit 所属 Turn 序号。 */
    private long turnSeq;

    /* 后台任务可能与交互线程并行读取，状态修改需要立即可见。 */
    private volatile boolean memoryEnabled;
    private volatile ToolApprovalMode approvalMode;

    /* 路径上下文在一次 Session 内固定，避免运行期间出现边界漂移。 */
    private final Path agentHome;
    private final Path workspace;
    private final List<Path> allowedRoots;
    private final Path gitRoot;

    /**
     * 创建一个 Session 状态。
     *
     * @param memoryEnabled 初始是否启用记忆
     * @param approvalMode 初始工具审批模式
     * @param agentHome Agent 自身数据和资源目录
     * @param workspace 默认工作目录及相对路径解析基准
     * @param allowedRoots Agent 可访问和修改的真实目录集合
     * @param gitRoot 当前 Git 仓库根目录；未发现时为 {@code null}
     */
    public SessionState(
            boolean memoryEnabled,
            ToolApprovalMode approvalMode,
            Path agentHome,
            Path workspace,
            List<Path> allowedRoots,
            Path gitRoot
    ) {
        // 1. 创建本次 Session 的稳定 ID。
        this.sessionId =
                UUID.randomUUID()
                        .toString();

        // 2. 保存运行期可变状态和创建时确定的路径上下文。
        this.memoryEnabled = memoryEnabled;
        this.approvalMode = Objects.requireNonNull(
                approvalMode,
                "ToolApprovalMode 不能为空"
        );
        this.agentHome = Objects.requireNonNull(
                agentHome,
                "agentHome 不能为空"
        );
        this.workspace = Objects.requireNonNull(
                workspace,
                "workspace 不能为空"
        );
        this.allowedRoots = List.copyOf(
                Objects.requireNonNull(
                        allowedRoots,
                        "allowedRoots 不能为空"
                )
        );
        this.gitRoot = gitRoot;
    }

    /**
     * 返回当前 Session 创建时生成的稳定 ID。
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 记录当前 submit 的 Turn 序号。
     *
     * @param turnSeq 当前 Session 内递增的 Turn 序号
     */
    void beginTurn(
            long turnSeq
    ) {
        this.turnSeq = turnSeq;
    }

    /**
     * 返回当前正在执行的 submit 所属 Turn 序号。
     *
     * @return 当前 Turn 序号
     */
    public long turnSeq() {
        return turnSeq;
    }

    /**
     * 在 workspace 校验通过后切换到已持久化 Session 的 ID。
     *
     * @param sessionId 要恢复的稳定 Session ID
     */
    void restoreSessionId(
            String sessionId
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
    }

    /**
     * 为一个新的空白 Session 生成稳定 ID。
     *
     * @return 新 Session ID
     */
    String startNewSession() {
        // 1. 新 Session 使用与应用启动时相同的 UUID 生成规则。
        sessionId = UUID.randomUUID().toString();
        return sessionId;
    }

    /**
     * 返回当前是否启用记忆。
     */
    public boolean memoryEnabled() {
        return memoryEnabled;
    }

    /**
     * 修改当前 Session 的记忆开关。
     *
     * @param memoryEnabled 新的记忆开关
     */
    public void setMemoryEnabled(
            boolean memoryEnabled
    ) {
        this.memoryEnabled = memoryEnabled;
    }

    /**
     * 返回当前工具审批模式。
     */
    public ToolApprovalMode approvalMode() {
        return approvalMode;
    }

    /**
     * 修改当前 Session 的工具审批模式。
     *
     * @param approvalMode 新的工具审批模式
     */
    public void setApprovalMode(
            ToolApprovalMode approvalMode
    ) {
        this.approvalMode = Objects.requireNonNull(
                approvalMode,
                "ToolApprovalMode 不能为空"
        );
    }

    /**
     * 返回 Agent 自身的数据和资源目录。
     */
    public Path agentHome() {
        return agentHome;
    }

    /**
     * 返回默认工作目录及相对路径解析基准。
     */
    public Path workspace() {
        return workspace;
    }

    /**
     * 返回 Agent 可访问和修改的目录集合。
     */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * 返回当前 Git 仓库根目录；未发现仓库时返回 {@code null}。
     */
    public Path gitRoot() {
        return gitRoot;
    }
}
