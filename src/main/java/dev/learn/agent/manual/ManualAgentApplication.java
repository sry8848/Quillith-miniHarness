package dev.learn.agent.manual;

import dev.learn.agent.manual.cli.ApplicationOptions;
import dev.learn.agent.manual.cli.ExecRunner;
import dev.learn.agent.manual.cli.HarborRunner;
import dev.learn.agent.manual.cli.InteractiveRunner;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.GitRepositoryResolver;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.List;
import java.util.Scanner;

/**
 * 手写 Java Coding Agent 的程序入口。
 *
 * 负责进程级终端、输入和 Runtime 生命周期，不承载 Agent Turn 逻辑。
 */
public final class ManualAgentApplication {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    ManualAgentApplication.class
            );

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

        // 1. 三种输入入口共用同一个实际工作目录和 Agent Core。
        Path cwd =
                Path.of("")
                        .toRealPath();

        // 2. 当前固定用户目录配置值保持与旧实现一致，后续只替换这个局部配置值。
        Path configuredAgentHome = cwd;
        Files.createDirectories(
                configuredAgentHome
        );
        Path agentHome =
                configuredAgentHome.toRealPath();

        // 3. 启动时以 workspace 发现 Git 根目录；未发现仓库仍允许为空。
        Path gitRoot = null;
        try {
            gitRoot =
                    GitRepositoryResolver.findRoot(
                            cwd
                    );
        } catch (IOException exception) {
            LOGGER.debug(
                    "未发现可用的 Git 仓库，跳过 Git MCP 注册：{}",
                    exception.getMessage()
            );
        }

        // 4. 集合只用一个初始 workspace，不把它实现成只能单目录的限制。
        List<Path> allowedRoots =
                List.of(cwd);

        // 5. 一次启动只创建一份 Session 状态，父 Agent 和 SubAgent 共享它。
        AgentState agentState =
                new AgentState(
                        options.memoryEnabled(),
                        options.mode() == ApplicationOptions.Mode.INTERACTIVE
                                ? ToolApprovalMode.ASK
                                : ToolApprovalMode.BYPASS,
                        agentHome,
                        cwd,
                        allowedRoots,
                        gitRoot
                );

        // 6. 三种入口只改变输入生命周期，不复制 Agent Core。
        int exitCode =
                switch (options.mode()) {
                    case INTERACTIVE -> {
                        runInteractive(
                                agentState,
                                options
                        );
                        yield 0;
                    }
                    case EXEC ->
                            runExec(
                                    agentState,
                                    options
                            );
                    case HARBOR -> {
                        runHarbor(
                                agentState
                        );
                        yield 0;
                    }
                };

        // 7. 正常路径自然返回，只有 Exec 已确认的 Provider 最终失败需要非零进程状态。
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 保持现有 transcript、Scanner 和 ASK 审批行为运行交互模式。
     *
     * @param agentState 当前 CLI Session 状态
     * @param options 已解析的交互模式选项
     * @throws IOException transcript 或 Runtime 初始化失败
     */
    private static void runInteractive(
            AgentState agentState,
            ApplicationOptions options
    ) throws IOException {
        // 1. Interactive 继续保存完整终端 transcript，保持原有本地会话行为。
        Path transcriptDirectory =
                createTerminalTranscriptDirectory(
                        agentState
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
                                 agentState,
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
     * 创建当前 Session 的终端 transcript 目录。
     *
     * @param agentState 当前 CLI Session 状态
     * @return agentHome 下已经创建的终端 transcript 目录
     * @throws IOException 目录创建失败
     */
    static Path createTerminalTranscriptDirectory(
            AgentState agentState
    ) throws IOException {
        // 1. 终端运行记录属于 Agent 自身，不随当前 workspace 改变位置。
        Path transcriptDirectory =
                agentState.agentHome()
                        .resolve(".task_outputs")
                        .resolve("transcripts");

        // 2. 创建失败直接交给应用入口终止启动，不回退到 workspace。
        Files.createDirectories(
                transcriptDirectory
        );
        return transcriptDirectory;
    }

    /**
     * 在 Harbor 隔离环境中使用现有 BYPASS 模式执行一条任务。
     *
     * @param agentState 当前 CLI Session 状态
     * @param options 已解析的 exec 模式选项
     * @return 正常完成返回 0，不可恢复 Provider Error 返回 1
     * @throws IOException Runtime 初始化或 Turn 记忆读写失败
     */
    private static int runExec(
            AgentState agentState,
            ApplicationOptions options
    ) throws IOException {
        // 1. Exec 不创建 Scanner 和终端 transcript，输出直接交给 Harbor 捕获。
        try (AgentRuntime runtime =
                     AgentRuntime.create(
                             agentState,
                             null
                     )) {
            // 2. BYPASS 复用现有权限模式；安全边界由不暴露宿主目录的 Harbor 环境提供。
            return new ExecRunner().run(
                    runtime.manualAgent(),
                    options.instruction()
            );
        }
    }

    /**
     * 在 Harbor 隔离环境中保持一份 Interactive Session 并处理多个 Turn。
     *
     * @param agentState 当前 Harbor Trial 唯一的 Session 状态
     * @throws IOException Runtime 初始化、FIFO 通信或 Turn 执行失败
     */
    private static void runHarbor(
            AgentState agentState
    ) throws IOException {
        // 1. 一个 Harbor 进程只创建一份 Runtime，所有 Turn 共用它。
        try (AgentRuntime runtime =
                     AgentRuntime.create(
                             agentState,
                             null
                     )) {
            // Harbor 使用 BYPASS，不创建人工审批 Scanner 或终端 transcript。
            new HarborRunner().run(
                    runtime.manualAgent()
            );
        }
    }
}
