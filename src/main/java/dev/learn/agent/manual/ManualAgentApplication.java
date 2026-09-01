package dev.learn.agent.manual;

import dev.learn.agent.manual.cli.ApplicationOptions;
import dev.learn.agent.manual.cli.ExecRunner;
import dev.learn.agent.manual.cli.InteractiveRunner;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * 手写 Java Coding Agent 的程序入口。
 *
 * 负责进程级终端、输入和 Runtime 生命周期，不承载 Agent Turn 逻辑。
 */
public final class ManualAgentApplication {

    /**
     * 解析启动模式，并运行交互会话或一条 exec 任务。
     *
     * @param args 命令行参数
     */
    public static void main(
            String[] args
    ) throws IOException {
        ApplicationOptions options =
                ApplicationOptions.parse(
                        args
                );

        // 1. 两种模式共用同一个实际工作目录和 Agent Core。
        Path cwd =
                Path.of("")
                        .toRealPath();

        // 2. 只有输入生命周期不同：Interactive 持续读取，Exec 只提交一次。
        int exitCode =
                switch (options.mode()) {
                    case INTERACTIVE -> {
                        runInteractive(
                                cwd,
                                options
                        );
                        yield 0;
                    }
                    case EXEC ->
                            runExec(
                                    cwd,
                                    options
                            );
                };

        // 3. 正常路径自然返回，只有 Exec 已确认的 Provider 最终失败需要非零进程状态。
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 保持现有 transcript、Scanner 和 ASK 审批行为运行交互模式。
     *
     * @param cwd 当前工作目录
     * @param options 已解析的交互模式选项
     * @throws IOException transcript 或 Runtime 初始化失败
     */
    private static void runInteractive(
            Path cwd,
            ApplicationOptions options
    ) throws IOException {
        // 1. Interactive 继续保存完整终端 transcript，保持原有本地会话行为。
        Path transcriptDirectory =
                cwd.resolve(
                        ".task_outputs"
                ).resolve(
                        "transcripts"
                );
        Files.createDirectories(
                transcriptDirectory
        );
        String transcriptTimestamp =
                DateTimeFormatter.ofPattern(
                                "yyyyMMdd-HHmmss-SSS"
                        ).withZone(
                                ZoneId.systemDefault()
                        ).format(
                                Instant.now()
                        );
        Path transcriptPath =
                transcriptDirectory.resolve(
                        "manual-agent-"
                                + transcriptTimestamp
                                + ".log"
                );
        OutputStream transcriptFile =
                Files.newOutputStream(
                        transcriptPath,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
        PrintStream originalTerminal =
                System.out;
        PrintStream sessionTerminal =
                new PrintStream(
                        new TeeOutputStream(
                                originalTerminal,
                                transcriptFile
                        ),
                        true,
                        StandardCharsets.UTF_8
                );
        System.setOut(
                sessionTerminal
        );
        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        sessionTerminal::close,
                        "terminal-transcript-close"
                )
        );
        System.out.println(
                "[Terminal transcript] "
                        + transcriptPath
        );

        // 2. 同一个 Scanner 同时服务交互输入和现有 ASK 工具审批。
        Scanner scanner =
                new Scanner(System.in);

        try {
            try (AgentRuntime runtime =
                         AgentRuntime.create(
                                 cwd,
                                 options.memoryEnabled(),
                                 ToolApprovalMode.ASK,
                                 scanner
                         )) {
                new InteractiveRunner(
                        scanner
                ).run(
                        runtime.manualAgent()
                );
            }
        } finally {
            scanner.close();
        }
    }

    /**
     * 在 Harbor 隔离环境中使用现有 BYPASS 模式执行一条任务。
     *
     * @param cwd 当前工作目录
     * @param options 已解析的 exec 模式选项
     * @return 正常完成返回 0，不可恢复 Provider Error 返回 1
     * @throws IOException Runtime 初始化或 Turn 记忆读写失败
     */
    private static int runExec(
            Path cwd,
            ApplicationOptions options
    ) throws IOException {
        // 1. Exec 不创建 Scanner 和 workspace transcript，输出直接交给 Harbor 捕获。
        try (AgentRuntime runtime =
                     AgentRuntime.create(
                             cwd,
                             options.memoryEnabled(),
                             ToolApprovalMode.BYPASS,
                             null
                     )) {
            // 2. BYPASS 复用现有权限模式；安全边界由不暴露宿主目录的 Harbor 环境提供。
            return new ExecRunner().run(
                    runtime.manualAgent(),
                    options.instruction()
            );
        }
    }
}
