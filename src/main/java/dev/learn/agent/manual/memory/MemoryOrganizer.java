package dev.learn.agent.manual.memory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.AgentLoop;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.output.StreamOutputPrinter;
import dev.learn.agent.manual.recovery.ContentRejectionRecoveryHandler;
import dev.learn.agent.manual.recovery.ModelRequestRecoveryManager;
import dev.learn.agent.manual.session.NoOpTurnJournal;
import dev.learn.agent.manual.systemprompt.IdentitySystemPromptProvider;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.systemprompt.WorkspaceSystemPromptProvider;
import dev.learn.agent.manual.telemetry.GenAiSpanAttributes;
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.tool.approval.DefaultToolApprovalPolicy;
import dev.learn.agent.manual.tool.approval.ToolApprovalGate;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.tool.tools.BashTool;
import dev.learn.agent.manual.tool.tools.EditFileTool;
import dev.learn.agent.manual.tool.tools.GlobTool;
import dev.learn.agent.manual.tool.tools.ReadFileTool;
import dev.learn.agent.manual.tool.tools.WriteFileTool;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 复用现有 AgentLoop 整理 llmwiki，并在正常结束后提交 Git 版本。
 */
public final class MemoryOrganizer implements AutoCloseable {

    private static final int MAX_MODEL_ROUNDS = 30;
    private static final String ORGANIZE_REQUEST =
            "整理当前 llmwiki；检查近期候选、目录结构、canonical description、时间字段和各级 index，完成后用 git diff 自检。";

    // 整理循环只持有 llmwiki 专用状态和五个文件操作工具。
    private final AgentLoop agentLoop;
    private final Path llmwikiRoot;
    private final BashTool bashTool;
    private final BackgroundTaskScheduler backgroundScheduler;

    /**
     * 创建 llmwiki 专用 AgentLoop，并初始化独立 Git 仓库。
     *
     * @param client 应用共享的模型客户端
     * @param model 整理使用的模型名称
     * @param store llmwiki 固定文件操作入口
     * @param bashExecutable 当前环境已有的 Bash 可执行文件
     * @param agentHome 父运行时的 Agent 数据目录
     * @throws IOException Git 仓库或路径解析无法初始化
     */
    public MemoryOrganizer(
            AnthropicClient client,
            String model,
            LlmwikiStore store,
            Path bashExecutable,
            Path agentHome
    ) throws IOException {
        Objects.requireNonNull(client, "AnthropicClient 不能为空");
        Objects.requireNonNull(model, "model 不能为空");
        Objects.requireNonNull(store, "LlmwikiStore 不能为空");
        Objects.requireNonNull(bashExecutable, "Bash 路径不能为空");
        Objects.requireNonNull(agentHome, "agentHome 不能为空");

        // 1. llmwiki 使用自己的 Git 仓库，不把外层数据库纳入版本历史。
        this.llmwikiRoot = store.root();
        runGit("init");

        // 2. 文件工具只以 llmwiki 为工作区；Bash 仍按首期约定依赖 Prompt 约束。
        SessionState organizerState = new SessionState(false, ToolApprovalMode.BYPASS,
                agentHome, llmwikiRoot, List.of(llmwikiRoot), llmwikiRoot);
        WorkspacePathResolver paths = new WorkspacePathResolver(organizerState);
        ContextManager contextManager = new ContextManager(paths);
        ToolRegistry toolRegistry = new ToolRegistry();
        this.backgroundScheduler = new BackgroundTaskScheduler();
        this.bashTool = new BashTool(llmwikiRoot, bashExecutable, backgroundScheduler);
        toolRegistry.registerAll(
                bashTool,
                new ReadFileTool(paths),
                new WriteFileTool(paths),
                new EditFileTool(paths),
                new GlobTool(paths)
        );

        // 3. 整理 Agent 使用独立中文 Prompt，不加载主 Session、Task、Skill 或 MCP 能力。
        SystemPromptManager promptManager = new SystemPromptManager(List.of(
                IdentitySystemPromptProvider.memoryOrganizer(),
                new WorkspaceSystemPromptProvider()
        ));
        promptManager.refreshFrom(RefreshScope.APPLICATION, organizerState);
        ToolApprovalGate approvalGate = new ToolApprovalGate(
                new DefaultToolApprovalPolicy(paths), organizerState, null);
        ModelRequestRecoveryManager recoveryManager = new ModelRequestRecoveryManager(
                List.of(new ContentRejectionRecoveryHandler()));

        // 4. 复用已有循环；NoOp Journal 和无压缩器保证整理过程不进入主会话历史。
        this.agentLoop = new AgentLoop(
                client,
                model,
                promptManager,
                organizerState,
                toolRegistry,
                approvalGate,
                new HookRegistry(),
                contextManager,
                new StreamOutputPrinter(StreamOutputPrinter.silentTerminal()),
                backgroundScheduler,
                MAX_MODEL_ROUNDS,
                recoveryManager,
                new NoOpTurnJournal(),
                Optional.empty()
        );
    }

    /**
     * 执行一次语义整理，并由程序提交全部 llmwiki 修改。
     *
     * @throws IOException Git 命令无法启动、被中断或提交失败
     */
    @WithSpan("memory.organize")
    public void organize() throws IOException {
        GenAiSpanAttributes.recordMemoryOrganizationStart();

        // 1. 每次整理使用新的短会话，上一批模型输出不会进入下一批上下文。
        List<MessageParam> messages = new ArrayList<>(List.of(
                MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(ORGANIZE_REQUEST)
                        .build()
        ));
        agentLoop.run(messages, "");

        // 2. 语义整理正常结束后，确定性地记录这一批全部文件变化。
        runGit("add", "-A");
        runGit("-c", "user.name=Quillith", "-c", "user.email=quillith@local",
                "commit", "-m", "quillith: organize llmwiki");
    }

    /**
     * 在 llmwiki 根目录执行一条 Git 命令并检查退出码。
     *
     * @param arguments git 后面的参数
     * @throws IOException 命令无法执行、被中断或返回非零退出码
     */
    private void runGit(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(llmwikiRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("等待 Git 命令时被中断", exception);
        }
        if (exitCode != 0) {
            throw new IOException("Git 命令失败，exitCode=" + exitCode + "，output=" + output);
        }
    }

    /**
     * 关闭整理 Agent 持有的 Bash 进程和后台调度器。
     */
    @Override
    public void close() {
        bashTool.close();
        backgroundScheduler.close();
    }
}
