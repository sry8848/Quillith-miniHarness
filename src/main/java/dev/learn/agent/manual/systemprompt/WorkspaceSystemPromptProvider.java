package dev.learn.agent.manual.systemprompt;

import dev.learn.agent.manual.AgentState;

import java.util.Optional;

/**
 * 根据当前程序状态生成模型可见的工作区说明。
 */
public final class WorkspaceSystemPromptProvider
        implements SystemPromptProvider {

    /**
     * 返回工作区 section 的稳定 id。
     */
    @Override
    public String id() {
        return "workspace";
    }

    /**
     * 工作区属于当前会话环境。
     */
    @Override
    public RefreshScope scope() {
        return RefreshScope.SESSION;
    }

    /**
     * 工作区说明紧跟在基础身份之后。
     */
    @Override
    public int order() {
        return 200;
    }

    /**
     * 从 AgentState 派生模型需要看到的工作目录和 Git 仓库目录。
     */
    @Override
    public Optional<String> load(
            AgentState agentState
    ) {
        return Optional.of(
                "当前工作目录："
                        + agentState.workspace()
                        + "\nGit 仓库根目录："
                        + (agentState.gitRoot() == null
                        ? "未发现"
                        : agentState.gitRoot().toString())
        );
    }
}
