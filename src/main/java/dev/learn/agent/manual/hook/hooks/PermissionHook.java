package dev.learn.agent.manual.hook;

import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
     * Agent 允许操作的工作区根目录。
     */
    private final Path workspace;

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
     * @param workspace Agent 允许操作的工作区
     * @param scanner   用于读取用户审批结果
     */
    public PermissionHook(
            Path workspace,
            Scanner scanner
    ) {
        /*
         * 转换为绝对路径并消除其中的 . 和 ..，
         * 方便后面判断目标路径是否仍在工作区内。
         */
        this.workspace =
                Objects.requireNonNull(
                                workspace,
                                "Workspace 不能为空"
                        )
                        .toAbsolutePath()
                        .normalize();

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
    public Optional<String> beforeToolUse(
            ToolUseBlock toolUse
    ) {
        /*
         * 模型生成的工具参数本质上是一段 JSON。
         */
        JsonNode input =
                toolUse._input()
                        .convert(JsonNode.class);

        /*
         * beforeToolUse 只负责分发，
         * 具体规则分别放在对应的权限方法中。
         */
        return switch (toolUse.name()) {
            case "bash" ->
                    checkBashPermission(input);

            case "write_file", "edit_file" ->
                    checkFileWritePermission(
                            toolUse,
                            input
                    );

            /*
             * read_file、glob 等工具暂时直接允许。
             */
            default ->
                    Optional.empty();
        };
    }

    /**
     * 检查 Bash 工具权限。
     */
    private Optional<String> checkBashPermission(
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
            return Optional.of(
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
                return Optional.of(
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

        return Optional.empty();
    }

    /**
     * 检查 write_file 和 edit_file 的写入权限。
     */
    private Optional<String> checkFileWritePermission(
            ToolUseBlock toolUse,
            JsonNode input
    ) {
        JsonNode pathNode =
                input.get("path");

        /*
         * 文件工具必须提供字符串类型的 path。
         */
        if (pathNode == null
                || !pathNode.isTextual()) {
            return Optional.of(
                    "Invalid file input: path must be a string"
            );
        }

        String pathText =
                pathNode.textValue();

        final Path targetPath;

        try {
            /*
             * 相对路径会以 workspace 为起点解析。
             *
             * normalize() 会处理路径中的 . 和 ..。
             */
            targetPath =
                    workspace.resolve(pathText)
                            .normalize();
        } catch (InvalidPathException exception) {
            /*
             * 例如路径中含有当前操作系统不允许的字符。
             */
            return Optional.of(
                    "Invalid file path: " + pathText
            );
        }

        /*
         * startsWith(workspace) 为 false，
         * 表示目标路径已经逃离工作区。
         *
         * 工作区外写入属于硬边界，不允许用户审批放行。
         */
        if (!targetPath.startsWith(workspace)) {
            return Optional.of(
                    "Permission denied: path escapes workspace"
            );
        }

        /*
         * 工作区内可以写入，但仍然必须让用户确认。
         */
        return askUser(
                "工具将修改工作区中的文件",
                toolUse.name(),
                input
        );
    }

    /**
     * 在控制台展示工具调用并等待用户审批。
     *
     * Bash 和文件写入都会复用这段逻辑，
     * 因此现在提取为独立方法。
     */
    private Optional<String> askUser(
            String reason,
            String toolName,
            JsonNode input
    ) {
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
            return Optional.empty();
        }

        return Optional.of(
                "Permission denied by user"
        );
    }
}