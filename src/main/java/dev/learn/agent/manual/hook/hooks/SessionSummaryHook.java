package dev.learn.agent.manual.hook.hooks;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Agent 准备停止时，输出本轮会话摘要。
 */
public final class SessionSummaryHook implements AgentHook {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    SessionSummaryHook.class
            );

    /**
     * 统计消息历史中的 tool_result 数量。
     */
    @Override
    public HookEffect onStop(
            List<MessageParam> messages
    ) {
        int toolCallCount = 0;

        for (MessageParam message : messages) {
            /*
             * MessageParam 的 content 可能只是普通字符串，
             * 也可能是一组结构化内容块。
             */
            if (!message.content().isBlockParams()) {
                continue;
            }

            for (ContentBlockParam block
                    : message.content().asBlockParams()) {
                if (block.isToolResult()) {
                    toolCallCount++;
                }
            }
        }

        LOGGER.info(
                "Stop：本轮会话共执行 {} 次工具调用",
                toolCallCount
        );

        /*
         * CONTINUE 表示摘要输出完成后允许 Agent 正常停止。
         */
        return HookEffect.proceed();
    }
}
