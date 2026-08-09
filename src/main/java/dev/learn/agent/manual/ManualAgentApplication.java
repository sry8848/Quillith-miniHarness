package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.hook.hooks.LargeOutputHook;
import dev.learn.agent.manual.hook.hooks.PermissionHook;
import dev.learn.agent.manual.hook.hooks.SessionSummaryHook;
import dev.learn.agent.manual.hook.hooks.TodoReminderHook;
import dev.learn.agent.manual.hook.hooks.ToolLoggingHook;
import dev.learn.agent.manual.hook.hooks.WorkspaceLoggingHook;
import dev.learn.agent.manual.memory.MemoryConsolidator;
import dev.learn.agent.manual.memory.MemoryDialogueFormatter;
import dev.learn.agent.manual.memory.MemoryEntry;
import dev.learn.agent.manual.memory.MemoryExtractor;
import dev.learn.agent.manual.memory.MemoryRecallService;
import dev.learn.agent.manual.memory.MemoryRepository;
import dev.learn.agent.manual.memory.MemorySelector;
import dev.learn.agent.manual.skill.SkillRegistry;
import dev.learn.agent.manual.systemprompt.IdentitySystemPromptProvider;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.RuntimeContext;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.systemprompt.WorkspaceSystemPromptProvider;
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.tool.tools.*;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import dev.learn.agent.manual.tool.entity.TodoState;

