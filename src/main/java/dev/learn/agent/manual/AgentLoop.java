package dev.learn.agent.manual;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.RuntimeContext;
import dev.learn.agent.manual.systemprompt.SystemPrompt;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 手写 Agent 的模型调用和工具执行循环。
 */
public final class AgentLoop {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    AgentLoop.class
            );

    private static final long DEFAULT_MAX_TOKENS =
            8_000;

    private static final long ESCALATED_MAX_TOKENS =
            64_000;

    private static final int MAX_OUTPUT_CONTINUATIONS =
            3;

    private static final String OUTPUT_CONTINUATION_PROMPT =
            "Output token limit hit. Resume directly — "
                    + "no apology, no recap. "
                    + "Pick up mid-thought.";

    private static final String TOOL_CALL_RETRY_PROMPT =
            "The previous response hit the output token limit while "
                    + "forming tool calls. Retry the work using smaller "
                    + "tool calls. Do not repeat completed narration.";

    private final AnthropicClient client;

    private final String model;

    // 保存动态 System Prompt 的刷新入口和当前真实运行状态。
    private final SystemPromptManager systemPromptManager;

    private final RuntimeContext runtimeContext;

    private final ToolRegistry toolRegistry;

    private final HookRegistry hookRegistry;

    /*
     * 四层上下文管线共用同一个管理器，
     * 保证工具结果文件、会话摘要和压缩策略属于同一次应用会话。
     */
    private final ContextManager contextManager;

    /*
     * 该上限约束 Agent 主循环轮次，不统计摘要请求和恢复重试。
     * 它用于阻止模型持续请求工具，而不是作为 API 计费上限。
     */
    private final int maxModelRounds;

    public AgentLoop(
            AnthropicClient client,
            String model,
            SystemPromptManager systemPromptManager,
            RuntimeContext runtimeContext,
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            int maxModelRounds
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

        this.systemPromptManager =
                Objects.requireNonNull(
                        systemPromptManager,
                        "SystemPromptManager 不能为空"
                );

        this.runtimeContext =
                Objects.requireNonNull(
                        runtimeContext,
                        "RuntimeContext 不能为空"
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

        // 保存 AgentLoop 执行四层上下文治理时使用的统一入口。
        this.contextManager =
                Objects.requireNonNull(
                        contextManager,
                        "ContextManager 不能为空"
                );

        if (maxModelRounds <= 0) {
            throw new IllegalArgumentException(
                    "最大模型循环轮次必须大于 0"
            );
        }

        // 保存主循环的终止边界，防止工具调用无限延续。
        this.maxModelRounds =
                maxModelRounds;
    }

    /**
     * 持续调用模型，直到模型不再请求工具。
     *
     * @param messages 可修改的会话消息历史
     * @param turnContext 只进入本轮模型请求、不写入会话历史的临时上下文
     * @return 当前任务最终 assistant 回复中的文本结论
     */
    public String run(
            List<MessageParam> messages,
            String turnContext
    ) {
        /*
         * [边界：调用方漏传本轮上下文 → 无法区分“没有召回记忆”
         * 与“集成代码遗漏参数”，导致请求行为依赖空指针位置]
         */
        Objects.requireNonNull(
                turnContext,
                "turnContext 不能为 null"
        );

        /*
         * [核心] 一次 run 对应一个新的用户回合或子任务，
         * 因此先刷新 TURN 以及更短生命周期的 Prompt Item。
         */
        systemPromptManager.refreshFrom(
                RefreshScope.TURN,
                runtimeContext
        );

        /*
         * 输出恢复状态只属于当前用户回合或子任务。
         * 父子 Agent 各自调用 run()，不会共享升级和续写次数。
         */
        long maxTokens =
                DEFAULT_MAX_TOKENS;

        boolean outputLimitEscalated =
                false;

        int outputContinuationCount =
                0;

        List<String> continuedOutputParts =
                new ArrayList<>();

        for (
                int modelRoundCount = 1;
                modelRoundCount <= maxModelRounds;
                modelRoundCount++
        ) {
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
             * L1 先按消息数量裁掉中间旧历史，
             * 同时保护会话目标、最近消息和工具协议边界。
             */
            List<MessageParam> requestMessages =
                    contextManager.snipMiddle(
                            messages
                    );

            /*
             * L2 再替换较旧的大型工具结果。
             *
             * L1 先缩小需要扫描的消息范围；
             * L2 只处理仍留在活跃上下文中的旧工具结果。
             */
            requestMessages =
                    contextManager.compactOldToolResults(
                            requestMessages
                    );

            /*
             * L4 只在两层本地压缩仍不足时调用模型生成摘要。
             *
             * 先执行零 API 成本的压缩，可以避免每轮都为摘要
             * 增加延迟和模型调用费用。
             */
            if (contextManager.shouldAutoCompact(
                    requestMessages
            )) {
                requestMessages =
                        contextManager.compactHistory(
                                requestMessages,
                                ""
                        );
            }

            /*
             * ContextManager 返回新列表，不会直接修改调用方历史。
             * 所有请求前管线成功后再统一替换，避免只提交半条管线的结果。
             */
            messages.clear();

            messages.addAll(
                    requestMessages
            );

            Message response;
            boolean hasToolUse;

            boolean toolCallRetryAttempted =
                    false;

            /*
             * 输出恢复属于当前正常模型轮次的内部过程。
             * 因此升级重试和续写都不会重复执行 BeforeModelCall Hook，
             * 也不会占用父 100 / 子 30 的主循环轮次。
             */
            while (true) {
                response =
                        requestModel(
                                messages,
                                turnContext,
                                maxTokens
                        );

                boolean reachedOutputLimit =
                        response.stopReason()
                                .filter(
                                        StopReason.MAX_TOKENS::equals
                                )
                                .isPresent();

                /*
                 * 第一次截断直接把 8K 提高到 qwen3.5-flash
                 * 已确认支持的 64K，再重试完全相同的消息历史。
                 * 首次截断内容不入历史，避免模型重复输出同一部分。
                 */
                if (reachedOutputLimit
                        && !outputLimitEscalated) {
                    maxTokens =
                            ESCALATED_MAX_TOKENS;

                    outputLimitEscalated =
                            true;

                    LOGGER.warn(
                            "模型输出达到 {} Token，上限提升到 {} 后重试同一请求",
                            DEFAULT_MAX_TOKENS,
                            ESCALATED_MAX_TOKENS
                    );

                    continue;
                }

                hasToolUse =
                        response.content()
                                .stream()
                                .anyMatch(
                                        ContentBlock::isToolUse
                                );

                /*
                 * stop_reason=max_tokens 表示整条响应不完整。
                 * 即使其中已经解析出 tool_use，也可能遗漏后续并行工具，
                 * 因而不能执行可见部分或把截断 assistant 写入历史。
                 */
                if (reachedOutputLimit
                        && hasToolUse) {
                    if (toolCallRetryAttempted) {
                        return "Error: tool calls remained truncated "
                                + "after one smaller-call retry.";
                    }

                    messages.add(
                            MessageParam.builder()
                                    .role(
                                            MessageParam.Role.USER
                                    )
                                    .content(
                                            TOOL_CALL_RETRY_PROMPT
                                    )
                                    .build()
                    );

                    toolCallRetryAttempted =
                            true;

                    LOGGER.warn(
                            "64K 输出在工具调用阶段被截断，丢弃响应并要求拆小工具调用"
                    );

                    continue;
                }

                MessageParam assistantMessage =
                        toAssistantMessage(
                                response
                        );

                /*
                 * 只有完整响应或纯文本截断可以进入历史。
                 * 前者将进入正常结束/工具执行，后者将通过 user 消息续写。
                 */
                messages.add(
                        assistantMessage
                );

                if (!reachedOutputLimit) {
                    break;
                }

                continuedOutputParts.add(
                        extractText(
                                response
                        )
                );

                if (outputContinuationCount
                        >= MAX_OUTPUT_CONTINUATIONS) {
                    return String.join(
                            "\n",
                            continuedOutputParts
                    )
                            + "\n\nError: model output remained truncated after "
                            + MAX_OUTPUT_CONTINUATIONS
                            + " continuation attempts.";
                }

                messages.add(
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.USER
                                )
                                .content(
                                        OUTPUT_CONTINUATION_PROMPT
                                )
                                .build()
                );

                outputContinuationCount++;

                LOGGER.warn(
                        "64K 输出仍被截断，开始第 {}/{} 次续写",
                        outputContinuationCount,
                        MAX_OUTPUT_CONTINUATIONS
                );
            }

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
                continuedOutputParts.add(
                        extractText(
                                response
                        )
                );

                return String.join(
                        "\n",
                        continuedOutputParts
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

                ToolExecutionResult executionResult;

                HookEffect combinedEffect =
                        beforeEffect;

                if (beforeEffect.decision()
                        == HookEffect.Decision.BLOCK) {
                    /*
                     * 权限拒绝时不执行工具，
                     * 但仍要把拒绝原因作为 tool_result 返回模型。
                     */
                    executionResult =
                            ToolExecutionResult.failure(
                                    beforeEffect.reason()
                            );
                } else {
                    ToolCall effectiveToolCall =
                            beforeEffect.updatedInput() == null
                                    ? toolCall
                                    : toolCall.withInput(
                                            beforeEffect.updatedInput()
                                    );

                    executionResult =
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
                                    executionResult.content()
                            );

                    combinedEffect =
                            beforeEffect.and(
                                    afterEffect
                            );

                    if (afterEffect.updatedOutput() != null) {
                        executionResult =
                                new ToolExecutionResult(
                                        afterEffect.updatedOutput(),
                                        executionResult.error()
                                );
                    }
                }

                // 后续 Hook 上下文只修改输出文本，不改变成功或失败状态。
                String output =
                        executionResult.content();

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
                                .isError(
                                        executionResult.error()
                                )
                                .build();

                toolResults.add(
                        ContentBlockParam.ofToolResult(
                                toolResult
                        )
                );
            }

            /*
             * L3 在工具结果进入长期历史前限制本轮输出总量。
             *
             * 此时 Hook 已经完成输出修改，预算计算看到的是
             * 真正准备交给模型的最终结果。
             */
            List<ContentBlockParam> limitedToolResults =
                    contextManager.applyToolResultBudget(
                            toolResults
                    );

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
                                    limitedToolResults
                            )
                            .build();

            messages.add(
                    resultMessage
            );

            /*
             * 回到 for 循环开头。
             *
             * 下一次请求会把工具结果连同完整历史再次发送给模型。
             */
        }

        /*
         * 执行到这里说明最后一次模型响应仍要求调用工具，
         * Agent 没有在规定次数内生成最终答案。
         */
        return "Error: agent reached the limit of "
                + maxModelRounds
                + " model rounds without a final answer.";
    }

    /**
     * 发送一次主模型请求，并只对输入上下文超限恢复一次。
     *
     * 输出上限升级和续写都会调用这个入口，因此每次真实请求
     * 都会刷新 MODEL_CALL System Prompt；BeforeModelCall Hook 则仍只在
     * 外层正常轮次执行一次。
     *
     * @param messages 当前真实会话历史
     * @param turnContext 只进入本轮请求的临时上下文
     * @param maxTokens 本次请求允许的最大输出 Token
     * @return 主模型响应
     */
    private Message requestModel(
            List<MessageParam> messages,
            String turnContext,
            long maxTokens
    ) {
        try {
            return client.messages()
                    .create(
                            createRequest(
                                    messages,
                                    turnContext,
                                    maxTokens
                            )
                    );
        } catch (BadRequestException exception) {
            /*
             * 普通 400 可能是参数、模型或协议错误，
             * 只有已确认的上下文超限错误才允许改变历史并重试。
             */
            if (!isPromptTooLong(
                    exception
            )) {
                throw exception;
            }

            /*
             * 恢复压缩摘要较旧轮次，并原样保留最新 API 轮次，
             * 使下一次请求仍在处理同一项尚未完成的工作。
             */
            List<MessageParam> recoveredMessages =
                    contextManager.recoverFromPromptTooLong(
                            messages
                    );

            // 恢复成功后才用新历史替换被服务端拒绝的历史。
            messages.clear();

            messages.addAll(
                    recoveredMessages
            );

            /*
             * 第二次调用直接重试同一个主模型请求。
             * 这里不再捕获连续超限，避免形成无限压缩循环。
             */
            return client.messages()
                    .create(
                            createRequest(
                                    messages,
                                    turnContext,
                                    maxTokens
                            )
                    );
        }
    }

    /**
     * 把 SDK 响应转换成下一次请求可以复用的 assistant 消息。
     */
    private static MessageParam toAssistantMessage(
            Message response
    ) {
        return MessageParam.builder()
                .role(
                        MessageParam.Role.ASSISTANT
                )
                .contentOfBlockParams(
                        response.content()
                                .stream()
                                .map(
                                        ContentBlock::toParam
                                )
                                .toList()
                )
                .build();
    }

    /**
     * 提取一段 assistant 响应中的全部文本块。
     */
    private static String extractText(
            Message response
    ) {
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

    /**
     * 判断百炼或 Anthropic 是否因输入上下文过长拒绝主请求。
     *
     * 当前应用固定使用百炼的 Anthropic 兼容端点，
     * 因此这里只识别两个已经确认的错误文本，
     * 不把所有 BadRequestException 都当成可恢复错误。
     *
     * @param exception Anthropic Java SDK 返回的 HTTP 400 异常
     * @return 错误内容明确表示输入上下文超限时返回 true
     */
    private static boolean isPromptTooLong(
            BadRequestException exception
    ) {
        /*
         * SDK 的异常消息包含序列化后的服务端响应体。
         * ROOT 区域规则保证大小写转换不受运行机器语言环境影响。
         */
        String errorMessage =
                exception.getMessage()
                        .toLowerCase(
                                Locale.ROOT
                        );

        /*
         * Anthropic 使用 “prompt is too long”，
         * 百炼使用 “Range of input length should be [1, ...]”。
         */
        return errorMessage.contains(
                "prompt is too long"
        )
                || errorMessage.contains(
                "range of input length should be [1,"
        );
    }

    /**
     * 构造一次模型请求。
     *
     * turnContext 只追加到即将发送的请求对象，
     * 不会写入调用方持有的 messages。
     *
     * @param messages 当前真实会话历史
     * @param turnContext 本轮临时上下文；空字符串表示没有
     * @param maxTokens 本次请求允许的最大输出 Token
     * @return 可以直接发送给模型的请求
     */
    private MessageCreateParams createRequest(
            List<MessageParam> messages,
            String turnContext,
            long maxTokens
    ) {
        /*
         * [核心] 每次真正构造 Anthropic 请求前刷新 MODEL_CALL Item。
         * 普通调用和上下文超限后的恢复重试都会经过这里。
         */
        SystemPrompt systemPrompt =
                systemPromptManager.refreshFrom(
                        RefreshScope.MODEL_CALL,
                        runtimeContext
                );

        // [核心] 使用最新完整 System Prompt、真实历史和工具定义构造请求。
        MessageCreateParams.Builder request =
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .system(
                                systemPrompt.content()
                        )
                        .messages(messages)
                        .tools(
                                toolRegistry.definitions()
                        );

        /*
         * [核心] 有召回内容时，只把它追加到本次 API 请求。
         *
         * 不执行 messages.add()，避免记忆内容进入压缩、
         * 后续会话历史和回合结束后的记忆提取。
         */
        if (!turnContext.isBlank()) {
            request.addUserMessage(
                    "<system-reminder>\n"
                            + turnContext
                            + "\n</system-reminder>"
            );
        }

        // 完成不再修改的模型请求。
        return request.build();
    }

}
