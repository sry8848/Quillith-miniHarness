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
                "s11 Background Task Scheduler Agent"
        );
        System.out.println(
                "输入任务并回车，输入 q 或 exit 退出。"
        );


        // 2. 同一个 Scanner 持续读取输入，直到用户命令或 EOF 结束会话。
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

            // 3. 退出命令和本地 Memory 命令不进入 Agent Turn。
            if (command.isEmpty()
                    || "q".equalsIgnoreCase(command)
                    || "exit".equalsIgnoreCase(command)) {
                break;
            }

            if (isMemoryCommand(command)) {
                handleMemoryCommand(
                        command,
                        agentSession
                );
                continue;
            }

            try {
                // 4. 普通输入沿用同一个父 Agent，保留跨 Turn history。
                agentSession.submit(
                        query
                );
            } catch (AnthropicServiceException ignored) {
                // AgentSession 已完成诊断和历史回滚，交互模式继续等待用户下一条输入。
                continue;
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
