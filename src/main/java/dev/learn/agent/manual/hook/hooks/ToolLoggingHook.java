package dev.learn.agent.manual.hook.hooks;

import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在工具执行前记录调用信息。
 */
public final class ToolLoggingHook implements AgentHook {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    ToolLoggingHook.class
            );

    /**
     * 输出模型准备调用的工具和参数。
     */
    @Override
    public HookEffect beforeToolUse(
            ToolCall toolCall
    ) {
        String input =
                toolCall.input()
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

        LOGGER.debug(
                "PreToolUse：{}({})",
                toolCall.name(),
                preview
        );

        /*
         * 日志 Hook 只负责观察，不阻止工具执行。
         */
        return HookEffect.proceed();
    }
}
