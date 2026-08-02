package dev.learn.agent.manual.systemprompt;

import java.nio.file.Path;

/**
 * System Prompt Provider 读取的当前程序真实状态。
 *
 * @param workspace 当前 Agent 工作区
 */
public record RuntimeContext(
        Path workspace
) {}
