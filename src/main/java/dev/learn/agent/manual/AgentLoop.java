// 声明手写 Agent 主循环所在的包。
package dev.learn.agent.manual;

// 引入模型 SDK、项目组件和日志依赖。
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.SseException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.RuntimeContext;
import dev.learn.agent.manual.systemprompt.SystemPrompt;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.ToolExecutionScheduler;
import dev.learn.agent.manual.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 引入集合、并发和回调所需的 JDK 类型。
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 手写 Agent 的模型调用和工具执行循环。
 */
public final class AgentLoop {

    // 记录模型流、工具执行和恢复过程中的运行信息。
    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    AgentLoop.class
            );

    // 创建解析流式工具参数的 JSON 映射器。
    // 设计意图：沿用 Anthropic SDK 的映射规则，避免同一参数出现两套 JSON 语义。
    private static final ObjectMapper JSON_MAPPER =
            ObjectMappers.jsonMapper();

    // 定义单次模型响应允许生成的最大 token 数。
    private static final long MAX_OUTPUT_TOKENS =
            64_000;

    // 定义同一任务因输出截断而允许续写的最大次数。
    private static final int MAX_OUTPUT_CONTINUATIONS =
            3;

    // 定义普通文本响应达到输出上限后的续写指令。
    private static final String OUTPUT_CONTINUATION_PROMPT =
            "Output token limit hit. Resume directly — "
                    + "no apology, no recap. "
                    + "Pick up mid-thought.";

    // 定义含工具调用的响应达到输出上限后的续写指令。
    private static final String TOOL_CALL_CONTINUATION_PROMPT =
            "The previous response reached the output limit. Complete "
                    + "tool calls above have already run. Continue from "
                    + "their results, split remaining calls into smaller "
                    + "calls, and do not repeat completed work.";

    // 定义模型响应流意外中断后的恢复指令。
    private static final String STREAM_INTERRUPTION_PROMPT =
            "The previous assistant stream ended unexpectedly. Only fully "
                    + "completed content blocks were preserved; the unfinished "
                    + "block was discarded. Continue without repeating "
                    + "completed work.";

    // 保存发送模型请求所用的 Anthropic 客户端。
    private final AnthropicClient client;

    // 保存每次请求使用的模型标识。
    private final String model;

    // 保存动态 System Prompt 的刷新入口。
    private final SystemPromptManager systemPromptManager;

    // 保存生成动态 System Prompt 时读取的真实运行状态。
    private final RuntimeContext runtimeContext;

    // 保存可供模型调用的工具注册表。
    private final ToolRegistry toolRegistry;

    // 保存模型调用和工具调用生命周期中的 Hook 注册表。
    private final HookRegistry hookRegistry;

    // 保存四层上下文治理共用的管理器。
    // 设计意图：共用同一个管理器，保证工具结果文件、会话摘要和压缩策略属于同一次应用会话。
    private final ContextManager contextManager;

    // 保存 Agent 主循环允许执行的最大模型轮次。
    // 设计意图：只限制模型持续请求工具的业务轮次，不统计摘要请求和恢复重试，也不作为 API 计费上限。
    private final int maxModelRounds;

    /**
     * 创建模型调用和工具执行循环。
     *
     * @param client Anthropic 客户端
     * @param model 模型标识
     * @param systemPromptManager 动态 System Prompt 管理器
     * @param runtimeContext 当前运行上下文
     * @param toolRegistry 工具注册表
     * @param hookRegistry Hook 注册表
     * @param contextManager 上下文治理管理器
     * @param maxModelRounds 最大业务模型轮次
     * @throws NullPointerException 任一对象依赖为 null 时抛出
     * @throws IllegalArgumentException 最大业务模型轮次不大于 0 时抛出
     */
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
        // 校验并保存模型客户端。
        this.client =
                Objects.requireNonNull(
                        client,
                        "AnthropicClient 不能为空"
                );

        // 校验并保存模型标识。
        this.model =
                Objects.requireNonNull(
                        model,
                        "Model 不能为空"
                );

        // 校验并保存 System Prompt 管理器。
        this.systemPromptManager =
                Objects.requireNonNull(
                        systemPromptManager,
                        "SystemPromptManager 不能为空"
                );

        // 校验并保存运行上下文。
        this.runtimeContext =
                Objects.requireNonNull(
                        runtimeContext,
                        "RuntimeContext 不能为空"
                );

        // 校验并保存工具注册表。
        this.toolRegistry =
                Objects.requireNonNull(
                        toolRegistry,
                        "ToolRegistry 不能为空"
                );

        // 校验并保存 Hook 注册表。
        this.hookRegistry =
                Objects.requireNonNull(
                        hookRegistry,
                        "HookRegistry 不能为空"
                );

        // 校验并保存四层上下文治理的统一入口。
        this.contextManager =
                Objects.requireNonNull(
                        contextManager,
                        "ContextManager 不能为空"
                );

        // 校验主循环轮次下限。
        // 设计意图：无效上限在构造边界立即失败，避免创建永远无法执行的 AgentLoop。
        if (maxModelRounds <= 0) {
            throw new IllegalArgumentException(
                    "最大模型循环轮次必须大于 0"
            );
        }

        // 保存主循环的终止边界。
        // 设计意图：用明确轮次上限阻止模型持续请求工具而无法结束任务。
        this.maxModelRounds =
                maxModelRounds;
    }

    /**
     * 持续调用模型，直到模型不再请求工具。
     *
     * @param messages 可修改的会话消息历史
     * @param turnContext 只进入本轮模型请求、不写入会话历史的临时上下文
     * @param textOutput 实时接收模型文本增量的输出边界
     * @return 当前任务最终 assistant 回复中的文本结论
     */
    public String run(
            List<MessageParam> messages,
            String turnContext,
            Consumer<String> textOutput
    ) {
        // 校验本轮临时上下文已由调用方明确提供。
        // 设计意图：区分“没有召回记忆”和“集成代码遗漏参数”，让契约错误在调用边界暴露。
        Objects.requireNonNull(
                turnContext,
                "turnContext 不能为 null"
        );

        // 校验实时文本输出边界已由调用方提供。
        // 设计意图：输出策略交给调用方，使父 Agent 可以展示文本、子 Agent 可以静默消费。
        Objects.requireNonNull(
                textOutput,
                "textOutput 不能为 null"
        );

        // 刷新本轮以及更短生命周期的 System Prompt Item。
        // 设计意图：一次 run 对应一个新用户回合或子任务，必须从该边界更新动态提示词。
        systemPromptManager.refreshFrom(
                RefreshScope.TURN,
                runtimeContext
        );

        // 初始化当前回合已经执行的输出续写次数。
        int outputContinuationCount =
                0;

        // 初始化跨续写响应累积的完整文本片段。
        List<String> continuedOutputParts =
                new ArrayList<>();

        // 在业务轮次上限内持续请求模型并处理工具调用。
        for (
                int modelRoundCount = 1;
                modelRoundCount <= maxModelRounds;
                modelRoundCount++
        ) {
            // 触发模型请求前 Hook，收集基于当前历史生成的附加上下文。
            HookEffect beforeModelEffect =
                    hookRegistry.triggerBeforeModelCall(
                            messages
                    );

            // 在 Hook 返回附加上下文时把它们写入会话历史。
            if (!beforeModelEffect.additionalContexts()
                    .isEmpty()) {
                // 把多个 Hook 的附加上下文合并成一段文本。
                // 设计意图：合并为一条隐藏提醒，避免每个 Hook 分别制造一条 user 消息。
                String additionalContext =
                        String.join(
                                "\n\n",
                                beforeModelEffect.additionalContexts()
                        );

                // 把合并后的上下文作为隐藏提醒追加到会话历史。
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

                // 记录本轮实际追加的 Hook 上下文数量。
                LOGGER.debug(
                        "BeforeModelCall：已追加 {} 段上下文",
                        beforeModelEffect
                                .additionalContexts()
                                .size()
                );
            }

            // 执行 L1 中段裁剪，得到本次请求使用的消息副本。
            // 设计意图：先按数量缩小历史，同时保护会话目标、最近消息和工具协议边界。
            List<MessageParam> requestMessages =
                    contextManager.snipMiddle(
                            messages
                    );

            // 执行 L2 旧工具结果压缩。
            // 设计意图：L1 先缩小扫描范围，L2 只处理仍留在活跃上下文中的旧工具结果。
            requestMessages =
                    contextManager.compactOldToolResults(
                            requestMessages
                    );

            // 在本地压缩后仍超出阈值时执行 L4 模型摘要。
            // 设计意图：优先使用零 API 成本的压缩，避免每轮摘要带来的延迟和模型调用费用。
            if (contextManager.shouldAutoCompact(
                    requestMessages
            )) {
                requestMessages =
                        contextManager.compactHistory(
                                requestMessages,
                                ""
                        );
            }

            // 清空原历史，准备提交完整的请求前上下文治理结果。
            // 设计意图：ContextManager 返回新列表，所有管线成功后统一替换，避免提交半条管线的结果。
            messages.clear();

            // 把治理完成的请求消息写回真实会话历史。
            messages.addAll(
                    requestMessages
            );

            // 声明当前模型流结果和工具调用状态。
            StreamTurn turn;
            boolean hasToolUse;

            // 初始化当前业务轮次的流中断标记。
            boolean interruptedRound =
                    false;

            // 在当前业务轮次内部持续处理输出续写或结束条件。
            // 设计意图：恢复和续写不重复执行 BeforeModelCall Hook，也不额外占用业务轮次。
            while (true) {
                // 发送一次主模型请求并取得稳定的流结果。
                turn =
                        requestModel(
                                messages,
                                turnContext,
                                textOutput
                        );

                // 根据完整工具调用列表判断本轮是否需要执行工具协议。
                hasToolUse =
                        !turn.toolExecutions()
                                .isEmpty();

                // 在模型流中断时提交已完成内容并切换到外层恢复流程。
                // 设计意图：不重发原请求，只让下一业务轮次从已提交的完整块和恢复消息继续。
                if (turn.interrupted()) {
                    // 保存流中断前已经完整关闭的文本。
                    if (!turn.text()
                            .isBlank()) {
                        continuedOutputParts.add(
                                turn.text()
                        );
                    }

                    // 把流中断前的完整协议内容提交到会话历史。
                    commitInterruptedTurn(
                            messages,
                            turn
                    );

                    // 标记当前业务轮次需要按流中断规则恢复。
                    interruptedRound =
                            true;

                    // 结束当前轮次内部的输出处理循环。
                    break;
                }

                // 在存在工具调用时退出内部循环，转入统一的工具结果提交阶段。
                // 设计意图：工具 assistant 必须等待全部结果后与 tool_result 成对提交。
                if (hasToolUse) {
                    break;
                }

                // 把没有工具调用的完整 assistant 内容写入会话历史。
                if (!turn.assistantContent()
                        .isEmpty()) {
                    messages.add(
                            toAssistantMessage(
                                    turn.assistantContent()
                            )
                    );
                }

                // 在响应未达到输出上限时结束内部循环。
                if (!turn.reachedOutputLimit()) {
                    break;
                }

                // 保存本次被输出上限截断前的完整文本。
                continuedOutputParts.add(
                        turn.text()
                );

                // 在续写次数耗尽时构造并返回截断错误。
                if (outputContinuationCount
                        >= MAX_OUTPUT_CONTINUATIONS) {
                    String error =
                            "Error: model output remained truncated after "
                                    + MAX_OUTPUT_CONTINUATIONS
                                    + " continuation attempts.";

                    // 把截断错误追加到实时输出。
                    // 设计意图：正文已经流式展示，只追加错误可以避免重复打印完整正文。
                    textOutput.accept(
                            "\n\n" + error
                    );

                    // 返回已完成文本与截断错误组成的最终结果。
                    return String.join(
                            "\n",
                            continuedOutputParts
                    )
                            + "\n\n"
                            + error;
                }

                // 把普通文本续写指令追加到会话历史。
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

                // 累加当前回合的输出续写次数。
                outputContinuationCount++;

                // 记录即将开始的续写次数和上限。
                LOGGER.warn(
                        "64K 输出仍被截断，开始第 {}/{} 次续写",
                        outputContinuationCount,
                        MAX_OUTPUT_CONTINUATIONS
                );
            }

            // 在流中断时退还当前业务轮次并重新进入请求前管线。
            // 设计意图：中断恢复已经写入历史，且它不是模型反复调用工具，不应消耗业务轮次。
            if (interruptedRound) {
                // 抵消 for 循环即将执行的业务轮次递增。
                modelRoundCount--;

                // 从外层循环重新执行请求前 Hook 和上下文治理。
                continue;
            }

            // 在没有工具调用时进入任务停止检查。
            if (!hasToolUse) {
                // 触发 Stop Hook，确认模型是否可以结束当前任务。
                HookEffect stopEffect =
                        hookRegistry.triggerStop(
                                messages
                        );

                // 在 Stop Hook 阻止结束时构造继续处理消息。
                if (stopEffect.decision()
                        == HookEffect.Decision.BLOCK) {
                    // 把 Stop Hook 返回的原因作为继续处理消息。
                    String continuationMessage =
                            stopEffect.reason();

                    // 在 Stop Hook 提供附加上下文时合并到继续处理消息。
                    if (!stopEffect.additionalContexts()
                            .isEmpty()) {
                        continuationMessage +=
                                "\n\nHook 追加上下文：\n"
                                        + String.join(
                                                "\n",
                                                stopEffect.additionalContexts()
                                        );
                    }

                    // 把 Stop Hook 的继续处理消息追加到会话历史。
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

                    // 开始下一业务模型轮次，让模型继续处理任务。
                    continue;
                }

                // 把最终 assistant 响应文本加入跨续写结果。
                // 设计意图：只返回文本，使子 Agent 能作为 task 工具结果交付而不泄露完整消息历史。
                continuedOutputParts.add(
                        turn.text()
                );

                // 合并并返回当前任务的全部完整文本片段。
                return String.join(
                        "\n",
                        continuedOutputParts
                );
            }

            // 按模型调用顺序等待并收集本轮工具结果。
            List<ContentBlockParam> limitedToolResults =
                    collectToolResults(
                            turn.toolExecutions()
                    );

            // 在含工具调用的响应达到输出上限时追加专用续写说明。
            // 设计意图：已执行的完整工具调用不能撤销，恢复说明必须放在全部 tool_result 之后。
            if (turn.reachedOutputLimit()) {
                // 创建可追加恢复说明的工具结果列表。
                limitedToolResults =
                        new ArrayList<>(
                                limitedToolResults
                        );

                // 在全部工具结果之后追加工具调用续写说明。
                limitedToolResults.add(
                        textBlock(
                                TOOL_CALL_CONTINUATION_PROMPT
                        )
                );
            }

            // 构造携带全部 tool_result 的 user 消息。
            // 设计意图：遵循 Anthropic 的 assistant tool_use 与 user tool_result 配对协议。
            MessageParam resultMessage =
                    MessageParam.builder()
                            .role(
                                    MessageParam.Role.USER
                            )
                            .contentOfBlockParams(
                                    limitedToolResults
                            )
                            .build();

            // 成对提交 assistant 工具请求和 user 工具结果。
            messages.addAll(
                    List.of(
                            toAssistantMessage(
                                    turn.assistantContent()
                            ),
                            resultMessage
                    )
            );

            // 当前轮次结束后回到 for 循环，让下一请求读取工具结果和完整历史。
        }

        // 在业务模型轮次耗尽后构造未生成最终答案的错误。
        String error =
                "Error: agent reached the limit of "
                        + maxModelRounds
                        + " model rounds without a final answer.";

        // 把业务轮次耗尽错误发送到实时输出边界。
        // 设计意图：主循环耗尽后不会再有模型文本，需要由本地流程明确告知用户。
        textOutput.accept(
                "\n\n" + error
        );

        // 返回业务轮次耗尽错误作为本次任务结果。
        return error;
    }

    /**
     * 发送一次主模型请求，并只对输入上下文超限恢复一次。
     *
     * 输出续写也会调用这个入口，因此每次真实请求
     * 都会刷新 MODEL_CALL System Prompt；BeforeModelCall Hook 则仍只在
     * 外层正常轮次执行一次。
     *
     * @param messages 当前真实会话历史
     * @param turnContext 只进入本轮请求的临时上下文
     * @param textOutput 实时接收模型文本增量的输出边界
     * @return 主模型响应
     */
    private StreamTurn requestModel(
            List<MessageParam> messages,
            String turnContext,
            Consumer<String> textOutput
    ) {
        // 首次发送当前主模型请求。
        try {
            return createStreamingTurn(
                    createRequest(
                            messages,
                            turnContext
                    ),
                    textOutput
            );
        } catch (BadRequestException exception) {
            // 判断 HTTP 400 是否为已经确认的输入上下文超限错误。
            // 设计意图：普通 400 还可能表示参数、模型或协议错误，不能据此修改会话历史。
            if (!isPromptTooLong(
                    exception
            )) {
                throw exception;
            }

            // 压缩较旧轮次并保留最新 API 轮次，生成恢复后的消息历史。
            // 设计意图：重试请求仍需处理同一项尚未完成的工作。
            List<MessageParam> recoveredMessages =
                    contextManager.recoverFromPromptTooLong(
                            messages
                    );

            // 清空被服务端拒绝的原消息历史。
            // 设计意图：恢复成功后才替换真实历史，避免压缩失败时丢失原数据。
            messages.clear();

            // 写入恢复压缩成功后的消息历史。
            messages.addAll(
                    recoveredMessages
            );

            // 使用恢复后的历史重试同一个主模型请求。
            // 设计意图：不再捕获第二次超限，避免形成无限压缩循环。
            return createStreamingTurn(
                    createRequest(
                            messages,
                            turnContext
                    ),
                    textOutput
            );
        }
    }

    /**
     * 消费一次模型事件流，并在内容块关闭时按安全级别调度完整工具调用。
     *
     * 正常结束和中断恢复共用同一份完整内容块；
     * 尚未收到 content_block_stop 的当前块不会进入结果。
     *
     * @param request 已构造完成的模型请求
     * @param textOutput 实时接收模型文本增量的输出边界
     * @return 本次模型流可安全进入历史的内容和工具执行
     */
    private StreamTurn createStreamingTurn(
            MessageCreateParams request,
            Consumer<String> textOutput
    ) {
        // 初始化已经收到 content_block_stop 的稳定 assistant 内容列表。
        List<ContentBlockParam> assistantContent =
                new ArrayList<>();

        // 初始化已经完整关闭、可进入最终返回值的文本列表。
        List<String> completedText =
                new ArrayList<>();

        // 初始化按模型调用顺序保存的工具执行列表。
        List<PendingToolExecution> toolExecutions =
                new ArrayList<>();

        // 初始化当前尚未关闭的流式内容块。
        // 设计意图：当前块只在 start 和 stop 之间存在，流中断时直接丢弃。
        RawContentBlockStartEvent.ContentBlock currentBlock =
                null;

        // 初始化当前内容块的增量文本或工具 JSON 缓冲区。
        StringBuilder currentContent =
                null;

        // 初始化是否已经收到 message_stop 的状态。
        boolean messageStopped =
                false;

        // 初始化本次响应是否达到模型输出上限的状态。
        boolean reachedOutputLimit =
                false;

        // 创建当前模型流的有序并发调度边界。
        // 设计意图：只读工具可并发，副作用工具按模型调用顺序独占执行。
        try (ToolExecutionScheduler toolScheduler =
                     new ToolExecutionScheduler()) {
            // 创建并消费模型响应流，把可恢复的流异常转换成稳定结果。
            try {
                // 发送已构造的请求并取得模型事件流。
                StreamResponse<RawMessageStreamEvent> streamResponse =
                        client.messages()
                                .createStreaming(
                                        request
                                );

                // 在自动关闭响应流的边界内遍历全部事件。
                try (streamResponse) {
                    // 创建按服务端顺序读取事件的迭代器。
                    Iterator<RawMessageStreamEvent> events =
                            streamResponse.stream()
                                    .iterator();

                    // 按 Anthropic 事件顺序消费事件并构造唯一的当前内容块。
                    while (events.hasNext()) {
                        // 读取下一个模型流事件。
                        RawMessageStreamEvent event =
                                events.next();

                        // 在 content_block_start 到达时初始化当前内容块。
                        if (event.isContentBlockStart()) {
                            // 保存新开始的内容块类型和初始数据。
                            currentBlock =
                                    event.asContentBlockStart()
                                            .contentBlock();

                            // 根据文本或工具调用类型初始化当前内容缓冲区。
                            if (currentBlock.isText()) {
                                currentContent =
                                        new StringBuilder(
                                                currentBlock.asText()
                                                        .text()
                                        );
                            } else if (currentBlock.isToolUse()) {
                                currentContent =
                                        new StringBuilder();
                            } else {
                                throw new IllegalStateException(
                                        "Unsupported streamed content block: "
                                                + currentBlock
                                );
                            }

                            // 当前块已初始化，继续读取下一个流事件。
                            continue;
                        }

                        // 在 content_block_delta 到达时累积当前块的增量内容。
                        if (event.isContentBlockDelta()) {
                            // 提取当前增量的具体类型和值。
                            RawContentBlockDelta delta =
                                    event.asContentBlockDelta()
                                            .delta();

                            // 根据文本或工具 JSON 类型处理当前增量。
                            if (delta.isText()) {
                                // 读取本次文本增量。
                                String text =
                                        delta.asText()
                                                .text();

                                // 把文本增量追加到当前内容缓冲区。
                                currentContent.append(
                                        text
                                );

                                // 把正文增量立即发送到实时输出边界。
                                // 设计意图：正文需要即时展示，工具 JSON 则只在内存中累积。
                                textOutput.accept(
                                        text
                                );
                            } else if (delta.isInputJson()) {
                                currentContent.append(
                                        delta.asInputJson()
                                                .partialJson()
                                );
                            } else {
                                throw new IllegalStateException(
                                        "Unsupported streamed content delta: "
                                                + delta
                                );
                            }

                            // 当前增量已处理，继续读取下一个流事件。
                            continue;
                        }

                        // 在 content_block_stop 到达时提交当前完整内容块。
                        if (event.isContentBlockStop()) {
                            // 根据当前块是文本还是工具调用生成稳定内容。
                            if (currentBlock.isText()) {
                                // 读取已经完整关闭的文本块内容。
                                String text =
                                        currentContent.toString();

                                // 把完整文本块加入 assistant 协议内容。
                                assistantContent.add(
                                        textBlock(
                                                text
                                        )
                                );

                                // 把完整文本加入最终返回值候选列表。
                                completedText.add(
                                        text
                                );
                            } else {
                                // 读取 content_block_start 中保存的工具调用元数据。
                                ToolUseBlock startTool =
                                        currentBlock.asToolUse();

                                // 初始化解析后的工具输入。
                                JsonNode input =
                                        null;

                                // 把完整工具 JSON 缓冲区解析为输入对象。
                                try {
                                    input =
                                            currentContent.isEmpty()
                                                    ? JSON_MAPPER.createObjectNode()
                                                    : JSON_MAPPER.readTree(
                                                            currentContent.toString()
                                                    );
                                } catch (JsonProcessingException ignored) {
                                    // 保持输入为空，交由后续校验生成失败的工具结果。
                                    // 设计意图：失败结果沿用原 tool_use_id 返回模型，便于模型修正参数。
                                }

                                // 判断解析结果是否满足工具输入必须为 JSON 对象的契约。
                                boolean validInput =
                                        input != null
                                                && input.isObject();

                                // 为 SDK 工具块准备类型安全的 JSON 对象输入。
                                JsonNode safeInput =
                                        validInput
                                                ? input
                                                : JSON_MAPPER.createObjectNode();

                                // 用完整输入重建可以提交到协议历史的工具调用。
                                ToolUseBlock completedTool =
                                        startTool.toBuilder()
                                                .input(
                                                        JsonValue.fromJsonNode(
                                                                safeInput
                                                        )
                                                )
                                                .build();

                                // 对合法输入按工具安全级别调度，对非法输入直接创建失败结果。
                                CompletableFuture<ToolExecutionResult> future =
                                        validInput
                                                ? toolScheduler.submit(
                                                        toolRegistry.isConcurrencySafe(
                                                                completedTool.name()
                                                        ),
                                                        () -> executeTool(
                                                                completedTool
                                                        )
                                                )
                                                : CompletableFuture.completedFuture(
                                                        ToolExecutionResult.failure(
                                                                "Error: tool input must be a valid JSON object."
                                                        )
                                                );

                                // 把完整工具调用加入 assistant 协议内容。
                                assistantContent.add(
                                        ContentBlockParam.ofToolUse(
                                                completedTool.toParam()
                                        )
                                );

                                // 按模型调用顺序保存工具元数据和异步结果。
                                toolExecutions.add(
                                        new PendingToolExecution(
                                                completedTool,
                                                future
                                        )
                                );
                            }

                            // 清除已经关闭的当前内容块。
                            currentBlock =
                                    null;

                            // 清除已经关闭内容块的增量缓冲区。
                            currentContent =
                                    null;

                            // 当前完整块已提交，继续读取下一个流事件。
                            continue;
                        }

                        // 在 message_delta 到达时记录模型停止原因。
                        if (event.isMessageDelta()) {
                            // 在停止原因为 MAX_TOKENS 时标记响应达到输出上限。
                            if (event.asMessageDelta()
                                    .delta()
                                    .stopReason()
                                    .filter(
                                            StopReason.MAX_TOKENS::equals
                                    )
                                    .isPresent()) {
                                reachedOutputLimit =
                                        true;
                            }

                            // 当前消息增量已处理，继续读取下一个流事件。
                            continue;
                        }

                        // 在 message_stop 到达时标记响应流正常结束。
                        if (event.isMessageStop()) {
                            messageStopped =
                                    true;
                        }
                    }
                }

                // 检查响应流是否拥有最终停止信号且不存在未关闭内容块。
                // 设计意图：不完整块不能进入协议历史，异常结束统一交给中断恢复流程。
                if (!messageStopped
                        || currentBlock != null) {
                    return interruptedTurn(
                            assistantContent,
                            completedText,
                            toolExecutions,
                            textOutput,
                            new AnthropicInvalidDataException(
                                    "Stream ended before the current message completed"
                            )
                    );
                }

                // 返回正常结束的稳定内容、工具执行和输出上限状态。
                return new StreamTurn(
                        List.copyOf(
                                assistantContent
                        ),
                        String.join(
                                "\n",
                                completedText
                        ),
                        List.copyOf(
                                toolExecutions
                        ),
                        reachedOutputLimit,
                        false
                );
            } catch (AnthropicIoException
                     | AnthropicInvalidDataException
                     | SseException exception) {
                // 把可恢复的模型流异常转换为仅含完整内容块的中断结果。
                return interruptedTurn(
                        assistantContent,
                        completedText,
                        toolExecutions,
                        textOutput,
                        exception
                );
            }
        }
    }

    /**
     * 把流异常转换成只包含完整内容块的可续接结果。
     *
     * @param assistantContent 已经完整关闭的 assistant 内容块
     * @param completedText 已经完整关闭的文本块
     * @param toolExecutions 已经启动的工具调用
     * @param textOutput 用户可见输出边界
     * @param cause 流中断原因
     * @return 不包含当前未关闭内容块的中断结果
     */
    private StreamTurn interruptedTurn(
            List<ContentBlockParam> assistantContent,
            List<String> completedText,
            List<PendingToolExecution> toolExecutions,
            Consumer<String> textOutput,
            RuntimeException cause
    ) {
        // 记录流中断原因以及已经保留的稳定内容数量。
        // 设计意图：完整异常只进入本地日志，模型只接收稳定的恢复事实。
        LOGGER.warn(
                "模型响应流中断，保留 {} 个完整内容块和 {} 个工具调用",
                assistantContent.size(),
                toolExecutions.size(),
                cause
        );

        // 在实时输出中提示最后一个未完成内容块已经丢弃。
        // 设计意图：未关闭文本可能已经显示但不会进入协议历史，提示可避免用户误判恢复起点。
        textOutput.accept(
                "\n\n[响应流中断：最后未完成的内容块未保存，"
                        + "将从已完成内容继续]\n\n"
        );

        // 构造仅包含完整内容块的可续接流结果。
        return new StreamTurn(
                List.copyOf(
                        assistantContent
                ),
                String.join(
                        "\n",
                        completedText
                ),
                List.copyOf(
                        toolExecutions
                ),
                false,
                true
        );
    }

    /**
     * 把 SDK 响应转换成下一次请求可以复用的 assistant 消息。
     */
    private static MessageParam toAssistantMessage(
            List<ContentBlockParam> content
    ) {
        // 把 SDK 内容块封装为 assistant 角色的历史消息。
        return MessageParam.builder()
                .role(
                        MessageParam.Role.ASSISTANT
                )
                .contentOfBlockParams(
                        content
                )
                .build();
    }

    /**
     * 执行一个已经完整关闭的工具调用。
     *
     * @param toolUse 已收到 content_block_stop 的工具调用
     * @return 保留真实成功或失败状态的工具执行结果
     */
    private ToolExecutionResult executeTool(
            ToolUseBlock toolUse
    ) {
        // 在工具执行管线边界内捕获未处理的运行时异常。
        try {
            // 把 SDK 工具块转换成 Hook 和工具注册表共用的内部对象。
            ToolCall toolCall =
                    ToolCall.from(
                            toolUse
                    );

            // 触发工具执行前 Hook，取得权限决策和输入修改。
            HookEffect beforeEffect =
                    hookRegistry.triggerBeforeToolUse(
                            toolCall
                    );

            // 声明当前工具的最终执行结果。
            ToolExecutionResult executionResult;

            // 初始化用于汇总前后置 Hook 的组合效果。
            HookEffect combinedEffect =
                    beforeEffect;

            // 根据前置 Hook 的决策选择拒绝工具或执行完整管线。
            if (beforeEffect.decision()
                    == HookEffect.Decision.BLOCK) {
                // 为被前置 Hook 拒绝的工具构造失败结果。
                // 设计意图：不执行工具，但仍返回可与原 tool_use 配对的错误。
                executionResult =
                        ToolExecutionResult.failure(
                                beforeEffect.reason()
                        );
            } else {
                // 应用前置 Hook 修改后的工具输入。
                ToolCall effectiveToolCall =
                        beforeEffect.updatedInput() == null
                                ? toolCall
                                : toolCall.withInput(
                                        beforeEffect.updatedInput()
                                );

                // 通过工具注册表执行最终工具调用。
                executionResult =
                        toolRegistry.execute(
                                effectiveToolCall
                        );

                // 对已经执行的工具触发 AfterToolUse Hook。
                // 设计意图：被权限拒绝的工具没有真实执行结果，不应触发后置 Hook。
                HookEffect afterEffect =
                        hookRegistry.triggerAfterToolUse(
                                effectiveToolCall,
                                executionResult.content()
                        );

                // 合并前置和后置 Hook 产生的效果。
                combinedEffect =
                        beforeEffect.and(
                                afterEffect
                        );

                // 在后置 Hook 修改输出时保留原工具的成功或失败状态。
                if (afterEffect.updatedOutput() != null) {
                    executionResult =
                            new ToolExecutionResult(
                                    afterEffect.updatedOutput(),
                                    executionResult.error()
                            );
                }
            }

            // 读取 Hook 处理完成后的工具结果文本。
            String output =
                    executionResult.content();

            // 在 Hook 提供附加上下文时把它们追加到工具结果文本。
            // 设计意图：附加上下文只扩充文本，不改变工具原有的成功或失败状态。
            if (!combinedEffect.additionalContexts()
                    .isEmpty()) {
                output +=
                        "\n\nHook 追加上下文：\n"
                                + String.join(
                                        "\n",
                                        combinedEffect.additionalContexts()
                                );
            }

            // 返回 Hook 处理完成且保留原错误状态的工具结果。
            return new ToolExecutionResult(
                    output,
                    executionResult.error()
            );
        } catch (RuntimeException exception) {
            // 记录 Hook、权限或工具管线抛出的未处理异常。
            // 设计意图：Future 边界统一兜住执行管线，完整异常进入日志，模型只接收可配对的精简错误。
            LOGGER.error(
                    "工具调用 {}({}) 的执行管线发生未处理异常",
                    toolUse.name(),
                    toolUse.id(),
                    exception
            );

            // 读取异常携带的可见错误消息。
            String exceptionMessage =
                    exception.getMessage();

            // 在异常消息为空时使用异常类型名生成稳定错误详情。
            String detail =
                    exceptionMessage == null
                            || exceptionMessage.isBlank()
                            ? exception.getClass()
                                    .getSimpleName()
                            : exceptionMessage;

            // 返回与原工具调用配对的管线失败结果。
            return ToolExecutionResult.failure(
                    "Error: tool pipeline failed unexpectedly: "
                            + detail
            );
        }
    }

    /**
     * 按模型调用顺序等待工具结果并应用现有结果预算。
     *
     * @param executions 已按安全级别调度的工具调用
     * @return 与 tool_use 顺序一致的 tool_result 内容块
     */
    private List<ContentBlockParam> collectToolResults(
            List<PendingToolExecution> executions
    ) {
        // 初始化按模型调用顺序构造的 tool_result 列表。
        List<ContentBlockParam> toolResults =
                new ArrayList<>();

        // 依次等待每个已调度工具任务并构造协议结果。
        for (PendingToolExecution execution : executions) {
            // 等待当前顺序位置对应的工具执行结果。
            // 设计意图：安全批次仍可并发运行，join 只约束结果的交付顺序。
            ToolExecutionResult result =
                    execution.resultFuture()
                            .join();

            // 把内部工具结果封装为 Anthropic tool_result。
            ToolResultBlockParam toolResult =
                    ToolResultBlockParam.builder()
                            .toolUseId(
                                    execution.toolUse()
                                            .id()
                            )
                            .content(
                                    result.content()
                            )
                            .isError(
                                    result.error()
                            )
                            .build();

            // 把 tool_result 加入最终协议内容列表。
            toolResults.add(
                    ContentBlockParam.ofToolResult(
                            toolResult
                    )
            );
        }

        // 对 Hook 处理后的最终工具结果应用上下文预算。
        // 设计意图：预算失败直接向上抛出，不伪造已经成功工具的状态。
        return contextManager.applyToolResultBudget(
                toolResults
        );
    }

    /**
     * 提交流中断前的完整 assistant 块、工具结果和恢复说明。
     *
     * @param messages 当前真实会话历史
     * @param turn 本次中断流保留下来的稳定内容
     */
    private void commitInterruptedTurn(
            List<MessageParam> messages,
            StreamTurn turn
    ) {
        // 初始化流中断后需要提交的 user 内容列表。
        List<ContentBlockParam> userContent =
                new ArrayList<>();

        // 在中断前存在完整工具调用时收集并追加对应结果。
        if (!turn.toolExecutions()
                .isEmpty()) {
            userContent.addAll(
                    collectToolResults(
                            turn.toolExecutions()
                    )
            );
        }

        // 在全部工具结果之后追加流中断恢复说明。
        // 设计意图：Anthropic 要求 tool_result 位于同一 user 消息的普通文本之前。
        userContent.add(
                textBlock(
                        STREAM_INTERRUPTION_PROMPT
                )
        );

        // 把工具结果和恢复说明封装为 user 消息。
        MessageParam recoveryMessage =
                MessageParam.builder()
                        .role(
                                MessageParam.Role.USER
                        )
                        .contentOfBlockParams(
                                userContent
                        )
                        .build();

        // 初始化本次中断后可以安全提交的完整消息列表。
        List<MessageParam> completedTurn =
                new ArrayList<>();

        // 在存在完整 assistant 内容块时先加入对应历史消息。
        // 设计意图：第一个内容块尚未关闭时不能构造空 assistant 消息。
        if (!turn.assistantContent()
                .isEmpty()) {
            completedTurn.add(
                    toAssistantMessage(
                            turn.assistantContent()
                    )
            );
        }

        // 把恢复 user 消息追加到本轮完整消息列表。
        completedTurn.add(
                recoveryMessage
        );

        // 把准备完成的中断恢复消息统一提交到真实会话历史。
        // 设计意图：所有结果准备成功后统一提交，避免留下不完整的协议消息对。
        messages.addAll(
                completedTurn
        );
    }

    /**
     * 构造恢复说明使用的普通文本内容块。
     *
     * @param text 需要进入 user 消息的说明
     * @return Anthropic 普通文本内容块
     */
    private static ContentBlockParam textBlock(
            String text
    ) {
        // 把普通字符串封装为 Anthropic 文本内容块。
        return ContentBlockParam.ofText(
                TextBlockParam.builder()
                        .text(
                                text
                        )
                        .build()
        );
    }

    /**
     * 一个已经完整关闭并提交到有序并发调度器的工具调用。
     *
     * @param toolUse 完整工具请求
     * @param resultFuture 按安全级别调度的执行结果
     */
    private record PendingToolExecution(
            ToolUseBlock toolUse,
            CompletableFuture<ToolExecutionResult> resultFuture
    ) {
    }

    /**
     * 一次模型流可以安全交给主循环消费的稳定结果。
     *
     * @param assistantContent 已经完整关闭的 assistant 内容块
     * @param text 可以进入最终返回值的完整文本块
     * @param toolExecutions 按模型调用顺序保存的工具 Future
     * @param reachedOutputLimit 正常流是否达到输出上限
     * @param interrupted 本次流是否在 message_stop 前中断
     */
    private record StreamTurn(
            List<ContentBlockParam> assistantContent,
            String text,
            List<PendingToolExecution> toolExecutions,
            boolean reachedOutputLimit,
            boolean interrupted
    ) {
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
        // 读取 SDK 异常中的服务端响应文本并统一转换为小写。
        // 设计意图：使用 ROOT 区域规则，保证大小写转换不受运行机器语言环境影响。
        String errorMessage =
                exception.getMessage()
                        .toLowerCase(
                                Locale.ROOT
                        );

        // 匹配 Anthropic 和百炼已经确认的输入上下文超限文本。
        // 设计意图：只识别明确错误文本，不把其他 BadRequestException 错判为可恢复错误。
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
     * @return 可以直接发送给模型的请求
     */
    private MessageCreateParams createRequest(
            List<MessageParam> messages,
            String turnContext
    ) {
        // 刷新 MODEL_CALL 生命周期的 System Prompt Item。
        // 设计意图：普通调用和上下文超限后的恢复重试都会从此入口取得最新提示词。
        SystemPrompt systemPrompt =
                systemPromptManager.refreshFrom(
                        RefreshScope.MODEL_CALL,
                        runtimeContext
                );

        // 使用最新完整 System Prompt、真实历史和工具定义初始化请求构建器。
        MessageCreateParams.Builder request =
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .system(
                                systemPrompt.content()
                        )
                        .messages(messages)
                        .tools(
                                toolRegistry.definitions()
                        );

        // 在存在本轮召回内容时只把它追加到当前 API 请求。
        // 设计意图：不修改 messages，避免临时记忆进入压缩、后续历史和回合结束后的记忆提取。
        if (!turnContext.isBlank()) {
            request.addUserMessage(
                    "<system-reminder>\n"
                            + turnContext
                            + "\n</system-reminder>"
            );
        }

        // 构建并返回不再修改的模型请求。
        return request.build();
    }

}
