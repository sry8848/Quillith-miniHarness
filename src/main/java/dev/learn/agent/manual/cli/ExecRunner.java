package dev.learn.agent.manual.cli;

import com.anthropic.errors.AnthropicServiceException;
import dev.learn.agent.manual.AgentSession;

import java.io.IOException;

/**
 * 把一条命令行 instruction 提交给 AgentSession，并在完成后结束执行。
 */
public final class ExecRunner {

    /**
     * 执行一次任务，并把已完成诊断的 Provider Error 映射为非零退出码。
     *
     * @param agentSession 当前 Runtime 创建的父 Agent
     * @param instruction 命令行提供的单次任务
     * @return 正常完成返回 0，不可恢复 Provider Error 返回 1
     * @throws IOException Turn 的记忆读写失败
     */
    public int run(
            AgentSession agentSession,
            String instruction
    ) throws IOException {
        try {
            // 1. Exec 与 Interactive 共用同一条 Turn 编排，只是不再读取下一条输入。
            agentSession.submit(
                    instruction
            );
            return 0;
        } catch (AnthropicServiceException ignored) {
            // 2. AgentSession 已输出 Provider 诊断并回滚失败 Turn，Runner 只负责进程结果。
            return 1;
        }
    }
}
