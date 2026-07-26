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
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import dev.learn.agent.manual.tool.tools.BashTool;
import dev.learn.agent.manual.tool.tools.EditFileTool;
import dev.learn.agent.manual.tool.tools.GlobTool;
import dev.learn.agent.manual.tool.tools.ReadFileTool;
import dev.learn.agent.manual.tool.entity.TodoState;
import dev.learn.agent.manual.tool.tools.TodoWriteTool;
import dev.learn.agent.manual.tool.tools.WriteFileTool;

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
         * TodoWriteTool 负责写入状态，
         * TodoReminderHook 负责读取状态。
         *
         * 两者必须持有同一个 TodoState 实例。
         */
        TodoState todoState =
                new TodoState();

        ToolRegistry toolRegistry =
                new ToolRegistry();

        toolRegistry.registerAll(
                new BashTool(
                        workspace,
                        bashExecutable
                ),
                new ReadFileTool(paths),
                new WriteFileTool(paths),
                new EditFileTool(paths),
                new GlobTool(paths),
                new TodoWriteTool(
                        todoState
                )
        );

        Scanner scanner =
                new Scanner(System.in);

        HookRegistry hookRegistry =
                new HookRegistry();

        hookRegistry.registerAll(
                new WorkspaceLoggingHook(
                        workspace
                ),
                new ToolLoggingHook(),
                new PermissionHook(
                        paths,
                        scanner
                ),
                new LargeOutputHook(),
                new TodoReminderHook(
                        todoState
                ),
                new SessionSummaryHook()
        );

        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(BASE_URL)
                        .build();

        String systemPrompt =
                "You are a coding agent working in "
                        + workspace
                        + ". Before starting any multi-step task, "
                        + "use todo_write to plan your steps. "
                        + "Update todo statuses as you work. "
                        + "Use the available tools to complete the task.";

        AgentLoop agentLoop =
                new AgentLoop(
                        client,
                        MODEL,
                        systemPrompt,
                        toolRegistry,
                        hookRegistry
                );

        /*
         * history 跨多次用户输入保留，
         * 模型才能记住之前的对话、工具调用和 TODO 更新。
         */
        List<MessageParam> history =
                new ArrayList<>();

        System.out.println(
                "s05 TodoWrite Agent"
        );

        System.out.println(
                "输入任务并回车，输入 q 或 exit 退出。"
        );

        try {
            while (true) {
                System.out.println();
                System.out.print("s05 >> ");

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
