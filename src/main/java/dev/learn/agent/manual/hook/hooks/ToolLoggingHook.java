package dev.learn.agent.manual.hook;

import com.anthropic.models.messages.ToolUseBlock;

import java.util.Optional;

/**
 * 在工具执行前记录调用信息。
 */
public final class ToolLoggingHook implements AgentHook {

    /**
     * 输出模型准备调用的工具和参数。
     */
    @Override
    public Optional<String> beforeToolUse(
            ToolUseBlock toolUse
    ) {
        String input =
                toolUse._input()
                        .toString();

        /*
         * write_file 的参数可能包含完整文件内容。
         *
         * 如果直接输出全部参数，
         * 控制台可能被大量文本占满，因此只显示前 120 个字符。
         */
        String preview =
                input.length() <= 120
                        ? input
                        : input.substring(0, 120)
                          + "...";

        System.out.printf(
                "[HOOK] 准备调用工具：%s(%s)%n",
                toolUse.name(),
                preview
        );

        /*
         * 日志 Hook 只负责观察，不阻止工具执行。
         */
        return Optional.empty();
    }
}