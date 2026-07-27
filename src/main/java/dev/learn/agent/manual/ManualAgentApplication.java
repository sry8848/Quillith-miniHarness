package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.hook.hooks.LargeOutputHook;
import dev.learn.agent.manual.hook.hooks.PermissionHook;
import dev.learn.agent.manual.hook.hooks.SessionSummaryHook;
import dev.learn.agent.manual.hook.hooks.TodoReminderHook;
import dev.learn.agent.manual.hook.hooks.ToolLoggingHook;
import dev.learn.agent.manual.hook.hooks.WorkspaceLoggingHook;
import dev.learn.agent.manual.skill.SkillRegistry;
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
     * 因此允许比单个子任务更多的模型调用。
     */
    private static final int MAX_PARENT_MODEL_CALLS =
            100;

    /*
     * 子 Agent 应处理边界清晰的子任务，
     * 30 次上限用于阻止委派任务陷入无限工具循环。
     */
    private static final int MAX_SUBAGENT_MODEL_CALLS =
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
         * 不保存某个 Agent 的消息历史，可以安全用于当前同步执行模型。
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
         * s06 当前同步执行，不存在两个 Agent 同时读取 Scanner 的问题。
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

        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(BASE_URL)
                        .build();

        /*
         * 子 Agent 使用独立系统提示词。
         *
         * “不要继续委派”用于指导模型行为；
         * 真正的递归防护来自 subagentToolRegistry 中不存在 task。
         */
        String subagentSystemPrompt =
                "You are a coding subagent working in "
                        + workspace
                        + ". Complete only the delegated task. "
                        + "Use the available tools when needed. "
                        + "Return a concise, factual conclusion. "
                        + "Do not attempt to delegate the task further.";

        AgentLoop subagentLoop =
                new AgentLoop(
                        client,
                        MODEL,
                        subagentSystemPrompt,
                        subagentToolRegistry,
                        subagentHookRegistry,
                        MAX_SUBAGENT_MODEL_CALLS
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

        String parentSystemPrompt =
                "You are a coding agent working in "
                        + workspace
                        + ". Before starting any multi-step task, "
                        + "use todo_write to plan your steps. "
                        + "Update todo statuses as you work. "
                        + "For complex, self-contained subtasks "
                        + "that require broad codebase exploration, "
                        + "use task to delegate the work. "
                        + "Use the available tools to complete the task."
                        + "\n\nAvailable skills:\n"
                        + skillRegistry.catalog()
                        + "\nWhen an available skill matches the "
                        + "user's task, call load_skill before "
                        + "completing the task.";

        AgentLoop agentLoop =
                new AgentLoop(
                        client,
                        MODEL,
                        parentSystemPrompt,
                        toolRegistry,
                        hookRegistry,
                        MAX_PARENT_MODEL_CALLS
                );


        /*
         * history 跨多次用户输入保留，
         * 模型才能记住之前的对话、工具调用和 TODO 更新。
         */
        List<MessageParam> history =
                new ArrayList<>();

        System.out.println(
                "s07 Skill Loading Agent"
        );

        System.out.println(
                "输入任务并回车，输入 q 或 exit 退出。"
        );

        try {
            while (true) {
                System.out.println();
                System.out.print("s07 >> ");

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

                String answer =
                        agentLoop.run(
                                history
                        );

                System.out.println(
                        "模型：" + answer
                );
            }
        } finally {
            client.close();
            scanner.close();
        }
    }
}
