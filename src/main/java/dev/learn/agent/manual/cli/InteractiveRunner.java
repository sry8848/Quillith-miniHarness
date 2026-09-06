package dev.learn.agent.manual.cli;

import com.anthropic.errors.AnthropicServiceException;
import dev.learn.agent.manual.AgentSession;

import java.io.IOException;
import java.util.Objects;
import java.util.Scanner;

/**
 * 从终端持续读取用户输入，并把普通消息提交给同一个 AgentSession。
 */
public final class InteractiveRunner {

    private final Scanner scanner;

    /**
     * 创建使用指定终端输入的交互 Runner。
     */
    public InteractiveRunner(
            Scanner scanner
    ) {
        this.scanner =
                Objects.requireNonNull(
                        scanner,
                        "Scanner 不能为空"
                );
    }

    /**
     * 保持当前命令行行为运行交互循环。
     */
    public void run(
            AgentSession agentSession
    ) throws IOException {
        Objects.requireNonNull(
                agentSession,
                "AgentSession 不能为空"
        );

        // 1. Interactive 独有的 banner 和输入说明保持原样。
        System.out.println(
                "Quillith"
        );
        System.out.println(
                "输入任务并回车，输入 q 或 exit 退出。"
        );


        // 2. 同一个 Scanner 持续读取输入，直到用户命令或 EOF 结束会话。
        while (true) {
            System.out.println();
            System.out.print("Quillith >> ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String query =
                    scanner.nextLine();
            String command =
                    query.trim();

            // 3. 退出命令和本地 Memory 命令不进入 Agent Turn。
            if (command.isEmpty()
                    || "q".equalsIgnoreCase(command)
                    || "exit".equalsIgnoreCase(command)) {
                break;
            }

            // 3. Session 命令只读取或恢复本地状态，不作为模型输入。
            if ("/session".equalsIgnoreCase(command)) {
                printSessions(agentSession);
                continue;
            }

            if ("/resume".equalsIgnoreCase(command)
                    || command.regionMatches(true, 0, "/resume ", 0, "/resume ".length())) {
                handleResume(command, agentSession);
                continue;
            }

            // 4. Memory 命令保持既有本地控制语义。
            if (isMemoryCommand(command)) {
                handleMemoryCommand(
                        command,
                        agentSession
                );
                continue;
            }

            try {
                // 5. 普通输入沿用同一个父 Agent，保留跨 Turn history。
                agentSession.submit(
                        query
                );
            } catch (AnthropicServiceException ignored) {
                throw ignored;
            }
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
     * 打印当前 workspace 可访问的持久化 Session 摘要。
     */
    private static void printSessions(AgentSession agentSession) {
        // 1. 空列表保持明确提示，避免用户误以为当前内存 Session 已经持久化。
        if (agentSession.listSessions().isEmpty()) {
            System.out.println("[Session：暂无已持久化会话]");
            return;
        }

        // 2. Store 已按更新时间排序，Runner 只负责展示。
        for (var session : agentSession.listSessions()) {
            System.out.println(session.sessionId() + " | " + session.updatedAt()
                    + " | " + session.firstUserMessage());
        }
    }

    /**
     * 解析并执行 `/resume <sessionId>`。
     */
    private static void handleResume(String command, AgentSession agentSession) {
        String[] parts = command.split("\\s+");
        if (parts.length != 2 || parts[1].isBlank()) {
            System.out.println("用法：/resume <sessionId>");
            return;
        }

        try {
            // 1. resume 只加载和封口，不触发新的模型 Turn。
            agentSession.resume(parts[1]);
            System.out.println("[Session 已恢复：" + parts[1] + "]");
        } catch (IllegalArgumentException exception) {
            System.out.println("[Session 恢复失败：" + exception.getMessage() + "]");
        }
    }

    /**
     * 执行当前支持的交互式记忆命令。
     */
    private static void handleMemoryCommand(
            String command,
            AgentSession agentSession
    ) {
        String argument =
                command.length()
                        == "/memory".length()
                        ? ""
                        : command.substring(
                        "/memory".length()
                ).trim();

        if ("on".equalsIgnoreCase(argument)) {
            agentSession.setMemoryEnabled(
                    true
            );
            System.out.println(
                    "[Memory：已开启]"
            );
            return;
        }

        if ("off".equalsIgnoreCase(argument)) {
            agentSession.setMemoryEnabled(
                    false
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
                            + (agentSession.memoryEnabled()
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
}
