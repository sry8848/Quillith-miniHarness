package dev.learn.agent.manual;

import com.anthropic.core.JsonValue;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.cli.ApplicationOptions;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.hook.hooks.BackgroundTaskHook;
import dev.learn.agent.manual.hook.hooks.LargeOutputHook;
import dev.learn.agent.manual.hook.hooks.SessionSummaryHook;
import dev.learn.agent.manual.hook.hooks.TodoReminderHook;
import dev.learn.agent.manual.hook.hooks.ToolLoggingHook;
import dev.learn.agent.manual.hook.hooks.WorkspaceLoggingHook;
import dev.learn.agent.manual.mcp.GitMcpClient;
import dev.learn.agent.manual.mcp.GitHubMcpClient;
import dev.learn.agent.manual.mcp.McpAgentTool;
import dev.learn.agent.manual.mcp.McpToolClient;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.memory.MemoryTurnResult;
import dev.learn.agent.manual.output.StreamOutputPrinter;
import dev.learn.agent.manual.recovery.ContentRejectionRecoveryHandler;
import dev.learn.agent.manual.recovery.ModelRequestRecoveryManager;
import dev.learn.agent.manual.skill.SkillRegistry;
import dev.learn.agent.manual.systemprompt.IdentitySystemPromptProvider;
import dev.learn.agent.manual.systemprompt.MemorySystemPromptProvider;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.RuntimeContext;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.systemprompt.WorkspaceSystemPromptProvider;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.tool.approval.DefaultToolApprovalPolicy;
import dev.learn.agent.manual.tool.approval.ToolApprovalGate;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.tool.approval.ToolApprovalPolicy;
import dev.learn.agent.manual.tool.tools.*;
import dev.learn.agent.manual.utils.GitRepositoryResolver;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import dev.learn.agent.manual.tool.entity.TodoState;
import io.modelcontextprotocol.spec.McpSchema;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 手写 Java Coding Agent 的程序入口。
 *
 * 负责创建模型客户端、工具、Hook 和会话历史，
 * 再把这些对象组装成完整的 Agent。
 */
public final class ManualAgentApplication {

