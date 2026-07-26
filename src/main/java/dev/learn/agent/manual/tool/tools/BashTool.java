package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 使用 Git Bash 执行 Shell 命令。
 */
public final class BashTool implements AgentTool {

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
                    "Run a Bash command in the workspace.",
                    Map.of(
                            "command",
                            ToolDefinitionFactory.stringProperty(
                                    "The Bash command to execute."
                            )
                    ),
                    List.of("command")
            );

    private final Path workspace;

    private final Path bashExecutable;

    /**
     * 创建 Bash 工具。
     *
     * @param workspace      命令默认执行目录
     * @param bashExecutable Git Bash 的 bash.exe 路径
     */
    public BashTool(
            Path workspace,
            Path bashExecutable
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
                    "Git Bash 不存在："
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
     * 执行模型生成的 Bash 命令。
     */
    @Override
    public String execute(JsonNode input) {
        JsonNode commandNode =
                input.get("command");

        /*
         * 即使工具 Schema 声明 command 必填，
         * 执行边界仍然需要校验模型实际返回的参数。
         */
        if (commandNode == null
                || !commandNode.isTextual()) {
            return "Error: command must be a string";
        }

        String command =
                commandNode.textValue();

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

        try {
            Process process =
                    processBuilder.start();

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
                    /*
                     * 先终止 Bash 启动的子进程，
                     * 再终止 Bash 本身。
                     */
                    process.descendants()
                            .forEach(
                                    ProcessHandle::destroyForcibly
                            );

                    process.destroyForcibly();
                    process.waitFor();

                    return "Error: command timed out after "
                            + TIMEOUT_SECONDS
                            + " seconds";
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

                return "Exit code: "
                        + process.exitValue()
                        + "\n"
                        + output;
            }
        } catch (IOException exception) {
            return "Error: failed to start Bash: "
                    + exception.getMessage();
        } catch (InterruptedException exception) {
            /*
             * 恢复线程的中断标记，
             * 让上层代码仍然知道线程曾被中断。
             */
            Thread.currentThread()
                    .interrupt();

            return "Error: Bash execution interrupted";
        } catch (ExecutionException exception) {
            return "Error: failed to read Bash output: "
                    + exception.getCause()
                    .getMessage();
        }
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
