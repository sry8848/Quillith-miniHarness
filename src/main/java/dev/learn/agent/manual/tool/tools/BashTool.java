package dev.learn.agent.manual.tool.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionMode;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 使用当前运行环境配置的 Bash executable 执行 Shell 命令。
 *
 * <p>Workspace 只是进程的初始工作目录，不是文件系统沙盒；
 * 工具审批由 AgentLoop 外部的审批边界统一处理。</p>
 */
public final class BashTool implements AgentTool, AutoCloseable {

    private static final int TIMEOUT_SECONDS = 120;

    /*
     * 最多保存前 50000 字节输出。
     *
     * 超出部分仍会从进程管道读取，但不再保存，
     * 防止大量输出一直占用内存。
     */
    private static final int MAX_OUTPUT_BYTES = 50_000;

    /*
     * 发送给模型的 Bash 工具定义。
     */
    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "bash",
                    "Run a Bash command with the workspace as the initial working directory. "
                            + "This is not a filesystem sandbox. "
                            + "The host approval policy applies to the entire Bash call. "
                            + "Set run_in_background=true only for a command "
                            + "that may start immediately. The command result "
                            + "is delivered to this agent before its final answer.",
                    Map.of(
                            "command",
                            ToolDefinitionFactory.stringProperty(
                                    "The Bash command to execute."
                            ),
                            "run_in_background",
                            JsonValue.from(
                                    Map.of(
                                            "type",
                                            "boolean",
                                            "description",
                                            "Start the command in the background."
                                    )
                            )
                    ),
                    List.of("command")
            );

    private final Path workspace;

    private final Path bashExecutable;

    // 当前 AgentLoop 独有的后台任务调度器。
    private final BackgroundTaskScheduler backgroundScheduler;

    // 保存尚未退出的 Bash 进程，应用关闭时统一终止它们。
    private final Set<Process> runningProcesses =
            ConcurrentHashMap.newKeySet();

    /**
     * 创建 Bash 工具。
     *
     * @param workspace           命令默认执行目录
     * @param bashExecutable      当前运行环境的 Bash executable 路径
     * @param backgroundScheduler 当前 AgentLoop 的后台任务调度器
     */
    public BashTool(
            Path workspace,
            Path bashExecutable,
            BackgroundTaskScheduler backgroundScheduler
    ) {
        this.workspace =
                Objects.requireNonNull(
                                workspace,
                                "Workspace 不能为空"
                        )
                        .toAbsolutePath()
                        .normalize();

        this.bashExecutable =
                Objects.requireNonNull(
                                bashExecutable,
                        "Bash 路径不能为空"
                        )
                        .toAbsolutePath()
                        .normalize();

        this.backgroundScheduler =
                Objects.requireNonNull(
                        backgroundScheduler,
                        "BackgroundTaskScheduler 不能为空"
                );

        /*
         * 这些路径来自程序内部配置，
         * 配置错误时应在启动阶段立即失败。
         */
        if (!Files.isDirectory(this.workspace)) {
            throw new IllegalArgumentException(
                    "Workspace 不存在："
                            + this.workspace
            );
        }

        if (!Files.isRegularFile(this.bashExecutable)) {
            throw new IllegalArgumentException(
                    "Bash executable 不存在："
                            + this.bashExecutable
            );
        }
    }

    /**
     * 返回发送给模型的工具定义。
     */
    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /**
     * 读取本次 Bash 调用是否明确要求后台执行。
     *
     * @param input 模型生成的 JSON 参数
     * @return 布尔值为 true 时进入后台调度器，否则进入前台
     */
    @Override
    public ToolExecutionMode executionMode(
            JsonNode input
    ) {
        JsonNode runInBackgroundNode =
                input.get(
                        "run_in_background"
                );

        return runInBackgroundNode != null
                && runInBackgroundNode.isBoolean()
                && runInBackgroundNode.booleanValue()
                ? ToolExecutionMode.BACKGROUND
                : ToolExecutionMode.FOREGROUND;
    }

    /**
     * 执行模型生成的 Bash 命令。
     */
    @Override
    public ToolExecutionResult execute(JsonNode input) {
        JsonNode commandNode =
                input.get("command");

        /*
         * 即使工具 Schema 声明 command 必填，
         * 执行边界仍然需要校验模型实际返回的参数。
         */
        if (commandNode == null
                || !commandNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: command must be a string"
            );
        }

        String command =
                commandNode.textValue();

        JsonNode runInBackgroundNode =
                input.get(
                        "run_in_background"
                );

        if (runInBackgroundNode != null
                && !runInBackgroundNode.isBoolean()) {
            return ToolExecutionResult.failure(
                    "Error: run_in_background must be a boolean"
            );
        }

        if (runInBackgroundNode != null
                && runInBackgroundNode.booleanValue()) {
            String taskId =
                    backgroundScheduler.start(
                            command,
                            () -> executeSynchronously(
                                    command
                            )
                    );

            return ToolExecutionResult.success(
                    "Background task "
                            + taskId
                            + " started. The command result will be "
                            + "delivered before the final answer."
            );
        }

        return executeSynchronously(command);
    }

    /**
     * 同步执行 Bash 命令，并保留原有超时、退出码和输出限制。
     */
    private ToolExecutionResult executeSynchronously(
            String command
    ) {

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        bashExecutable.toString(),
                        "--noprofile",
                        "--norc",
                        "-c",
                        command
                )
                        .directory(
                                workspace.toFile()
                        )
                        /*
                         * 把标准错误合并进标准输出，
                         * 最终一起返回给模型。
                        */
                        .redirectErrorStream(true);

        Process process;

        try {
            process =
                    processBuilder.start();
        } catch (IOException exception) {
            return ToolExecutionResult.failure(
                    "Error: failed to start Bash: "
                            + exception.getMessage()
            );
        }

        // 进程创建成功后立即登记，确保 close() 能看到后台 Bash。
        runningProcesses.add(process);

        try {

            /*
             * 单独使用一个虚拟线程持续读取进程输出。
             *
             * 如果等待进程结束后才读取，
             * 大量输出可能填满操作系统管道，导致进程无法继续。
             */
            try (ExecutorService executor =
                         Executors.newVirtualThreadPerTaskExecutor()) {

                Future<CapturedOutput> outputFuture =
                        executor.submit(
                                () -> readOutput(
                                        process.getInputStream()
                                )
                        );

                boolean finished =
                        process.waitFor(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                if (!finished) {
                    // 超时后统一终止 Bash 及其子进程，避免留下真实后台命令。
                    stopProcess(process);

                    return ToolExecutionResult.failure(
                            "Error: command timed out after "
                                    + TIMEOUT_SECONDS
                                    + " seconds"
                    );
                }

                CapturedOutput captured =
                        outputFuture.get();

                String output =
                        captured.text()
                                .strip();

                if (output.isEmpty()) {
                    output = "(no output)";
                }

                if (captured.truncated()) {
                    output +=
                            "\n... output truncated";
                }

                // 读取命令真实退出码，不能用“进程已结束”代替成功判断。
                int exitCode =
                        process.exitValue();

                // 成功和失败共享同一份可诊断输出格式。
                String result =
                        "Exit code: "
                                + exitCode
                                + "\n"
                                + output;

                /*
                 * Shell 进程正常结束不代表命令成功，
                 * 非零退出码必须作为失败状态交给模型。
                 */
                    return exitCode == 0
                        ? ToolExecutionResult.success(
                                result
                        )
                        : ToolExecutionResult.failure(
                                result
                        );
            }
        } catch (InterruptedException exception) {
            // 当前执行线程被中断时同时终止 Bash，避免 Java 任务退出而进程继续运行。
            stopProcess(process);

            /*
             * 恢复线程的中断标记，
             * 让上层代码仍然知道线程曾被中断。
             */
            Thread.currentThread()
                    .interrupt();

            return ToolExecutionResult.failure(
                    "Error: Bash execution interrupted"
            );
        } catch (ExecutionException exception) {
            return ToolExecutionResult.failure(
                    "Error: failed to read Bash output: "
                            + exception.getCause()
                            .getMessage()
            );
        } finally {
            // 命令正常结束、失败或被关闭后都移除进程登记。
            runningProcesses.remove(process);
        }
    }

    /**
     * 终止 Bash 进程及其子进程，并等待 Bash 退出。
     */
    private void stopProcess(
            Process process
    ) {
        // 先终止 Bash 派生的真实命令，再终止 Bash 主进程。
        process.descendants()
                .forEach(
                        ProcessHandle::destroyForcibly
                );

        process.destroyForcibly();

        try {
            process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
        }
    }

    /**
     * 终止当前工具仍然持有的全部 Bash 进程。
     */
    @Override
    public void close() {
        runningProcesses.forEach(
                this::stopProcess
        );
    }

    /**
     * 持续读取进程输出，只保存前 MAX_OUTPUT_BYTES 字节。
     */
    private CapturedOutput readOutput(
            InputStream input
    ) throws IOException {
        ByteArrayOutputStream captured =
                new ByteArrayOutputStream();

        byte[] buffer =
                new byte[8_192];

        boolean truncated = false;

        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
            int remaining =
                    MAX_OUTPUT_BYTES
                            - captured.size();

            if (remaining > 0) {
                int bytesToKeep =
                        Math.min(
                                remaining,
                                bytesRead
                        );

                captured.write(
                        buffer,
                        0,
                        bytesToKeep
                );
            }

            if (bytesRead > remaining) {
                truncated = true;
            }
        }

        return new CapturedOutput(
                captured.toString(
                        StandardCharsets.UTF_8
                ),
                truncated
        );
    }

    /**
     * 同时保存截取后的文本和是否发生截断。
     */
    private record CapturedOutput(
            String text,
            boolean truncated
    ) {
    }
}
