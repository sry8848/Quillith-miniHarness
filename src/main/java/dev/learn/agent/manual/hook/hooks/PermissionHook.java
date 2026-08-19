package dev.learn.agent.manual.hook.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Scanner;

/**
 * 在工具执行前检查权限。
 */
public final class PermissionHook implements AgentHook {
    /*
     * 无论用户是否同意，都不允许执行的命令片段。
     *
     * 这里使用 List，是因为后面需要逐个检查：
     * 命令中是否包含某个危险片段。
     */
    private static final List<String> DENY_LIST = List.of(
            "rm -rf /",
            "sudo",
            "shutdown",
            "reboot",
            "mkfs",
            "dd if="
    );

    /*
     * 这些命令不是无条件禁止，
     * 但在真正执行前必须得到用户确认。
     */
    private static final List<String> APPROVAL_REQUIRED = List.of(
            "rm ",
            "> /etc/",
            "chmod 777"
    );

    /*
     * MCP 工具名使用 mcp__git__ 前缀，
     * 让权限规则可以区分本地工具和官方 Git Server 工具。
     */
    private static final String MCP_GIT_TOOL_PREFIX =
            "mcp__git__";

    /*
     * 官方 Git Server 当前明确提供的只读工具。
     * 未列出的 Git MCP 工具默认走人工确认，避免新工具悄悄获得写权限。
     */
    private static final Set<String> MCP_GIT_READ_ONLY_TOOLS = Set.of(
            "git_status",
            "git_diff_unstaged",
            "git_diff_staged",
            "git_diff",
            "git_log",
            "git_show",
            "git_branch"
    );

    /*
     * 与文件工具共享同一套路径解析规则。
     */
    private final WorkspacePathResolver paths;

    /*
     * Scanner 由程序入口创建后传入。
     *
     * PermissionHook 只使用它，
     * 不负责创建和关闭它。
     */
    private final Scanner scanner;

    /**
     * 创建权限 Hook。
     *
     * @param paths   与文件工具共享的路径解析器
     * @param scanner 用于读取用户审批结果
     */
    public PermissionHook(
            WorkspacePathResolver paths,
            Scanner scanner
    ) {
        this.paths =
                Objects.requireNonNull(
                        paths,
                        "WorkspacePathResolver 不能为空"
                );

        this.scanner =
                Objects.requireNonNull(
                        scanner,
                        "Scanner 不能为空"
                );
    }

    /**
     * 根据工具名称，把权限检查交给对应的权限规则。
     */
    @Override
    public HookEffect beforeToolUse(
            ToolCall toolCall
    ) {
        JsonNode input =
                toolCall.input();

        /*
         * beforeToolUse 只负责分发，
         * 具体规则分别放在对应的权限方法中。
         */
        if (toolCall.name().startsWith(MCP_GIT_TOOL_PREFIX)) {
            return checkGitMcpPermission(toolCall);
        }

        return switch (toolCall.name()) {
            case "bash" ->
                    checkBashPermission(input);

            case "write_file", "edit_file" ->
                    checkFileWritePermission(
                            toolCall,
                            input
                    );

            /*
             * read_file、glob 等工具暂时直接允许。
             */
            default ->
                    HookEffect.proceed();
        };
    }

    /**
     * 检查官方 Git MCP 工具权限。
     *
     * @param toolCall 带有 mcp__git__ 命名空间的工具调用
     * @return 只读工具直接放行，写工具或未知工具要求用户确认
     */
    private HookEffect checkGitMcpPermission(
            ToolCall toolCall
    ) {
        String remoteToolName =
                toolCall.name()
                        .substring(
                                MCP_GIT_TOOL_PREFIX.length()
                        );

        if (MCP_GIT_READ_ONLY_TOOLS.contains(remoteToolName)) {
            return HookEffect.proceed();
        }

        return askUser(
                "Git MCP 工具可能修改仓库",
                toolCall.name(),
                toolCall.input()
        );
    }

    /**
     * 检查 Bash 工具权限。
     */
    private HookEffect checkBashPermission(
            JsonNode input
    ) {
        JsonNode commandNode =
                input.get("command");

        /*
         * command 是模型生成的外部输入，
         * 必须确认它存在并且是字符串。
         */
        if (commandNode == null
                || !commandNode.isTextual()) {
            return HookEffect.block(
                    "Invalid bash input: command must be a string"
            );
        }

        String command =
                commandNode.textValue();

        /*
         * 第一关：硬拒绝名单。
         */
        for (String pattern : DENY_LIST) {
            if (command.contains(pattern)) {
                return HookEffect.block(
                        "Permission denied: '"
                                + pattern
                                + "' is on the deny list"
                );
            }
        }

        /*
         * 第二关：需要用户审批的命令规则。
         */
        for (String pattern : APPROVAL_REQUIRED) {
            if (command.contains(pattern)) {
                return askUser(
                        "检测到可能具有破坏性的命令",
                        "bash",
                        input
                );
            }
        }

        return HookEffect.proceed();
    }

    /**
     * 检查 write_file 和 edit_file 的写入权限。
     */
    private HookEffect checkFileWritePermission(
            ToolCall toolCall,
            JsonNode input
    ) {
        JsonNode pathNode =
                input.get("path");

        /*
         * 文件工具必须提供字符串类型的 path。
         */
        if (pathNode == null
                || !pathNode.isTextual()) {
            return HookEffect.block(
                    "Invalid file input: path must be a string"
            );
        }

        String pathText =
                pathNode.textValue();

        final Path targetPath;

        try {
            /*
             * 审批前使用与实际工具完全相同的路径规则。
             * 工具执行时仍会再次解析，防止审批后路径状态发生变化。
             */
            targetPath =
                    switch (toolCall.name()) {
                        case "write_file" ->
                                paths.resolveForWrite(
                                        pathText
                                );

                        case "edit_file" ->
                                paths.resolveExisting(
                                        pathText
                                );

                        default ->
                                throw new IllegalStateException(
                                        "不支持的文件写入工具："
                                                + toolCall.name()
                                );
                    };
        } catch (IOException
                 | InvalidPathException exception) {
            return HookEffect.block(
                    "Permission denied: "
                            + exception.getMessage()
            );
        }

        /*
         * 工作区内可以写入，但仍然必须让用户确认。
         */
        return askUser(
                "工具将修改工作区中的文件："
                        + targetPath,
                toolCall.name(),
                input
        );
    }

    /**
     * 在控制台展示工具调用并等待用户审批。
     *
     * Bash 和文件写入都会复用这段逻辑，
     * 因此现在提取为独立方法。
     */
    private synchronized HookEffect askUser(
            String reason,
            String toolName,
            JsonNode input
    ) {
        /*
         * 多个工具可以在虚拟线程中并发到达审批边界。
         * 串行读取共享 Scanner，避免两个提示争抢同一行用户输入。
         */
        System.out.println();
        System.out.println(reason);
        System.out.println(
                "工具：" + toolName
        );
        System.out.println(
                "参数：" + input
        );
        System.out.print(
                "是否允许执行？[y/N] "
        );

        String choice =
                scanner.nextLine()
                        .trim();

        /*
         * 明确输入 y 或 yes 才放行。
         */
        if ("y".equalsIgnoreCase(choice)
                || "yes".equalsIgnoreCase(choice)) {
            return HookEffect.proceed();
        }

        return HookEffect.block(
                "Permission denied by user"
        );
    }
}
