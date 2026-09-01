package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.hook.hooks.BackgroundTaskHook;
import dev.learn.agent.manual.hook.hooks.LargeOutputHook;
import dev.learn.agent.manual.hook.hooks.SessionSummaryHook;
import dev.learn.agent.manual.hook.hooks.TodoReminderHook;
import dev.learn.agent.manual.hook.hooks.ToolLoggingHook;
import dev.learn.agent.manual.hook.hooks.WorkspaceLoggingHook;
import dev.learn.agent.manual.mcp.GitHubMcpClient;
import dev.learn.agent.manual.mcp.GitMcpClient;
import dev.learn.agent.manual.mcp.McpAgentTool;
import dev.learn.agent.manual.mcp.McpToolClient;
import dev.learn.agent.manual.memory.MemoryRuntime;
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
import dev.learn.agent.manual.tool.entity.TodoState;
import dev.learn.agent.manual.tool.tools.*;
import dev.learn.agent.manual.utils.GitRepositoryResolver;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

/**
 * 创建并持有一次交互式 Agent 进程使用的长期资源。
 */
public final class AgentRuntime implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    AgentRuntime.class
            );

    private static final String BASE_URL =
            "https://dashscope.aliyuncs.com/apps/anthropic";
    private static final String MODEL =
            "qwen3.5-flash";
    private static final int MAX_PARENT_MODEL_ROUNDS =
            100;
    private static final int MAX_SUBAGENT_MODEL_ROUNDS =
            30;
    private static final String MAIN_AGENT_OWNER =
            "main-agent";

    private final ManualAgent manualAgent;
    private final BackgroundTaskScheduler parentBackgroundScheduler;
    private final BackgroundTaskScheduler subagentBackgroundScheduler;
    private final BashTool parentBashTool;
    private final BashTool subagentBashTool;
    private final GitMcpClient gitMcpClient;
    private final GitHubMcpClient githubMcpClient;
    private final AnthropicClient client;

    private AgentRuntime(
            ManualAgent manualAgent,
            BackgroundTaskScheduler parentBackgroundScheduler,
            BackgroundTaskScheduler subagentBackgroundScheduler,
            BashTool parentBashTool,
            BashTool subagentBashTool,
            GitMcpClient gitMcpClient,
            GitHubMcpClient githubMcpClient,
            AnthropicClient client
    ) {
        this.manualAgent = manualAgent;
        this.parentBackgroundScheduler = parentBackgroundScheduler;
        this.subagentBackgroundScheduler = subagentBackgroundScheduler;
        this.parentBashTool = parentBashTool;
        this.subagentBashTool = subagentBashTool;
        this.gitMcpClient = gitMcpClient;
        this.githubMcpClient = githubMcpClient;
        this.client = client;
    }

    /**
     * 按当前应用装配顺序创建完整 Runtime。
     *
     * @param cwd 当前工作目录
     * @param memoryEnabled 父 Agent 的初始记忆开关
     * @param approvalMode 当前进程使用的现有工具审批模式
     * @param scanner ASK 模式共享的终端输入，BYPASS 模式传 null
     * @return 已完成依赖装配的 Runtime
     * @throws IOException 工作区或 Runtime 资源初始化失败
     */
    public static AgentRuntime create(
            Path cwd,
            boolean memoryEnabled,
            ToolApprovalMode approvalMode,
            Scanner scanner
    ) throws IOException {
        Objects.requireNonNull(
                cwd,
                "cwd 不能为空"
        );
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

        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        cwd
                );
        SkillRegistry skillRegistry =
                new SkillRegistry(
                        cwd.resolve(
                                "skills"
                        )
                );
        TodoState todoState =
                new TodoState();
        TaskStore taskStore =
                new TaskStore(
                        paths
                );

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

        GitMcpClient gitMcpClient = null;
        GitHubMcpClient githubMcpClient =
                new GitHubMcpClient(
                        githubToken
                );
        try {
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
            registerMcpTools(
                    toolRegistry,
                    githubMcpClient,
                    "github"
            );
        } catch (RuntimeException exception) {
            githubMcpClient.close();
            if (gitMcpClient != null) {
                gitMcpClient.close();
            }
            throw exception;
        }

        ToolRegistry subagentToolRegistry =
                new ToolRegistry();
        subagentToolRegistry.registerAll(
                subagentBashTool,
                readFileTool,
                writeFileTool,
                editFileTool,
                globTool
        );

        ToolLoggingHook toolLoggingHook =
                new ToolLoggingHook();
        ToolApprovalPolicy approvalPolicy =
                new DefaultToolApprovalPolicy(
                        paths
                );
        ToolApprovalGate approvalGate =
                new ToolApprovalGate(
                        approvalPolicy,
                        approvalMode,
                        scanner
                );
        LargeOutputHook largeOutputHook =
                new LargeOutputHook();

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

        HookRegistry subagentHookRegistry =
                new HookRegistry();
        subagentHookRegistry.registerAll(
                toolLoggingHook,
                largeOutputHook,
                new BackgroundTaskHook(
                        subagentBackgroundScheduler
                )
        );

        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(BASE_URL)
                        .maxRetries(2)
                        .build();
        MemoryRuntime memoryRuntime =
                new MemoryRuntime(
                        memoryEnabled,
                        client,
                        MODEL,
                        paths
                );
        ContextManager contextManager =
                new ContextManager(
                        client,
                        MODEL,
                        paths
                );
        ModelRequestRecoveryManager recoveryManager =
                new ModelRequestRecoveryManager(
                        List.of(
                                new ContentRejectionRecoveryHandler()
                        )
                );

        StreamOutputPrinter subagentOutputPrinter =
                new StreamOutputPrinter(
                        StreamOutputPrinter.silentTerminal()
                );
        StreamOutputPrinter parentOutputPrinter =
                new StreamOutputPrinter(
                        System.out
                );

        SystemPromptManager subagentSystemPromptManager =
                new SystemPromptManager(
                        List.of(
                                IdentitySystemPromptProvider.subagent(),
                                new WorkspaceSystemPromptProvider()
                        )
                );
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

        toolRegistry.register(
                new TaskTool(
                        subagentLoop
                )
        );

        SystemPromptManager parentSystemPromptManager =
                new SystemPromptManager(
                        List.of(
                                IdentitySystemPromptProvider.parent(),
                                new WorkspaceSystemPromptProvider(),
                                new MemorySystemPromptProvider(
                                        memoryRuntime
                                ),
                                skillRegistry
                        )
                );
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
        ManualAgent manualAgent =
                new ManualAgent(
                        agentLoop,
                        memoryRuntime,
                        hookRegistry,
                        parentSystemPromptManager,
                        runtimeContext
                );

        return new AgentRuntime(
                manualAgent,
                parentBackgroundScheduler,
                subagentBackgroundScheduler,
                parentBashTool,
                subagentBashTool,
                gitMcpClient,
                githubMcpClient,
                client
        );
    }

    /**
     * 返回本 Runtime 唯一的父 ManualAgent。
     */
    public ManualAgent manualAgent() {
        return manualAgent;
    }

    /**
     * 按当前 Application finally 的顺序关闭资源。
     */
    @Override
    public void close() {
        parentBackgroundScheduler.close();
        subagentBackgroundScheduler.close();
        parentBashTool.close();
        subagentBashTool.close();
        githubMcpClient.close();
        if (gitMcpClient != null) {
            gitMcpClient.close();
        }
        client.close();
    }

    /**
     * 初始化一个 MCP Client，并把发现的工具注册到指定工具表。
     */
    private static void registerMcpTools(
            ToolRegistry toolRegistry,
            McpToolClient client,
            String namespace
    ) {
        client.initialize();
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
}
