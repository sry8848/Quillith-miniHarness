package dev.learn.agent.manual.hook.hooks;

import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在工具执行后检查输出大小。
 */
public final class LargeOutputHook implements AgentHook {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    LargeOutputHook.class
            );

    /*
     * 这里只做教学用的字符数预警。
     * 字符数不等于模型实际消耗的 Token 数。
     */
    private static final int WARNING_THRESHOLD = 100_000;

    /**
     * 工具执行结束后，由 HookRegistry 调用。
     */
    @Override
    public HookEffect afterToolUse(
            ToolCall toolCall,
            String output
    ) {
        if (output.length() <= WARNING_THRESHOLD) {
            return HookEffect.proceed();
        }

        String warning =
                "工具 "
                        + toolCall.name()
                        + " 的输出过大，共 "
                        + output.length()
                        + " 个字符";

        LOGGER.warn(
                "PostToolUse：{}",
                warning
        );

        /*
         * 除了在控制台提醒用户，
         * 还把警告作为附加上下文交给模型。
         */
        return HookEffect.addContext(
                warning
        );
    }
}
