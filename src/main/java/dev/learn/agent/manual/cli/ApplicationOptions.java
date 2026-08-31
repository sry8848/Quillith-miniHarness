package dev.learn.agent.manual.cli;

import java.util.Objects;

/**
 * 保存应用启动时解析出的命令行选项。
 */
public record ApplicationOptions(
        boolean memoryEnabled
) {

    /**
     * 解析当前版本支持的启动参数。
     *
     * @param args main 方法收到的命令行参数
     * @return 已校验的应用选项
     */
    public static ApplicationOptions parse(
            String[] args
    ) {
        Objects.requireNonNull(
                args,
                "命令行参数不能为空"
        );

        boolean memoryEnabled = true;

        for (String argument : args) {
            if ("--memory=on".equals(argument)) {
                memoryEnabled = true;
                continue;
            }

            if ("--memory=off".equals(argument)) {
                memoryEnabled = false;
                continue;
            }

            throw new IllegalArgumentException(
                    "未知命令行参数："
                            + argument
            );
        }

        return new ApplicationOptions(
                memoryEnabled
        );
    }
}