    // 记录可选 Git 能力的调试信息，不让正常的无 Git 环境污染用户终端。
    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    ManualAgentApplication.class
            );

    private static final String BASE_URL =
            "https://dashscope.aliyuncs.com/apps/anthropic";

    private static final String MODEL =
            "qwen3.5-flash";

    /*
     * 当前应用只有交互式入口，因此默认需要询问用户。
     * 未来非交互入口应在应用装配层传入 BYPASS，
     * 不要让审批策略自行解析命令行参数。
     */
    private static final ToolApprovalMode DEFAULT_APPROVAL_MODE =
            ToolApprovalMode.ASK;

    /*
     * 父 Agent 负责完整任务和多次委派，
     * 因此允许比单个子任务更多的主循环轮次。
     */
    private static final int MAX_PARENT_MODEL_ROUNDS =
            100;

    /*
     * 子 Agent 应处理边界清晰的子任务，
     * 30 轮上限用于阻止委派任务陷入无限工具循环。
     */
    private static final int MAX_SUBAGENT_MODEL_ROUNDS =
            30;

    /*
     * 持久化任务的 owner 由 Harness 绑定，
     * 不允许模型通过工具参数伪造或替换执行者身份。
     */
    private static final String MAIN_AGENT_OWNER =
            "main-agent";

    /**
     * 启动交互式 Coding Agent。
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

        Path cwd =
                Path.of("")
                        .toRealPath();

        // 创建本次会话的终端记录文件，并让所有现有 System.out 调用同时输出到终端和文件。
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

        // Git 是可选能力；发现失败时保留 null，后面不装配 Git MCP。
        Path gitRoot = null;

        try {
            // Git 负责从当前工作目录向上发现仓库根目录，文件工作区本身不随之扩大。
            gitRoot =
                    GitRepositoryResolver.findRoot(
                            cwd
                    );
        } catch (IOException exception) {
            // 当前环境没有可用 Git 仓库时，只跳过依赖 Git 的 MCP，不阻断 Agent 启动。
            LOGGER.debug(
                    "未发现可用的 Git 仓库，跳过 Git MCP 注册：{}",
                    exception.getMessage()
            );
        }

        // 保存 System Prompt Provider 当前能够读取的真实程序状态。
        RuntimeContext runtimeContext =
                new RuntimeContext(
                        cwd,
                        gitRoot
                );

        Path bashExecutable =
                Path.of(
                        "C:\\Windows\\System32\\bash.exe"
                );

        String apiKey =
                System.getenv(
                        "DASHSCOPE_API_KEY"
                );

        if (apiKey == null
                || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 DASHSCOPE_API_KEY"
            );
        }

        // 远程 GitHub MCP 使用独立的 GitHub Token，不能复用模型服务的 API Key。
        String githubToken =
                System.getenv(
                        "GITHUB_PERSONAL_ACCESS_TOKEN"
                );

        if (githubToken == null
                || githubToken.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 GITHUB_PERSONAL_ACCESS_TOKEN"
            );
        }

        /*
         * 所有文件工具共享一个路径解析器，
         * 因而具有完全相同的工作区边界。
         */
        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        cwd
                );

        /*
         * 技能目录属于 Agent 的启动配置。
         *
         * 注册表只在启动时扫描一次，
         * 使 system prompt 和 load_skill 使用完全相同的技能快照。
         */
        SkillRegistry skillRegistry =
                new SkillRegistry(
                        cwd.resolve(
                                "skills"
                        )
                );

        /*
         * TodoWriteTool 负责写入状态，
         * TodoReminderHook 负责读取状态。
         *
         * 两者必须持有同一个 TodoState 实例。
         */
        TodoState todoState =
                new TodoState();

        /*
         * 新版 s10 的项目任务通过工作区 .tasks 目录跨会话保存。
         * TaskStore 是任务状态、依赖检查和文件发布的唯一边界。
         */
        TaskStore taskStore =
                new TaskStore(
                        paths
                );

        /*
         * 父子 Agent 操作同一个工作区，
         * 因此共享只保存工作区依赖的文件工具实例。
         *
         * BashTool 必须按 AgentLoop 分开创建，
         * 因为后台任务状态和活动进程属于各自的生命周期。
         */
        BackgroundTaskScheduler parentBackgroundScheduler =
                new BackgroundTaskScheduler();

        BashTool parentBashTool =
                new BashTool(
                        cwd,
                        bashExecutable,
                        parentBackgroundScheduler
                );

        BackgroundTaskScheduler subagentBackgroundScheduler =
                new BackgroundTaskScheduler();

        BashTool subagentBashTool =
                new BashTool(
                        cwd,
                        bashExecutable,
                        subagentBackgroundScheduler
                );

        ReadFileTool readFileTool =
                new ReadFileTool(
                        paths
                );

        WriteFileTool writeFileTool =
                new WriteFileTool(
                        paths
                );

        EditFileTool editFileTool =
                new EditFileTool(
                        paths
                );

        GlobTool globTool =
                new GlobTool(
                        paths
                );

        /*
         * 主 Agent 保留完整的会话规划能力。
         *
         * task 工具会在后续步骤中只注册到这个 Registry。
         */
        ToolRegistry toolRegistry =
                new ToolRegistry();

        toolRegistry.registerAll(
                parentBashTool,
                readFileTool,
                writeFileTool,
                editFileTool,
                globTool,
                new TodoWriteTool(
                        todoState
                ),
                new CreateTaskTool(
                        taskStore
                ),
                new ListTasksTool(
                        taskStore
                ),
                new GetTaskTool(
                        taskStore
                ),
                new ClaimTaskTool(
                        taskStore,
                        MAIN_AGENT_OWNER
                ),
                new CompleteTaskTool(
                        taskStore,
                        MAIN_AGENT_OWNER
                ),
                new LoadSkillTool(
                        skillRegistry
                )
        );

        /*
         * 主 Agent 在启动阶段按可用能力注册本地 Git MCP 和远程 GitHub MCP。
         *
         * Git MCP 依赖当前工作目录对应的 Git 仓库；
         * 没有 gitRoot 时不创建、不初始化也不注册 Git MCP。
         *
         * MCP 工具只进入父 Agent，避免子 Agent 绕过主会话的 MCP 调用边界。
         */
        GitMcpClient gitMcpClient = null;

        GitHubMcpClient githubMcpClient =
                new GitHubMcpClient(
                        githubToken
                );

        try {
            // 没有 Git 仓库时完整跳过 Git MCP 的创建、握手、工具发现和注册。
            if (gitRoot != null) {
                gitMcpClient =
                        new GitMcpClient(
                                gitRoot
                        );

                registerMcpTools(
                        toolRegistry,
                        gitMcpClient,
                        "git"
                );
            }

            // GitHub MCP 与本地 Git 无关，继续按原流程注册。
            registerMcpTools(
                    toolRegistry,
                    githubMcpClient,
                    "github"
            );
        } catch (RuntimeException exception) {
            // 启动阶段尚未进入主循环，失败时立即关闭已经成功创建的 MCP 连接。
            githubMcpClient.close();
            if (gitMcpClient != null) {
                gitMcpClient.close();
            }
            throw exception;
        }

        /*
         * 子 Agent 使用独立的工具白名单。
         *
         * 不提供 todo_write，避免覆盖主会话规划状态；
         * 不提供持久化任务工具，任务图由父 Agent 协调；
         * 不提供 task，从能力层阻止递归委派。
         */
        ToolRegistry subagentToolRegistry =
                new ToolRegistry();

        subagentToolRegistry.registerAll(
                subagentBashTool,
                readFileTool,
                writeFileTool,
                editFileTool,
                globTool
        );

        Scanner scanner =
                new Scanner(System.in);

        /*
         * 父子 Agent 共用同一个终端和工作区，
         * 因此共享工具日志、大输出处理和审批 Gate。
         *
         * ToolApprovalGate 会串行化终端确认，避免并发工具同时读取 Scanner。
         */
        ToolLoggingHook toolLoggingHook =
                new ToolLoggingHook();

        ToolApprovalPolicy approvalPolicy =
                new DefaultToolApprovalPolicy(
                        paths
                );

        ToolApprovalGate approvalGate =
                new ToolApprovalGate(
                        approvalPolicy,
                        DEFAULT_APPROVAL_MODE,
                        scanner
                );

        LargeOutputHook largeOutputHook =
                new LargeOutputHook();

        /*
         * Hook 只保留日志、输出治理和会话扩展能力，
         * 工具审批由 AgentLoop 中独立的 ToolApprovalGate 负责。
         */
        HookRegistry hookRegistry =
                new HookRegistry();

        hookRegistry.registerAll(
                new WorkspaceLoggingHook(
                        cwd
                ),
                toolLoggingHook,
                largeOutputHook,
                new TodoReminderHook(
                        todoState
                ),
                new SessionSummaryHook(),
                new BackgroundTaskHook(
                        parentBackgroundScheduler
                )
        );

        /*
         * 子 Agent 仍然经过日志和输出治理，
         * 但不继承与主会话状态绑定的 Hook。
         *
         * 特别是不注册 TodoReminderHook，
         * 因为子 Agent 没有 todo_write 工具。
         */
        HookRegistry subagentHookRegistry =
                new HookRegistry();

        subagentHookRegistry.registerAll(
                toolLoggingHook,
                largeOutputHook,
                new BackgroundTaskHook(
                        subagentBackgroundScheduler
                )
        );

        /*
         * 模型请求的瞬态错误由 SDK 统一重试。
         * 显式限制为两次，避免以后叠加应用层重试造成请求放大。
         */
        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(BASE_URL)
                        .maxRetries(2)
                        .build();

        /*
         * [核心] MemoryRuntime 统一持有记忆仓库、召回、提取和整理能力，
         * 并把命令行参数作为本次会话的初始开关状态。
         */
        MemoryRuntime memoryRuntime =
                new MemoryRuntime(
                        options.memoryEnabled(),
                        client,
                        MODEL,
                        paths
                );

        /*
         * 父子 Agent 属于同一次应用会话，
         * 共用管理器可以让上下文产物集中保存在同一个会话目录中。
         *
         * ContextManager 不保存某个 Agent 的消息列表，
         * 所以共享它不会混合父子 Agent 的活跃历史。
         */
        ContextManager contextManager =
                new ContextManager(
                        client,
                        MODEL,
                        paths
                );

        // 按显式顺序注册模型请求级错误 Handler；父子 Agent 共享无状态分派器。
        ModelRequestRecoveryManager recoveryManager =
                new ModelRequestRecoveryManager(
                        List.of(
                                new ContentRejectionRecoveryHandler()
                        )
                );

        // 父 Agent 可见、子 Agent 静默；两者只共享输出策略，不共享终端目标。
        StreamOutputPrinter subagentOutputPrinter =
                new StreamOutputPrinter(
                        StreamOutputPrinter.silentTerminal()
                );

        StreamOutputPrinter parentOutputPrinter =
                new StreamOutputPrinter(
                        System.out
                );

        /*
         * 子 Agent 只注册自己真实拥有的身份和工作区说明。
         * 它没有 todo_write、load_skill 和 task，不加载对应能力说明。
         */
        SystemPromptManager subagentSystemPromptManager =
                new SystemPromptManager(
                        List.of(
                                IdentitySystemPromptProvider
                                        .subagent(),
                                new WorkspaceSystemPromptProvider()
                        )
                );

        // [核心] 应用启动时建立子 Agent 的第一份完整 System Prompt。
        subagentSystemPromptManager.refreshFrom(
                RefreshScope.APPLICATION,
                runtimeContext
        );

        AgentLoop subagentLoop =
                new AgentLoop(
                        client,
                        MODEL,
                        subagentSystemPromptManager,
                        runtimeContext,
                        subagentToolRegistry,
                        approvalGate,
                        subagentHookRegistry,
                        contextManager,
                        subagentOutputPrinter,
                        subagentBackgroundScheduler,
                        MAX_SUBAGENT_MODEL_ROUNDS,
                        recoveryManager
                );

        /*
         * task 只注册到父 Agent 的工具表。
         *
         * 注册必须发生在创建父 AgentLoop 前，
         * 使父 Agent 的最终能力集合在装配阶段就清楚可见。
         */
        toolRegistry.register(
                new TaskTool(
                        subagentLoop
                )
        );

        /*
         * 父 Agent 的能力说明分别由身份、工作区、记忆和 Skill 模块提供。
         * Todo 和 Task 的使用说明已经属于各自的 Tool description。
         * MemoryRuntime 负责统一处理记忆的开启、关闭和运行中切换。
         */
        SystemPromptManager parentSystemPromptManager =
                new SystemPromptManager(
                        List.of(
                                IdentitySystemPromptProvider
                                        .parent(),
                                new WorkspaceSystemPromptProvider(),
                                new MemorySystemPromptProvider(
                                        memoryRuntime
                                ),
                                skillRegistry
                        )
                );

        // [核心] 应用启动时建立父 Agent 的第一份完整 System Prompt。
        parentSystemPromptManager.refreshFrom(
                RefreshScope.APPLICATION,
                runtimeContext
        );

        AgentLoop agentLoop =
                new AgentLoop(
                        client,
                        MODEL,
                        parentSystemPromptManager,
                        runtimeContext,
                        toolRegistry,
                        approvalGate,
                        hookRegistry,
                        contextManager,
                        parentOutputPrinter,
                        parentBackgroundScheduler,
                        MAX_PARENT_MODEL_ROUNDS,
                        recoveryManager
                );


        /*
         * history 跨多次用户输入保留，
         * 模型才能记住之前的对话、工具调用和 TODO 更新。
         */
        List<MessageParam> history =
                new ArrayList<>();

        System.out.println(
                "s11 Background Task Scheduler Agent"
        );

        System.out.println(
                "输入任务并回车，输入 q 或 exit 退出。"
        );

        System.out.println(
                "记忆命令：/memory on、/memory off、/memory status。"
        );

        try {
            while (true) {
                System.out.println();
                System.out.print("s11 >> ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String query =
                        scanner.nextLine();

                String command =
                        query.trim();

                if (command.isEmpty()
                        || "q".equalsIgnoreCase(command)
                        || "exit".equalsIgnoreCase(command)) {
                    break;
                }

                if (isMemoryCommand(command)) {
                    handleMemoryCommand(
                            command,
                            memoryRuntime,
                            parentSystemPromptManager,
                            runtimeContext
                    );
                    continue;
                }

                HookEffect promptEffect =
                        hookRegistry
                                .triggerUserPromptSubmit(
                                        query
                                );

                if (promptEffect.decision()
                        == HookEffect.Decision.BLOCK) {
                    System.out.println(
                            "消息被 Hook 阻止："
                                    + promptEffect.reason()
                    );

                    continue;
                }

                /*
                 * [核心] 每个用户回合开始时刷新工作区和记忆这两个 SESSION section。
                 * 这样上一轮结束后新生成的 MEMORY.md 索引会进入本轮所有模型请求，
                 * 同时不会随着同一回合的每次模型请求重复读取文件。
                 */
                parentSystemPromptManager.refreshFrom(
                        RefreshScope.SESSION,
                        runtimeContext
                );

                /*
                 * [核心] 在主 Agent 第一次处理用户问题前召回相关记忆。
                 *
                 * 返回内容稍后只进入本轮模型请求，
                 * 不会作为正式 MessageParam 写入 history。
                 */
                String recalledMemories =
                        memoryRuntime.recall(
                                query
                        );

                // 记录本次用户消息对象，Provider Error 后按对象身份回滚当前未完成回合。
                MessageParam userMessage =
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.USER
                                )
                                .content(query)
                                .build();
                history.add(
                        userMessage
                );

                /*
                 * UserPromptSubmit Hook 当前只记录日志，
                 * 但 Hook 协议允许它提供额外上下文。
                 */
                if (!promptEffect.additionalContexts()
                        .isEmpty()) {
                    history.add(
                            MessageParam.builder()
                                    .role(
                                            MessageParam.Role.USER
                                    )
                                    .content(
                                            "<system-reminder>\n"
                                                    + String.join(
                                                    "\n\n",
                                                    promptEffect
                                                            .additionalContexts()
                                            )
                                                    + "\n</system-reminder>"
                                    )
                                    .build()
                    );
                }

                /*
                 * [核心] 在 AgentLoop 压缩和替换 history 前，
                 * 保留本轮记忆提取使用的原始文本视图。
                 *
                 * recalledMemories 没有写入 history，
                 * 因此不会进入这份快照并被重复提取。
                 */
                List<MessageParam> memoryExtractionSnapshot =
                        memoryRuntime.capture(
                                history
                        );

                // AgentLoop 通过父 Agent 打印器展示文本、thinking、工具调用和工具结果。
                try {
                    agentLoop.run(
                            history,
                            recalledMemories
                    );
                } catch (AnthropicServiceException exception) {
                    // Provider Error 已无法在 AgentLoop 内安全恢复，展示官方诊断并回滚当前用户回合。
                    printProviderError(
                            exception
                    );
                    rollbackFailedTurn(
                            history,
                            userMessage
                    );
                    continue;
                }

                MemoryTurnResult memoryTurnResult =
                        memoryRuntime.completeTurn(
                                memoryExtractionSnapshot
                        );

                // 保存成功时显示本轮实际产生的记忆数量。
                if (memoryTurnResult.savedCount() > 0) {
                    System.out.println(
                            "[Memory：已保存 "
                                    + memoryTurnResult.savedCount()
                                    + " 条记忆]"
                    );

                    // 整理器返回空列表表示当前尚未达到数量阈值。
                    if (memoryTurnResult.consolidatedCount() > 0) {
                        System.out.println(
                                "[Memory：整理后保留 "
                                        + memoryTurnResult.consolidatedCount()
                                        + " 条记忆]"
                        );
                    }
                }

            }
        } finally {
            parentBackgroundScheduler.close();
            subagentBackgroundScheduler.close();
            parentBashTool.close();
            subagentBashTool.close();
            githubMcpClient.close();
            if (gitMcpClient != null) {
                gitMcpClient.close();
            }
            client.close();
            scanner.close();
        }
    }

    /**
     * 判断输入是否是交互式记忆控制命令。
     */
    private static boolean isMemoryCommand(
            String command
    ) {
        return "/memory".equalsIgnoreCase(
                command
        ) || command.regionMatches(
                true,
                0,
                "/memory ",
                0,
                "/memory ".length()
        );
    }

    /**
     * 执行交互式记忆开关命令，并立即刷新父 Agent 的 System Prompt。
     */
    private static void handleMemoryCommand(
            String command,
            MemoryRuntime memoryRuntime,
            SystemPromptManager parentSystemPromptManager,
            RuntimeContext runtimeContext
    ) {
        String argument =
                command.length()
                        == "/memory".length()
                        ? ""
                        : command.substring(
                                "/memory".length()
                        ).trim();

        if ("on".equalsIgnoreCase(argument)) {
            memoryRuntime.setEnabled(
                    true
            );
            parentSystemPromptManager.refreshFrom(
                    RefreshScope.SESSION,
                    runtimeContext
            );
            System.out.println(
                    "[Memory：已开启]"
            );
            return;
        }

        if ("off".equalsIgnoreCase(argument)) {
            memoryRuntime.setEnabled(
                    false
            );
            parentSystemPromptManager.refreshFrom(
                    RefreshScope.SESSION,
                    runtimeContext
            );
            System.out.println(
                    "[Memory：已关闭]"
            );
            return;
        }

        if ("status".equalsIgnoreCase(argument)
                || argument.isEmpty()) {
            System.out.println(
                    "[Memory："
                            + (memoryRuntime.enabled()
                            ? "已开启"
                            : "已关闭")
                            + "]"
            );
            return;
        }

        System.out.println(
                "用法：/memory on | /memory off | /memory status"
        );
    }

    /**
     * 初始化一个 MCP Client，发现其工具并注册到指定工具表。
     *
     * @param toolRegistry 接收 MCP 工具的 Agent 工具表
     * @param client       本地或远程 MCP 客户端
     * @param namespace    模型侧使用的 MCP Server 命名空间
     */
    private static void registerMcpTools(
            ToolRegistry toolRegistry,
            McpToolClient client,
            String namespace
    ) {
        // 先完成握手，确保后续 tools/list 和 tools/call 都在有效 MCP 会话中执行。
        client.initialize();

        // 服务端动态返回工具定义，宿主只负责增加命名空间并注册工具包装对象。
        List<McpSchema.Tool> tools =
                client.listTools();

        for (McpSchema.Tool tool : tools) {
            toolRegistry.register(
                    new McpAgentTool(
                            client,
                            namespace,
                            tool
                    )
            );
        }
    }

    /**
     * 输出 SDK 和 Provider 原始响应提供的错误诊断。
     *
     * @param exception 模型服务端异常
     */
    private static void printProviderError(
            AnthropicServiceException exception
    ) {
        // 输出 HTTP 状态、标准错误类型、服务端消息和请求标识。
        System.out.println(
                "\n[模型请求失败]"
        );
        System.out.println(
                "HTTP: "
                        + exception.statusCode()
        );
        System.out.println(
                "type: "
                        + providerErrorType(
                        exception
                )
        );
        System.out.println(
                "message: "
                        + providerErrorMessage(
                        exception
                )
        );
        System.out.println(
                "requestId: "
                        + providerRequestId(
                        exception
                )
        );
        System.out.println(
                "body: "
                        + providerErrorBody(
                        exception
                )
        );
    }

    /**
     * 从 Provider 响应中读取错误类型，优先使用百炼根字段和标准嵌套字段。
     */
    private static String providerErrorType(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorNode(
                        exception.body()
                );
        String type =
                readNodeString(
                        body,
                        "code"
                );
        if (!type.isBlank()) {
            return type;
        }

        type =
                readNodeString(
                        body == null
                                ? null
                                : body.get(
                                "error"
                        ),
                        "type"
                );
        if (!type.isBlank()) {
            return type;
        }

        // 百炼兼容端点也可能把错误类型放在响应根字段 type。
        type =
                readNodeString(
                        body,
                        "type"
                );
        if (!type.isBlank()) {
            return type;
        }

        return exception.errorType()
                .map(
                        errorType -> errorType.asString()
                )
                .orElse(
                        "未返回"
                );
    }

    /**
     * 从 Provider 响应中读取服务端错误消息。
     */
    private static String providerErrorMessage(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorNode(
                        exception.body()
                );
        String message =
                readNodeString(
                        body,
                        "message"
                );
        if (!message.isBlank()) {
            return message;
        }

        message =
                readNodeString(
                        body == null
                                ? null
                                : body.get(
                                "error"
                        ),
                        "message"
                );
        if (!message.isBlank()) {
            return message;
        }

        return exception.getMessage() == null
                || exception.getMessage().isBlank()
                ? "未返回"
                : exception.getMessage();
    }

    /**
     * 从 Provider 响应 Body 或标准请求头读取 Request ID。
     */
    private static String providerRequestId(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorNode(
                        exception.body()
                );
        String requestId =
                readNodeString(
                        body,
                        "request_id"
                );
        if (requestId.isBlank()) {
            requestId =
                    readNodeString(
                            body,
                            "requestId"
                    );
        }
        if (!requestId.isBlank()) {
            return requestId;
        }

        if (exception.headers() != null) {
            for (String headerName
                    : exception.headers().names()) {
                if (!"request-id".equalsIgnoreCase(
                        headerName
                ) && !"x-request-id".equalsIgnoreCase(
                        headerName
                )) {
                    continue;
                }

                List<String> values =
                        exception.headers()
                                .values(
                                        headerName
                                );
                if (!values.isEmpty()
                        && !values.get(0).isBlank()) {
                    return values.get(0);
                }
            }
        }

        return "未返回";
    }

    /**
     * 把 SDK JsonValue 错误体转换为 Jackson 节点。
     */
    private static JsonNode providerErrorNode(
            JsonValue value
    ) {
        if (value == null
                || value.isMissing()
                || value.isNull()) {
            return null;
        }

        try {
            return value.convert(
                    JsonNode.class
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 读取 Jackson 对象节点中的文本字段。
     */
    private static String readNodeString(
            JsonNode object,
            String fieldName
    ) {
        if (object == null
                || !object.isObject()) {
            return "";
        }

        JsonNode field =
                object.get(
                        fieldName
                );
        return field == null
                || !field.isTextual()
                ? ""
                : field.textValue();
    }

    /**
     * 返回原始 Provider Body，供无法解析时定位端点兼容问题。
     */
    private static String providerErrorBody(
            AnthropicServiceException exception
    ) {
        JsonValue body =
                exception.body();
        if (body == null
                || body.isMissing()
                || body.isNull()) {
            return "未返回";
        }

        JsonNode node =
                providerErrorNode(
                        body
                );
        return node == null
                ? body.toString()
                : node.toString();
    }

    /**
     * 从当前历史中移除本次尚未完成的用户回合及其后续消息。
     *
     * @param history 可修改的会话历史
     * @param userMessage 本次用户输入的原始消息对象
     */
    private static void rollbackFailedTurn(
            List<MessageParam> history,
            MessageParam userMessage
    ) {
        // 仅按对象身份定位本次用户消息，避免相同文本的历史消息被误删。
        for (int index = 0;
             index < history.size();
             index++) {
            if (history.get(index)
                    != userMessage) {
                continue;
            }

            // 用户消息之后的工具事实也属于失败回合，一并回滚到上一次提交边界。
            history.subList(
                    index,
                    history.size()
            ).clear();
            return;
        }
    }
}
