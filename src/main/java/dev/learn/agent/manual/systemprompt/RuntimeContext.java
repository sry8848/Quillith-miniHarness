package dev.learn.agent.manual.systemprompt;

import java.nio.file.Path;

/**
 * System Prompt Provider 读取的当前程序真实状态。
 *
 * @param cwd 当前 Agent 工作目录，文件工具以此作为路径边界
 * @param gitRoot 当前工作目录所属的 Git 仓库根目录
 */
public record RuntimeContext(
        Path cwd,
        Path gitRoot
) {}