import java.io.IOException;
import java.nio.file.Path;
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

    private static final String BASE_URL =
            "https://dashscope.aliyuncs.com/apps/anthropic";

    private static final String MODEL =
            "qwen3.5-flash";

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

    /**
     * 启动交互式 Coding Agent。
     *
     * @param args 命令行参数，当前版本暂不使用
     */
    public static void main(
            String[] args
    ) throws IOException {
        Path workspace =
                Path.of("")
                        .toRealPath();

        // 保存 System Prompt Provider 当前能够读取的真实程序状态。
        RuntimeContext runtimeContext =
                new RuntimeContext(
                        workspace
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

        /*
         * 所有文件工具共享一个路径解析器，
         * 因而具有完全相同的工作区边界。
         */
        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        workspace
                );

        /*
         * 技能目录属于 Agent 的启动配置。
         *
         * 注册表只在启动时扫描一次，
         * 使 system prompt 和 load_skill 使用完全相同的技能快照。
         */
        SkillRegistry skillRegistry =
                new SkillRegistry(
                        workspace.resolve(
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
         * 父子 Agent 操作同一个工作区，
         * 因此共享同一批基础工具实例。
         *
         * 这些工具只保存不可变的工作区依赖，
         * 不保存某个 Agent 的消息历史；修改类工具由各轮调度器独占执行。
         */
        BashTool bashTool =
                new BashTool(
                        workspace,
                        bashExecutable
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
                bashTool,
                readFileTool,
                writeFileTool,
                editFileTool,
                globTool,
                new TodoWriteTool(
                        todoState
                ),
                new LoadSkillTool(
                        skillRegistry
                )
        );

        /*
         * 子 Agent 使用独立的工具白名单。
         *
         * 不提供 todo_write，避免覆盖主会话规划状态；
         * 不提供 task，从能力层阻止递归委派。
         */
        ToolRegistry subagentToolRegistry =
                new ToolRegistry();

        subagentToolRegistry.registerAll(
                bashTool,
                readFileTool,
                writeFileTool,
                editFileTool,
                globTool
        );

        Scanner scanner =
                new Scanner(System.in);

        /*
         * 父子 Agent 共用同一个终端和工作区，
         * 因此共享工具日志、权限检查和大输出处理 Hook。
         *
         * PermissionHook 会串行化终端确认，避免并发工具同时读取 Scanner。
         */
        ToolLoggingHook toolLoggingHook =
                new ToolLoggingHook();

        PermissionHook permissionHook =
                new PermissionHook(
                        paths,
                        scanner
                );

        LargeOutputHook largeOutputHook =
                new LargeOutputHook();

        /*
         * 父 Agent 拥有完整的主会话 Hook。
         */
        HookRegistry hookRegistry =
                new HookRegistry();

        hookRegistry.registerAll(
                new WorkspaceLoggingHook(
                        workspace
                ),
                toolLoggingHook,
                permissionHook,
                largeOutputHook,
                new TodoReminderHook(
                        todoState
                ),
                new SessionSummaryHook()
        );

        /*
         * 子 Agent 仍然经过权限和输出治理，
         * 但不继承与主会话状态绑定的 Hook。
         *
         * 特别是不注册 TodoReminderHook，
         * 因为子 Agent 没有 todo_write 工具。
         */
        HookRegistry subagentHookRegistry =
                new HookRegistry();

        subagentHookRegistry.registerAll(
                toolLoggingHook,
                permissionHook,
                largeOutputHook
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
         * [核心] 记忆仓库提供候选和正文，Selector 负责相关性判断，
         * RecallService 将两者组合成本轮可以临时注入的上下文。
         */
        MemoryRepository memoryRepository =
                new MemoryRepository(
                        paths
                );

        MemorySelector memorySelector =
                new MemorySelector(
                        client,
                        MODEL
                );

        MemoryRecallService memoryRecallService =
                new MemoryRecallService(
                        memoryRepository,
                        memorySelector
                );

        /*
         * [核心] Extractor 在完整用户回合结束后，
         * 从压缩前对话快照中生成长期记忆候选。
         *
         * 它不直接写仓库，保存动作仍由应用层明确编排。
         */
        MemoryExtractor memoryExtractor =
                new MemoryExtractor(
                        client,
                        MODEL
                );

        /*
         * Consolidator 只在记忆集合发生写入后由应用层触发，
         * 内部再通过数量阈值判断是否值得调用模型整理。
         */
        MemoryConsolidator memoryConsolidator =
                new MemoryConsolidator(
                        client,
                        MODEL,
                        memoryRepository
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
                        subagentHookRegistry,
                        contextManager,
                        MAX_SUBAGENT_MODEL_ROUNDS
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
         * 父 Agent 的能力说明分别由身份、工作区和 Skill 模块提供。
         * Todo 和 Task 的使用说明已经属于各自的 Tool description。
         */
        SystemPromptManager parentSystemPromptManager =
                new SystemPromptManager(
                        List.of(
                                IdentitySystemPromptProvider
                                        .parent(),
                                new WorkspaceSystemPromptProvider(),
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
                        hookRegistry,
                        contextManager,
                        MAX_PARENT_MODEL_ROUNDS
                );


        /*
         * history 跨多次用户输入保留，
         * 模型才能记住之前的对话、工具调用和 TODO 更新。
         */
        List<MessageParam> history =
                new ArrayList<>();

        System.out.println(
                "s10 System Prompt Agent"
        );

        System.out.println(
                "输入任务并回车，输入 q 或 exit 退出。"
        );

        try {
            while (true) {
                System.out.println();
                System.out.print("s10 >> ");

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
                 * [核心] 在主 Agent 第一次处理用户问题前召回相关记忆。
                 *
                 * 返回内容稍后只进入本轮模型请求，
                 * 不会作为正式 MessageParam 写入 history。
                 */
                String recalledMemories =
                        memoryRecallService.recall(
                                query
                        );

                history.add(
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.USER
                                )
                                .content(query)
                                .build()
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
                        List.copyOf(
                                history
                        );

                /*
                 * [核心] 文本增量到达时立即展示并刷新终端，
                 * 完整响应仍由 AgentLoop 累积后写入历史。
                 */
                System.out.print(
                        "模型："
                );

                agentLoop.run(
                        history,
                        recalledMemories,
                        text -> {
                            System.out.print(
                                    text
                            );

                            System.out.flush();
                        }
                );

                // 当前回答流结束后换行，避免后续记忆状态紧跟正文末尾。
                System.out.println();

                // [核心] 把压缩前消息转换为提取器需要的纯文本对话。
                String extractionDialogue =
                        MemoryDialogueFormatter.format(
                                memoryExtractionSnapshot
                        );

                /*
                 * [核心] 根据原始对话和已有记忆目录，
                 * 生成已经通过协议校验的长期记忆候选。
                 */
                List<MemoryEntry> extractedMemories =
                        memoryExtractor.extract(
                                extractionDialogue,
                                memoryRepository.list()
                        );

                /*
                 * [核心] 逐条发布候选，并在每次保存后重建索引。
                 *
                 * 当前没有批次事务；中途失败可能只保存前半批，
                 * 该可靠性缺口已经记录在 s09 待办中。
                 */
                for (
                        MemoryEntry entry
                        : extractedMemories
                ) {
                    memoryRepository.save(
                            entry
                    );
                }

                // 保存成功时显示本轮实际产生的记忆数量。
                if (!extractedMemories.isEmpty()) {
                    System.out.println(
                            "[Memory：已保存 "
                                    + extractedMemories.size()
                                    + " 条记忆]"
                    );

                    /*
                     * [核心] 只有本轮实际写入了记忆，才检查是否需要整理。
                     *
                     * [边界：目录没有变化却在每轮按数量触发 →
                     * 稳定的十条有效记忆会导致重复模型调用和持续成本]
                     */
                    List<MemoryEntry> consolidatedMemories =
                            memoryConsolidator
                                    .consolidateIfNeeded();

                    // 整理器返回空列表表示当前尚未达到数量阈值。
                    if (!consolidatedMemories.isEmpty()) {
                        System.out.println(
                                "[Memory：整理后保留 "
                                        + consolidatedMemories.size()
                                        + " 条记忆]"
                        );
                    }
                }

            }
        } finally {
            client.close();
            scanner.close();
        }
    }
}
