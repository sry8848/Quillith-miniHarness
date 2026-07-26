package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 手写 Agent 的模型调用和工具执行循环。
 */
public final class AgentLoop {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    AgentLoop.class
            );

    private static final long MAX_TOKENS =
            4_096;

    private final AnthropicClient client;

    private final String model;

    private final String systemPrompt;

    private final ToolRegistry toolRegistry;

    private final HookRegistry hookRegistry;

    public AgentLoop(
            AnthropicClient client,
            String model,
            String systemPrompt,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry
    ) {
        this.client =
                Objects.requireNonNull(
                        client,
                        "AnthropicClient 不能为空"
                );

        this.model =
                Objects.requireNonNull(
                        model,
                        "Model 不能为空"
                );

        this.systemPrompt =
                Objects.requireNonNull(
                        systemPrompt,
                        "SystemPrompt 不能为空"
                );

        this.toolRegistry =
                Objects.requireNonNull(
                        toolRegistry,
                        "ToolRegistry 不能为空"
                );

        this.hookRegistry =
                Objects.requireNonNull(
                        hookRegistry,
                        "HookRegistry 不能为空"
                );
    }

    /**
     * 持续调用模型，直到模型不再请求工具。
     *
     * @param messages 可修改的会话消息历史
     * @return 当前任务最终 assistant 回复中的文本结论
     */
    public String run(
            List<MessageParam> messages
    ) {
        while (true) {
            /*
             * 每次构造模型请求前，先让 Hook 根据当前消息历史
             * 判断是否需要追加动态上下文。
             */
            HookEffect beforeModelEffect =
                    hookRegistry.triggerBeforeModelCall(
                            messages
                    );

            if (!beforeModelEffect.additionalContexts()
                    .isEmpty()) {
                /*
                 * 多个 Hook 的附加上下文合并成一条隐藏提醒，
                 * 避免每个 Hook 分别制造一条 user 消息。
                 */
                String additionalContext =
                        String.join(
                                "\n\n",
                                beforeModelEffect.additionalContexts()
                        );

                messages.add(
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.USER
                                )
                                .content(
                                        "<system-reminder>\n"
                                                + additionalContext
                                                + "\n</system-reminder>"
                                )
                                .build()
                );

                LOGGER.debug(
                        "BeforeModelCall：已追加 {} 段上下文",
                        beforeModelEffect
                                .additionalContexts()
                                .size()
                );
            }

            /*
             * 把完整消息历史和工具定义发送给模型。
             */
            Message response =
                    client.messages()
                            .create(
                                    createRequest(
                                            messages
                                    )
                            );

            MessageParam assistantMessage =
                    MessageParam.builder()
                            /*
                             * Messages API 返回的 Message
                             * 按协议一定属于 assistant。
                             */
                            .role(
                                    MessageParam.Role.ASSISTANT
                            )
                            /*
                             * 模型返回的是响应内容块 ContentBlock，
                             * 消息历史需要的是请求内容块 ContentBlockParam。
                             */
                            .contentOfBlockParams(
                                    response.content()
                                            .stream()
                                            .map(
                                                    ContentBlock::toParam
                                            )
                                            .toList()
                            )
                            .build();

            /*
             * 模型回复中的普通文本，
             */
            messages.add(
                    assistantMessage
            );


            /*
             * 不依赖某个特定供应商的 stop_reason 字符串，
             * 直接检查回复中是否存在 tool_use 内容块。
             */
            boolean hasToolUse =
                    response.content()
                            .stream()
                            .anyMatch(
                                    ContentBlock::isToolUse
                            );

            /*
             * 没有工具调用，说明模型准备结束本轮任务。
             */
            if (!hasToolUse) {
                HookEffect stopEffect =
                        hookRegistry.triggerStop(
                                messages
                        );

                /*
                 * Stop Hook 可以返回一条新消息，
                 * 要求模型继续处理。
                 */
                if (stopEffect.decision()
                        == HookEffect.Decision.BLOCK) {
                    String continuationMessage =
                            stopEffect.reason();

                    if (!stopEffect.additionalContexts()
                            .isEmpty()) {
                        continuationMessage +=
                                "\n\nHook 追加上下文：\n"
                                        + String.join(
                                                "\n",
                                                stopEffect.additionalContexts()
                                        );
                    }

                    messages.add(
                            MessageParam.builder()
                                    .role(
                                            MessageParam.Role.USER
                                    )
                                    .content(
                                            continuationMessage
                                    )
                                    .build()
                    );

                    continue;
                }

                /*
                 * 只返回最终 assistant 回复中的文本内容。
                 *
                 * 子 Agent 后续会把这个字符串作为 task 的工具结果返回，
                 * 不会把自己的完整消息历史加入父 Agent 上下文。
                 */
                return String.join(
                        "\n",
                        response.content()
                                .stream()
                                .filter(
                                        ContentBlock::isText
                                )
                                .map(
                                        block -> block
                                                .asText()
                                                .text()
                                )
                                .toList()
                );
            }

            /*
             * 一次模型回复可能同时请求多个工具，
             * 所以这里需要收集多个 tool_result。
             */
            List<ContentBlockParam> toolResults =
                    new ArrayList<>();

            for (ContentBlock block
                    : response.content()) {
                if (!block.isToolUse()) {
                    continue;
                }

                ToolUseBlock toolUse =
                        block.asToolUse();

                /*
                 * 转换成项目内部的 ToolCall 后，
                 * Hook 才能真正替换工具输入。
                 */
                ToolCall toolCall =
                        ToolCall.from(
                                toolUse
                        );

                /*
                 * 工具执行前依次触发：
                 *
                 * ToolLoggingHook
                 * PermissionHook
                 */
                HookEffect beforeEffect =
                        hookRegistry.triggerBeforeToolUse(
                                toolCall
                        );

                String output;

                HookEffect combinedEffect =
                        beforeEffect;

                if (beforeEffect.decision()
                        == HookEffect.Decision.BLOCK) {
                    /*
                     * 权限拒绝时不执行工具，
                     * 但仍要把拒绝原因作为 tool_result 返回模型。
                     */
                    output =
                            beforeEffect.reason();
                } else {
                    ToolCall effectiveToolCall =
                            beforeEffect.updatedInput() == null
                                    ? toolCall
                                    : toolCall.withInput(
                                            beforeEffect.updatedInput()
                                    );

                    output =
                            toolRegistry.execute(
                                    effectiveToolCall
                            );

                    /*
                     * 只有真正执行过的工具，
                     * 才触发 PostToolUse Hook。
                     */
                    HookEffect afterEffect =
                            hookRegistry.triggerAfterToolUse(
                                    effectiveToolCall,
                                    output
                            );

                    combinedEffect =
                            beforeEffect.and(
                                    afterEffect
                            );

                    if (afterEffect.updatedOutput() != null) {
                        output =
                                afterEffect.updatedOutput();
                    }
                }

                /*
                 * 附加上下文不会冒充工具的原始输出，
                 * 使用明确标题与工具结果分隔后再交给模型。
                 */
                if (!combinedEffect.additionalContexts()
                        .isEmpty()) {
                    output +=
                            "\n\nHook 追加上下文：\n"
                                    + String.join(
                                            "\n",
                                            combinedEffect.additionalContexts()
                                    );
                }

                /*
                 * toolUseId 告诉模型：
                 *
                 * 这个执行结果属于哪一次工具请求。
                 *
                 * 同一轮存在多个工具调用时，
                 * 不能只依靠工具名称判断对应关系。
                 */
                ToolResultBlockParam toolResult =
                        ToolResultBlockParam.builder()
                                .toolUseId(
                                        toolUse.id()
                                )
                                .content(output)
                                .build();

                toolResults.add(
                        ContentBlockParam.ofToolResult(
                                toolResult
                        )
                );
            }

            /*
             * Anthropic 协议要求：
             *
             * assistant 发出 tool_use，
             * user 再通过 tool_result 返回执行结果。
             */
            MessageParam resultMessage =
                    MessageParam.builder()
                            .role(
                                    MessageParam.Role.USER
                            )
                            .contentOfBlockParams(
                                    toolResults
                            )
                            .build();

            messages.add(
                    resultMessage
            );

            /*
             * 回到 while 开头。
             *
             * 下一次请求会把工具结果连同完整历史再次发送给模型。
             */
        }
    }

    /**
     * 构造一次模型请求。
     *
     * 这是模型 API 边界，因此单独成为方法。
     */
    private MessageCreateParams createRequest(
            List<MessageParam> messages
    ) {
        return MessageCreateParams.builder()
                .model(model)//设置当前使用的模型名称。
                .maxTokens(MAX_TOKENS)//限制模型一次回复最多生成多少 Token。
                .system(systemPrompt)//设置 Agent 的系统提示词。
                .messages(messages)//发送当前完整会话历史。
                .tools(toolRegistry.definitions())//告诉模型当前可以调用哪些工具。
                .build();//完成请求对象构造。
    }

}
