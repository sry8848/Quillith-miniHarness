package dev.learn.agent.manual.cli;

import java.util.StringJoiner;

/**
 * 保存应用启动时解析出的命令行选项。
 */
public record ApplicationOptions(
        Mode mode,
        boolean memoryEnabled,
        String instruction,
        String resumeSessionId
) {

    /**
     * 定义当前进程接收一次任务还是持续接收交互输入。
     */
    public enum Mode {
        INTERACTIVE,
        EXEC,
        HARBOR
    }

    /**
     * 解析当前版本支持的启动参数。
     *
     * @param args main 方法收到的命令行参数
     * @return 已校验的应用选项
     */
    public static ApplicationOptions parse(
            String[] args
    ) {
        // 1. exec 只在第一个参数出现时作为子命令，避免把普通参数误判为执行模式。
        if (args.length > 0
                && "exec".equals(args[0])) {
            return parseExec(args);
        }

        // 2. harbor 子命令只切换机器输入边界，Agent Core 仍复用现有实现。
        if (args.length > 0
                && "harbor".equals(args[0])) {
            return parseHarbor(args);
        }

        // 3. 未指定子命令时保持原有交互模式和记忆参数行为。
        return parseInteractive(args);
    }

    /**
     * 解析 Harbor 机器输入入口支持的现有记忆参数。
     *
     * @param args main 方法收到的完整命令行参数
     * @return Harbor 输入模式应用选项
     */
    private static ApplicationOptions parseHarbor(
            String[] args
    ) {
        // 1. Harbor 与现有入口保持相同的 Memory 默认值。
        boolean memoryEnabled = true;

        // 2. harbor 之后只接受已有 Memory 开关，instruction 由 FIFO 提供。
        for (int index = 1;
             index < args.length;
             index++) {
            ParsedOption option =
                    parseOption(
                            args[index]
                    );
            if (option.resumeSessionId() != null) {
                throw new IllegalArgumentException(
                        "harbor 不支持 --resume"
                );
            }
            memoryEnabled =
                    option.memoryEnabled(
                            memoryEnabled
                    );
        }

        return new ApplicationOptions(
                Mode.HARBOR,
                memoryEnabled,
                "",
                null
        );
    }

    /**
     * 解析交互模式支持的现有记忆参数。
     *
     * @param args main 方法收到的命令行参数
     * @return 交互模式应用选项
     */
    private static ApplicationOptions parseInteractive(
            String[] args
    ) {
        // 1. 交互模式保持 Memory 默认开启。
        boolean memoryEnabled = true;
        String resumeSessionId = null;

        // 2. Interactive 不接受位置参数，只复用原有 Memory 开关。
        for (String argument : args) {
            ParsedOption option =
                    parseOption(
                            argument
                    );
            memoryEnabled =
                    option.memoryEnabled(
                            memoryEnabled
                    );
            resumeSessionId =
                    option.resumeSessionId(
                            resumeSessionId
                    );
        }

        // 3. Interactive 没有单次 instruction，输入仍由 Scanner 提供。
        return new ApplicationOptions(
                Mode.INTERACTIVE,
                memoryEnabled,
                "",
                resumeSessionId
        );
    }

    /**
     * 解析 exec 子命令的记忆参数和单次任务文本。
     *
     * @param args main 方法收到的完整命令行参数
     * @return 单次执行模式应用选项
     */
    private static ApplicationOptions parseExec(
            String[] args
    ) {
        boolean memoryEnabled = true;
        String resumeSessionId = null;
        int instructionStart = 1;

        // 1. 只把 instruction 之前的参数视为选项，instruction 内容保持原样进入 Agent。
        while (instructionStart < args.length
                && args[instructionStart].startsWith("--")) {
            ParsedOption option =
                    parseOption(
                            args[instructionStart]
                    );
            memoryEnabled =
                    option.memoryEnabled(
                            memoryEnabled
                    );
            resumeSessionId =
                    option.resumeSessionId(
                            resumeSessionId
                    );
            instructionStart++;
        }

        // 2. exec 必须显式提供任务，避免启动一个什么也不执行的非交互进程。
        if (instructionStart >= args.length) {
            throw new IllegalArgumentException(
                    "exec 缺少 instruction"
            );
        }

        // 3. Shell 已完成引号解析，这里只需把剩余参数按空格还原成一条任务文本。
        StringJoiner instruction =
                new StringJoiner(" ");
        for (int index = instructionStart;
             index < args.length;
             index++) {
            instruction.add(args[index]);
        }

        String instructionText =
                instruction.toString();
        if (instructionText.isBlank()) {
            throw new IllegalArgumentException(
                    "exec 缺少 instruction"
            );
        }

        return new ApplicationOptions(
                Mode.EXEC,
                memoryEnabled,
                instructionText,
                resumeSessionId
        );
    }

    /**
     * 解析单个命令行选项。
     *
     * @param argument 当前命令行参数
     * @return 当前选项对应用选项的修改
     */
    private static ParsedOption parseOption(
            String argument
    ) {
        if ("--memory=on".equals(argument)) {
            return new ParsedOption(
                    Boolean.TRUE,
                    null
            );
        }

        if ("--memory=off".equals(argument)) {
            return new ParsedOption(
                    Boolean.FALSE,
                    null
            );
        }

        if (argument.startsWith("--resume=")) {
            String sessionId =
                    argument.substring(
                            "--resume=".length()
                    );
            if (sessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "--resume 缺少 sessionId"
                );
            }
            return new ParsedOption(
                    null,
                    sessionId
            );
        }

        throw new IllegalArgumentException(
                "未知命令行参数："
                        + argument
        );
    }

    /**
     * 表达一个 CLI 选项对当前解析状态的最小修改。
     */
    private record ParsedOption(
            Boolean memoryEnabled,
            String resumeSessionId
    ) {

        private boolean memoryEnabled(
                boolean current
        ) {
            return memoryEnabled == null
                    ? current
                    : memoryEnabled;
        }

        private String resumeSessionId(
                String current
        ) {
            return resumeSessionId == null
                    ? current
                    : resumeSessionId;
        }
    }
}
