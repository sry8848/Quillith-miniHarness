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
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.systemprompt.SystemPromptProvider;
import dev.learn.agent.manual.systemprompt.WorkspaceSystemPromptProvider;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.tool.ToolRetryPolicy;
import dev.learn.agent.manual.tool.approval.DefaultToolApprovalPolicy;
import dev.learn.agent.manual.tool.approval.ToolApprovalGate;
import dev.learn.agent.manual.tool.approval.ToolApprovalPolicy;
import dev.learn.agent.manual.tool.entity.TodoState;
import dev.learn.agent.manual.tool.tools.*;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;

/**
 * 创建并持有一次交互式 Agent 进程使用的长期资源。
 */
public final class AgentRuntime implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    AgentRuntime.class
            );

    private static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/apps/anthropic";
    private static final String DEFAULT_MODEL =
            "qwen3.5-flash";
    private static final String DEFAULT_WINDOWS_BASH_EXECUTABLE =
            "C:\\Windows\\System32\\bash.exe";
    private static final String DEFAULT_LINUX_BASH_EXECUTABLE =
            "/bin/bash";
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
     * @param agentState 当前 CLI Session 的唯一状态
     * @param scanner ASK 模式共享的终端输入，BYPASS 模式传 null
     * @return 已完成依赖装配的 Runtime
     * @throws IOException 工作区或 Runtime 资源初始化失败
     */
    public static AgentRuntime create(
            AgentState agentState,
            Scanner scanner
    ) throws IOException {
        Objects.requireNonNull(
                agentState,
                "AgentState 不能为空"
        );
        Path workspace = agentState.workspace();
        Path gitRoot = agentState.gitRoot();

        // 1. Harbor 显式模型配置优先，未提供时保持当前 Quillith 默认模型。
        String configuredModel =
                System.getenv(
                        "QUILLITH_MODEL"
                );
        String model =
                configuredModel == null
                        || configuredModel.isBlank()
                        ? DEFAULT_MODEL
                        : configuredModel;

        // 2. Harbor 显式 API Key 优先，未提供时继续支持本地百炼配置。
        String apiKey =
                System.getenv(
                        "QUILLITH_API_KEY"
                );
        if (apiKey == null
                || apiKey.isBlank()) {
            apiKey =
                    System.getenv(
                            "DASHSCOPE_API_KEY"
                    );
        }
        if (apiKey == null
                || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 QUILLITH_API_KEY 或 DASHSCOPE_API_KEY"
            );
        }

        // 3. Base URL 缺省时保留当前 Anthropic-compatible 地址。
        String configuredBaseUrl =
                System.getenv(
                        "QUILLITH_BASE_URL"
                );
        String baseUrl =
                configuredBaseUrl == null
                        || configuredBaseUrl.isBlank()
                        ? DEFAULT_BASE_URL
                        : configuredBaseUrl;

        // 4. Bash 可由环境显式指定，否则按运行平台使用默认 executable。
        String configuredBashExecutable =
                System.getenv(
                        "QUILLITH_BASH_EXECUTABLE"
                );
        Path bashExecutable;
        if (configuredBashExecutable != null) {
            if (configuredBashExecutable.isBlank()) {
                throw new IllegalStateException(
                        "环境变量 QUILLITH_BASH_EXECUTABLE 不能为空"
                );
            }
            bashExecutable =
                    Path.of(
                            configuredBashExecutable
                    );
        } else {
            boolean windows =
                    System.getProperty(
                                    "os.name"
                            ).startsWith(
                                    "Windows"
                            );
            bashExecutable =
                    Path.of(
                            windows
                                    ? DEFAULT_WINDOWS_BASH_EXECUTABLE
                                    : DEFAULT_LINUX_BASH_EXECUTABLE
                    );
        }

        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        agentState
                );
        SkillRegistry skillRegistry =
                loadSkillRegistry(
                        agentState.agentHome()
                                .resolve(
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
                        workspace,
                        bashExecutable,
                        parentBackgroundScheduler
                );
        BackgroundTaskScheduler subagentBackgroundScheduler =
                new BackgroundTaskScheduler();
        BashTool subagentBashTool =
                new BashTool(
                        workspace,
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

        // 1. 父 Agent 和子 Agent 使用同一份不可变重试策略，保证 Tool Call 语义一致。
        ToolRetryPolicy toolRetryPolicy =
                ToolRetryPolicy.defaults();
        ToolRegistry toolRegistry =
                new ToolRegistry(
                        toolRetryPolicy
                );
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
                )
        );

        // 5. Skill 是可选能力；只有完整加载且至少发现一个 Skill 时才注册相关工具。
        if (skillRegistry != null) {
            toolRegistry.register(
                    new LoadSkillTool(
                            skillRegistry
                    )
            );
        }

        GitMcpClient gitMcpClient = null;
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
        } catch (RuntimeException exception) {
            if (gitMcpClient != null) {
                gitMcpClient.close();
            }
            throw exception;
        }

        GitHubMcpClient githubMcpClient =
                loadGitHubMcpClient(
                        toolRegistry
                );

        ToolRegistry subagentToolRegistry =
                new ToolRegistry(
                        toolRetryPolicy
                );
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
                        agentState,
                        scanner
                );
        LargeOutputHook largeOutputHook =
                new LargeOutputHook();

        HookRegistry hookRegistry =
                new HookRegistry();
        hookRegistry.registerAll(
                new WorkspaceLoggingHook(
                        workspace
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
                        .baseUrl(baseUrl)
                        .maxRetries(2)
                        .build();
        MemoryRuntime memoryRuntime =
                new MemoryRuntime(
                        agentState,
                        client,
                        model,
                        paths
                );
        ContextManager contextManager =
                new ContextManager(
                        client,
                        model,
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
                agentState
        );

        AgentLoop subagentLoop =
                new AgentLoop(
                        client,
                        model,
                        subagentSystemPromptManager,
                        agentState,
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

        List<SystemPromptProvider> parentPromptProviders =
                new ArrayList<>(
                        List.of(
                                IdentitySystemPromptProvider.parent(),
                                new WorkspaceSystemPromptProvider(),
                                new MemorySystemPromptProvider(
                                        memoryRuntime
                                )
                        )
                );
        if (skillRegistry != null) {
            parentPromptProviders.add(
                    skillRegistry
            );
        }
        SystemPromptManager parentSystemPromptManager =
                new SystemPromptManager(
                        parentPromptProviders
                );
        parentSystemPromptManager.refreshFrom(
                RefreshScope.APPLICATION,
                agentState
        );

        AgentLoop agentLoop =
                new AgentLoop(
                        client,
                        model,
                        parentSystemPromptManager,
                        agentState,
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
                        agentState
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
        if (githubMcpClient != null) {
            githubMcpClient.close();
        }
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
        // 1. 先完成协议握手和工具发现，外部服务失败时不修改本地工具表。
        client.initialize();
        List<McpSchema.Tool> tools =
                client.listTools();

        // 2. 先构造并校验全部适配器，避免单个 Schema 错误留下半注册工具。
        Set<String> remoteToolNames =
                new HashSet<>();
        List<McpAgentTool> agentTools =
                new ArrayList<>(
                        tools.size()
                );
        for (McpSchema.Tool tool : tools) {
            if (!remoteToolNames.add(
                    tool.name()
            )) {
                throw new IllegalArgumentException(
                        "MCP 返回重复工具名称："
                                + tool.name()
                );
            }
            agentTools.add(
                    new McpAgentTool(
                            client,
                            namespace,
                            tool
                    )
            );
        }

        // 3. 所有工具准备完成后再提交注册；命名空间保证不同 MCP 之间不互相覆盖。
        for (McpAgentTool agentTool : agentTools) {
            toolRegistry.register(
                    agentTool
            );
        }
    }

    /**
     * 尝试加载 Skill 注册表。
     *
     * @param skillsDirectory Skill 根目录
     * @return 至少包含一个 Skill 的注册表；目录缺失、为空或加载异常时返回 null
     */
    private static SkillRegistry loadSkillRegistry(
            Path skillsDirectory
    ) {
        try {
            // 目录缺失是正常的零技能状态，不需要产生启动噪音。
            if (Files.notExists(
                    skillsDirectory
            )) {
                return null;
            }

            SkillRegistry skillRegistry =
                    new SkillRegistry(
                            skillsDirectory
                    );

            // 目录为空同样是正常状态，不向模型暴露永远无法加载内容的工具。
            return skillRegistry.isEmpty()
                    ? null
                    : skillRegistry;
        } catch (Exception exception) {
            // 保留完整堆栈供内部诊断，同时只停用可选 Skill 能力。
            LOGGER.error(
                    "Skill 初始化失败，跳过 Skill 能力：{}",
                    skillsDirectory,
                    exception
            );
            return null;
        }
    }

    /**
     * 尝试创建并注册 GitHub MCP。
     *
     * @param toolRegistry 父 Agent 工具注册表
     * @return 初始化成功的 GitHub MCP 客户端；不可用时返回 null
     */
    private static GitHubMcpClient loadGitHubMcpClient(
            ToolRegistry toolRegistry
    ) {
        String githubToken;
        try {
            githubToken =
                    System.getenv(
                            "GITHUB_PERSONAL_ACCESS_TOKEN"
                    );
        } catch (Exception exception) {
            // 读取可选配置失败也不能阻塞启动，保留异常供内部诊断。
            LOGGER.error(
                    "读取 GitHub MCP 配置失败，跳过 GitHub MCP",
                    exception
            );
            return null;
        }

        if (githubToken == null
                || githubToken.isBlank()) {
            // 未配置令牌是正常的可选能力缺失，需要提示但不能阻塞启动。
            LOGGER.warn(
                    "未配置 GITHUB_PERSONAL_ACCESS_TOKEN，跳过 GitHub MCP"
            );
            return null;
        }

        GitHubMcpClient githubMcpClient = null;
        try {
            // 只有令牌存在时才创建远程客户端，避免无效连接和空资源清理。
            githubMcpClient =
                    new GitHubMcpClient(
                            githubToken
                    );
            registerMcpTools(
                    toolRegistry,
                    githubMcpClient,
                    "github"
            );
            return githubMcpClient;
        } catch (Exception exception) {
            // 未知异常也只影响 GitHub MCP；完整异常留在日志中，Agent 继续启动。
            LOGGER.error(
                    "GitHub MCP 初始化失败，跳过 GitHub MCP",
                    exception
            );
            if (githubMcpClient != null) {
                try {
                    githubMcpClient.close();
                } catch (Exception closeException) {
                    LOGGER.error(
                            "GitHub MCP 降级清理失败",
                            closeException
                    );
                }
            }
            return null;
        }
    }
}
