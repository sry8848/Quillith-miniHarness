// 声明手写 Agent 主循环所在的包。
package dev.learn.agent.manual;

// 引入模型 SDK、项目组件和日志依赖。
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.context.ContextManager;
import dev.learn.agent.manual.context.ConversationCompactor;
import java.util.Optional;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.output.StreamOutputPrinter;
import dev.learn.agent.manual.recovery.ModelRequestRecoveryManager;
import dev.learn.agent.manual.recovery.ModelRequestRecoveryState;
import dev.learn.agent.manual.session.ConversationState;
import dev.learn.agent.manual.session.NoOpTurnJournal;
import dev.learn.agent.manual.session.SessionPersistenceException;
import dev.learn.agent.manual.session.TurnJournal;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.SystemPrompt;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.telemetry.GenAiSpanAttributes;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.tool.ToolCallDispatcher;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.ToolRegistry;
import dev.learn.agent.manual.tool.approval.ToolApprovalGate;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 引入集合、并发和回调所需的 JDK 类型。
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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

    // 定义每次模型请求允许生成的 thinking 预算。
    // 设计意图：启用固定 thinking 内容并保持普通输出仍有充足的生成空间。
    private static final long THINKING_BUDGET_TOKENS =
            8_192;

    // 定义同一任务因输出截断而允许续写的最大次数。
    private static final int MAX_OUTPUT_CONTINUATIONS =
            3;

    // 保留现有 SSE Provider Error 恢复状态机的最大次数，不与 HTTP 请求 Handler 共用状态。
    private static final int MAX_SSE_CONTENT_REJECTION_RECOVERIES =
            2;

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

    // 定义模型输出被服务端内容检查拒绝时使用的安全恢复说明。
    private static final String OUTPUT_CONTENT_REJECTION_PROMPT =
            "The provider rejected the previous model output. Continue "
                    + "the task with a safe, high-level response and do not "
                    + "repeat the rejected wording.";

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

    // 保存生成动态 System Prompt 时读取的当前 Session 状态。
    private final SessionState sessionState;

    // 保存可供模型调用的工具注册表。
    private final ToolRegistry toolRegistry;

    // 保存工具进入 ToolRegistry 前的统一审批边界。
    private final ToolApprovalGate approvalGate;

    // 保存当前 AgentLoop 独有的后台任务生命周期调度器。
    private final BackgroundTaskScheduler backgroundScheduler;

    // 保存模型调用和工具调用生命周期中的 Hook 注册表。
    private final HookRegistry hookRegistry;

    // 保存父子 Loop 共用的 ToolResult 字符预算与文件落盘管理器。
    private final ContextManager contextManager;
    // 仅持久化父会话装配压缩能力。
    private final Optional<ConversationCompactor> conversationCompactor;

    // 保存模型流、工具调用和工具结果共用的输出观察边界。
    // 设计意图：父 Agent 和子 Agent 通过不同打印器实现不同终端策略，但保留同一解析流程。
    private final StreamOutputPrinter outputPrinter;

    // 保存 Agent 主循环允许执行的最大模型轮次。
    // 设计意图：只限制模型持续请求工具的业务轮次，不统计摘要请求和恢复重试，也不作为 API 计费上限。
    private final int maxModelRounds;

    // 保存模型请求级错误的按序恢复分派器。
    private final ModelRequestRecoveryManager recoveryManager;

    // 父 Agent 写 SQLite，子 Agent 使用 NoOp Journal 保持历史隔离。
    private final TurnJournal turnJournal;

    /**
     * 创建模型调用和工具执行循环。
     *
     * @param client Anthropic 客户端
     * @param model 模型标识
     * @param systemPromptManager 动态 System Prompt 管理器
     * @param sessionState 当前 Session 状态
     * @param toolRegistry 工具注册表
     * @param approvalGate 工具执行前的审批边界
     * @param hookRegistry Hook 注册表
     * @param contextManager 上下文治理管理器
     * @param outputPrinter 当前 Agent 的流式输出打印器
     * @param backgroundScheduler 当前 AgentLoop 的后台调度器
     * @param maxModelRounds 最大业务模型轮次
     * @param recoveryManager 模型请求级错误恢复分派器
     * @throws NullPointerException 任一对象依赖为 null 时抛出
     * @throws IllegalArgumentException 最大业务模型轮次不大于 0 时抛出
     */
    public AgentLoop(
            AnthropicClient client,
            String model,
            SystemPromptManager systemPromptManager,
            SessionState sessionState,
            ToolRegistry toolRegistry,
            ToolApprovalGate approvalGate,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            StreamOutputPrinter outputPrinter,
            BackgroundTaskScheduler backgroundScheduler,
            int maxModelRounds,
            ModelRequestRecoveryManager recoveryManager
    ) {
        this(client, model, systemPromptManager, sessionState, toolRegistry, approvalGate,
                hookRegistry, contextManager, outputPrinter, backgroundScheduler,
                maxModelRounds, recoveryManager, new NoOpTurnJournal(), Optional.empty());
    }

    /**
     * 创建带有 Session 流式持久化边界的模型循环。
     */
    public AgentLoop(
            AnthropicClient client,
            String model,
            SystemPromptManager systemPromptManager,
            SessionState sessionState,
            ToolRegistry toolRegistry,
            ToolApprovalGate approvalGate,
            HookRegistry hookRegistry,
            ContextManager contextManager,
            StreamOutputPrinter outputPrinter,
            BackgroundTaskScheduler backgroundScheduler,
            int maxModelRounds,
            ModelRequestRecoveryManager recoveryManager,
            TurnJournal turnJournal,
            Optional<ConversationCompactor> conversationCompactor
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

        // 校验并保存当前 Session 状态。
        this.sessionState =
                Objects.requireNonNull(
                        sessionState,
                        "SessionState 不能为空"
                );

        // 校验并保存工具注册表。
        this.toolRegistry =
                Objects.requireNonNull(
                        toolRegistry,
                        "ToolRegistry 不能为空"
                );

        // 校验并保存工具执行前的审批边界。
        this.approvalGate =
                Objects.requireNonNull(
                        approvalGate,
                        "ToolApprovalGate 不能为空"
                );

        // 保存当前 AgentLoop 独有的后台任务调度器。
        this.backgroundScheduler =
                Objects.requireNonNull(
                        backgroundScheduler,
                        "BackgroundTaskScheduler 不能为空"
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

        // 保存当前 Agent 的输出边界，模型解析不再依赖普通文本 Consumer。
        this.outputPrinter =
                Objects.requireNonNull(
                        outputPrinter,
                        "StreamOutputPrinter 不能为空"
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

        // 保存模型请求恢复分派器；具体错误规则不进入 AgentLoop。
        this.recoveryManager =
                Objects.requireNonNull(
                        recoveryManager,
                        "ModelRequestRecoveryManager 不能为空"
                );

        this.turnJournal = Objects.requireNonNull(turnJournal, "turnJournal 不能为空");
        this.conversationCompactor = conversationCompactor;
    }

    /**
     * 持续调用模型，直到模型不再请求工具。
     *
     * @param messages 可修改的会话消息历史
     * @param turnContext 只进入本轮模型请求、不写入会话历史的临时上下文
     * @return 当前任务最终 assistant 回复中的文本结论
     */
    @WithSpan("agent.run")
    public String run(
            List<MessageParam> messages,
            String turnContext
    ) {
        ConversationState conversationState = new ConversationState();
        conversationState.restore(messages, messages, -1);
        String output = run(conversationState, turnContext);
        messages.clear();
        messages.addAll(conversationState.modelContext());
        return output;
    }

    /**
     * 持续调用模型，并维护父 Session 的完整历史和活动上下文。
     *
     * @param conversationState 当前会话的双视图状态
     * @param turnContext 只进入本轮模型请求的临时上下文
     * @return 当前任务最终 assistant 回复中的文本结论
     */
    @WithSpan("agent.run")
    public String run(
            ConversationState conversationState,
            String turnContext
    ) {
        return runInternal(
                conversationState,
                turnContext,
                null
        );
    }

    /**
     * 执行真实请求前治理，并提交一个已经完成的 assistant 文本。
     *
     * @param conversationState 当前会话的双视图状态
     * @param turnContext 只进入本轮模型请求的临时上下文
     * @param assistantText 已经完成的固定 assistant 文本
     * @return 已经提交的固定 assistant 文本
     */
    @WithSpan("agent.run.recorded")
    public String runRecorded(
            ConversationState conversationState,
            String turnContext,
            String assistantText
    ) {
        Objects.requireNonNull(
                assistantText,
                "assistantText 不能为 null"
        );
        return runInternal(
                conversationState,
                turnContext,
                assistantText
        );
    }

    /**
     * 执行 live 和 recorded Turn 共用的请求前生命周期。
     *
     * @param conversationState 当前会话的双视图状态
     * @param turnContext 只进入本轮模型请求的临时上下文
     * @param recordedAssistant 固定 assistant；{@code null} 表示调用真实模型
     * @return 当前 Turn 的最终 assistant 文本
     */
    private String runInternal(
            ConversationState conversationState,
            String turnContext,
            String recordedAssistant
    ) {
        Objects.requireNonNull(conversationState, "conversationState 不能为空");
        List<MessageParam> messages = conversationState.modelContext();

        // 校验本轮临时上下文已由调用方明确提供。
        // 设计意图：区分“没有召回记忆”和“集成代码遗漏参数”，让契约错误在调用边界暴露。
        Objects.requireNonNull(
                turnContext,
                "turnContext 不能为 null"
        );

        // 输出当前 Agent Span 的 Trace ID，用于把终端记录与 Jaeger 查询结果关联起来。
        Span currentAgentSpan =
                Span.current();
        if (currentAgentSpan.getSpanContext().isValid()) {
            outputPrinter.printLocalMessage(
                    "\n[Trace ID] "
                            + currentAgentSpan.getSpanContext()
                            .getTraceId()
                            + "\n"
            );
        }

        // 刷新本轮以及更短生命周期的 System Prompt Item。
        // 设计意图：一次 run 对应一个新用户回合或子任务，必须从该边界更新动态提示词。
        systemPromptManager.refreshFrom(
                RefreshScope.TURN,
                sessionState
        );

        // 初始化当前回合已经执行的输出续写次数。
        int outputContinuationCount =
                0;

        // 初始化跨续写响应累积的完整文本片段。
        List<String> continuedOutputParts =
                new ArrayList<>();

        // 保留现有 SSE 输出拒绝恢复计数；HTTP 请求级恢复次数由专属 State 保存。
        int contentRejectionCount =
                0;

        // 创建当前连续模型请求错误恢复链的初始状态。
        ModelRequestRecoveryState requestRecoveryState =
                new ModelRequestRecoveryState(
                        messages
                );

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
                appendCommitted(
                        conversationState,
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

            // 1. 自动入口只判断阈值，完整压缩提交交给共享 Compactor。
            if (conversationCompactor.isPresent()) {
                ConversationCompactor compactor = conversationCompactor.get();
                if (compactor.shouldAutoCompact(messages)) {
                    compactor.compact(conversationState);
                }
            }

            // 7. recorded Turn 在真实请求前治理完成后提交固定 assistant。
            if (recordedAssistant != null) {
                MessageParam assistantMessage =
                        MessageParam.builder()
                                .role(
                                        MessageParam.Role.ASSISTANT
                                )
                                .content(
                                        recordedAssistant
                                )
                                .build();
                commitCompletedTurn(
                        conversationState,
                        List.of(
                                assistantMessage
                        )
                );

                // 固定历史已经是完整响应，不再执行 Provider、工具或 Stop Hook。
                return recordedAssistant;
            }

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
                try {
                    turn =
                            runIteration(
                                    createRequest(
                                            messages,
                                            turnContext
                                    )
                            );
                } catch (AnthropicServiceException exception) {
                    // HTTP Provider Error 交给请求级 Handler；成功恢复后回到同一主循环。
                    if (recoveryManager.tryRecover(
                            exception,
                            requestRecoveryState
                    )) {
                        conversationState.replaceModelContext(
                                messages,
                                conversationState.latestSequence()
                        );
                        turnJournal.saveContextCheckpoint(conversationState);
                        continue;
                    }

                    // 未识别或不可恢复的异常保持原类型和原实例向外抛出。
                    throw exception;
                }

                // SSE Provider Error 携带流中已经完成的内容块和工具执行状态。
                if (turn.providerError() != null) {
                    if (!recoverFromProviderError(
                            conversationState,
                            requestRecoveryState,
                            turn,
                            turn.providerError(),
                            contentRejectionCount
                    )) {
                        throw turn.providerError();
                    }

                    // 本次恢复请求已消耗一次内容拒绝恢复机会。
                    contentRejectionCount++;
                    continue;
                }

                // 本次请求已被 Provider 接受：旧恢复链结束，后续错误从新 State 开始。
                requestRecoveryState =
                        new ModelRequestRecoveryState(
                                messages
                        );

                // 保留原有 SSE 连续拒绝计数重置。
                contentRejectionCount =
                        0;

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
                            conversationState,
                            turn
                    );

                    // 中断恢复提交的工具结果尚未被下一次模型请求确认，继续保留其 pending ID。
                    requestRecoveryState.recordPendingToolResults(
                            turn.toolExecutions()
                                    .stream()
                                    .map(
                                            execution -> execution.toolUse().id()
                                    )
                                    .toList()
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
                    commitCompletedTurn(
                            conversationState,
                            List.of(toAssistantMessage(turn.assistantContent()))
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
                    outputPrinter.printLocalMessage(
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
                appendCommitted(
                        conversationState,
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
                    appendCommitted(
                            conversationState,
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
                    turn.toolResults();

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
            commitCompletedTurn(
                    conversationState,
                    List.of(
                            toAssistantMessage(
                                    turn.assistantContent()
                            ),
                            resultMessage
                    )
            );

            // 记录这批结果，Provider 在下一次请求拒绝输入时只替换对应工具结果。
            requestRecoveryState.recordPendingToolResults(
                    turn.toolExecutions()
                            .stream()
                            .map(
                                    execution -> execution.toolUse().id()
                            )
                            .toList()
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
        outputPrinter.printLocalMessage(
                "\n\n" + error
        );

        // 返回业务轮次耗尽错误作为本次任务结果。
        return error;
    }

    /**
     * 消费一次模型事件流，并在内容块关闭时按安全级别调度完整工具调用。
     *
     * 正常结束和中断恢复共用同一份完整内容块；
     * 尚未收到 content_block_stop 的当前块不会进入结果。
     *
     * @param request 已构造完成的模型请求
     * @return 本次模型流可安全进入历史的内容和工具执行
     */
    @WithSpan("agent.iteration")
    private StreamTurn runIteration(
            MessageCreateParams request
    ) {
        StreamTurn turn;
        Context iterationContext = Context.current();

        try (ToolCallDispatcher toolDispatcher =
                     new ToolCallDispatcher(
                             toolRegistry,
                             backgroundScheduler
                     )) {
            turn = streamModel(request, toolDispatcher, iterationContext);
        }

        // 前台调度器关闭后，所有本轮工具已经结束，可以在 iteration 内按原顺序生成结果。
        return turn.withToolResults(
                collectToolResults(
                        turn.toolExecutions()
                )
        );
    }

    @WithSpan("llm.call")
    private StreamTurn streamModel(
            MessageCreateParams request,
            ToolCallDispatcher toolDispatcher,
            Context iterationContext
    ) {
        // 使用当前模型名称区分同一 Agent 回合中的不同模型请求。
        Span.current().updateName(
                "llm.call " + model
        );

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

        // 初始化 thinking 块的签名增量缓冲区，文本和签名必须分别保存。
        StringBuilder currentThinkingSignature =
                null;

        // 初始化是否已经收到 message_stop 的状态。
        boolean messageStopped =
                false;

        // 初始化本次响应是否达到模型输出上限的状态。
        boolean reachedOutputLimit =
                false;

        // 创建并消费模型响应流，把可恢复的流异常转换成稳定结果。
        try {
                // 发送已构造的请求并取得模型事件流。
                HttpResponseFor<StreamResponse<RawMessageStreamEvent>> response =
                        client.messages()
                                .withRawResponse()
                                .createStreaming(
                                        request
                                );

                // HTTP 响应和 SSE 流必须在 LLM 方法内关闭，避免 llm.call 覆盖 Tool 等待。
                try (response; StreamResponse<RawMessageStreamEvent> streamResponse =
                        response.parse()) {
                    response.requestId().ifPresent(
                            GenAiSpanAttributes::recordResponseId
                    );

                    // 创建按服务端顺序读取事件的迭代器。
                    Iterator<RawMessageStreamEvent> events =
                            streamResponse.stream()
                                    .iterator();

                    // 按 Anthropic 事件顺序消费事件并构造唯一的当前内容块。
                    while (events.hasNext()) {
                        // 读取下一个模型流事件。
                        RawMessageStreamEvent event =
                                events.next();

                        // 1. 读取 Provider 在 message_start 返回的模型和输入用量。
                        if (event.isMessageStart()) {
                            GenAiSpanAttributes.recordMessageStart(
                                    model,
                                    event.asMessageStart().message()
                            );
                            continue;
                        }

                        // 2. 在 content_block_start 到达时初始化当前内容块。
                        if (event.isContentBlockStart()) {
                            // 保存新开始的内容块类型和初始数据。
                            currentBlock =
                                    event.asContentBlockStart()
                                            .contentBlock();

                            // 根据文本、thinking 或工具调用类型初始化当前内容缓冲区。
                            if (currentBlock.isText()) {
                                // 保存文本起始块中的初始内容，并处理可能已经返回的非空文本。
                                String initialText =
                                        currentBlock.asText()
                                                .text();
                                currentContent =
                                        new StringBuilder(
                                                initialText
                                        );
                                currentThinkingSignature =
                                        null;
                                outputPrinter.startTextBlock();
                                if (!initialText.isEmpty()) {
                                    outputPrinter.printTextDelta(
                                            initialText
                                    );
                                }
                            } else if (currentBlock.isThinking()) {
                                // 保存 thinking 起始块中的文本和签名初始值。
                                String initialThinking =
                                        currentBlock.asThinking()
                                                .thinking();
                                currentContent =
                                        new StringBuilder(
                                                initialThinking
                                        );
                                currentThinkingSignature =
                                        new StringBuilder(
                                                currentBlock.asThinking()
                                                        .signature()
                                        );
                                outputPrinter.startThinkingBlock();
                                if (!initialThinking.isEmpty()) {
                                    outputPrinter.printThinkingDelta(
                                            initialThinking
                                    );
                                }
                            } else if (currentBlock.isToolUse()) {
                                currentContent =
                                        new StringBuilder();
                                currentThinkingSignature =
                                        null;
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

                            // 根据文本、thinking、签名或工具 JSON 类型处理当前增量。
                            if (delta.isText()) {
                                // 读取本次文本增量。
                                String text =
                                        delta.asText()
                                                .text();

                                // 把文本增量追加到当前内容缓冲区。
                                currentContent.append(
                                        text
                                );

                                // 把正文增量立即展示，工具 JSON 则只在内存中累积。
                                // 设计意图：正文需要即时展示，半截工具 JSON 不具备可读性。
                                outputPrinter.printTextDelta(
                                        text
                                );
                            } else if (delta.isThinking()) {
                                // 读取并累积 thinking 文本增量。
                                String thinking =
                                        delta.asThinking()
                                                .thinking();
                                currentContent.append(
                                        thinking
                                );

                                // thinking 与普通文本一样按增量完整展示。
                                outputPrinter.printThinkingDelta(
                                        thinking
                                );
                            } else if (delta.isSignature()) {
                                // 签名只进入当前 thinking 块，不作为可读文本打印。
                                currentThinkingSignature.append(
                                        delta.asSignature()
                                                .signature()
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
                            // 根据当前块是文本、thinking 还是工具调用生成稳定内容。
                            if (currentBlock.isText()) {
                                // 读取已经完整关闭的文本块内容。
                                String text =
                                        currentContent.toString();

                                // 先保存完整文本块，再暴露给本轮后续协议处理。
                                ContentBlockParam completedTextBlock = textBlock(text);
                                List<ContentBlockParam> durableContent = new ArrayList<>(assistantContent);
                                durableContent.add(completedTextBlock);
                                turnJournal.recordClosedAssistantContent(durableContent);
                                assistantContent.add(completedTextBlock);

                                // 把完整文本加入最终返回值候选列表。
                                completedText.add(
                                        text
                                );
                            } else if (currentBlock.isThinking()) {
                                // 使用完整 thinking 文本和签名构造可回传的 assistant 内容块。
                                ThinkingBlockParam thinkingBlock =
                                        ThinkingBlockParam.builder()
                                                .thinking(
                                                        currentContent.toString()
                                                )
                                                .signature(
                                                        currentThinkingSignature
                                                                .toString()
                                                )
                                                .build();
                                ContentBlockParam completedThinkingBlock =
                                        ContentBlockParam.ofThinking(thinkingBlock);
                                List<ContentBlockParam> durableContent = new ArrayList<>(assistantContent);
                                durableContent.add(completedThinkingBlock);
                                turnJournal.recordClosedAssistantContent(durableContent);
                                assistantContent.add(completedThinkingBlock);
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

                                ContentBlockParam completedToolBlock =
                                        ContentBlockParam.ofToolUse(completedTool.toParam());
                                List<ContentBlockParam> durableContent = new ArrayList<>(assistantContent);
                                durableContent.add(completedToolBlock);

                                // durable tool_use 和 NOT_STARTED 后才允许调度器启动工具。
                                turnJournal.recordToolUse(durableContent, completedTool.id());
                                assistantContent.add(completedToolBlock);
                                outputPrinter.printToolCall(completedTool);

                                // 对合法输入按执行模式分流，对非法输入直接创建失败结果。
                                CompletableFuture<ToolExecutionResult> future;

                                if (validInput) {
                                    // 分流器只读取模式，真实执行仍走 AgentLoop 公共工具管线。
                                    future =
                                            toolDispatcher.submit(
                                                    ToolCall.from(
                                                            completedTool
                                                    ),
                                                    () -> executeInIteration(
                                                            iterationContext,
                                                            completedTool
                                                    )
                                            );
                                } else {
                                    ToolExecutionResult invalidResult = ToolExecutionResult.failure(
                                            "Error: tool input must be a valid JSON object."
                                    );
                                    turnJournal.completeTool(completedTool.id(), invalidResult);
                                    future = CompletableFuture.completedFuture(invalidResult);
                                }

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

                            // 清除已经关闭 thinking 块的签名缓冲区。
                            currentThinkingSignature =
                                    null;

                            // 当前完整块已提交，继续读取下一个流事件。
                            continue;
                        }

                        // 在 message_delta 到达时记录模型停止原因。
                        if (event.isMessageDelta()) {
                            GenAiSpanAttributes.recordMessageDelta(
                                    event.asMessageDelta().usage(),
                                    event.asMessageDelta()
                                            .delta()
                                            .stopReason()
                                            .map(StopReason::asString)
                                            .orElse(null)
                            );

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
                        false,
                        null,
                        List.of()
                );
            } catch (SseException exception) {
                GenAiSpanAttributes.markLlmFailure();
                // 保留 SSE Provider Error 及此前已经完成的工具调用，交给业务回合恢复判断。
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
                        false,
                        exception,
                        List.of()
                );
            } catch (AnthropicIoException
                     | AnthropicInvalidDataException exception) {
                GenAiSpanAttributes.markLlmFailure();
                // 把可恢复的模型流异常转换为仅含完整内容块的中断结果。
                return interruptedTurn(
                        assistantContent,
                        completedText,
                        toolExecutions,
                        exception
                );
            }
    }

    /**
     * 把流异常转换成只包含完整内容块的可续接结果。
     *
     * @param assistantContent 已经完整关闭的 assistant 内容块
     * @param completedText 已经完整关闭的文本块
     * @param toolExecutions 已经启动的工具调用
     * @param cause 流中断原因
     * @return 不包含当前未关闭内容块的中断结果
     */
    private StreamTurn interruptedTurn(
            List<ContentBlockParam> assistantContent,
            List<String> completedText,
            List<PendingToolExecution> toolExecutions,
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
        outputPrinter.printLocalMessage(
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
                true,
                null,
                List.of()
        );
    }

    /**
     * 保留现有 SSE Provider Error 的处理状态机。
     *
     * <p>HTTP 模型请求异常已经由 ModelRequestRecoveryManager 处理；本方法只接收
     * 已经从 SSE 流转换出的 Provider Error，不把流式 partial turn 纳入请求级 Handler。</p>
     *
     * @param messages 当前真实会话历史
     * @param recoveryState 当前连续模型请求错误恢复链状态
     * @param providerErrorTurn SSE Provider Error 携带的已完成流状态
     * @param exception SDK 提供的原始 Provider Error
     * @param previousRejectionCount 当前业务回合在本次拒绝前的连续拒绝次数
     * @return 本次 SSE 错误已构造恢复请求时返回 true
     */
    private boolean recoverFromProviderError(
            ConversationState conversationState,
            ModelRequestRecoveryState recoveryState,
            StreamTurn providerErrorTurn,
            AnthropicServiceException exception,
            int previousRejectionCount
    ) {
        List<MessageParam> messages = conversationState.modelContext();

        // 只有服务端明确返回 DataInspectionFailed 才进入内容拒绝恢复。
        if (!isDataInspectionFailure(
                exception
        )) {
            return false;
        }

        // 根据服务端真实消息区分输入拒绝、输出拒绝和未知方向。
        ContentRejectionDirection direction =
                contentRejectionDirection(
                        exception
                );

        // SSE 输出拒绝可能已经启动工具，先提交完整工具事实再追加恢复说明。
        if (direction
                == ContentRejectionDirection.OUTPUT
                && providerErrorTurn != null) {
            boolean shouldRetry =
                    previousRejectionCount
                            < MAX_SSE_CONTENT_REJECTION_RECOVERIES;

            commitProviderErrorToolFacts(
                    conversationState,
                    providerErrorTurn,
                    shouldRetry
                            ? outputContentRejectionMessage(
                            exception
                    )
                            : null
            );

            // 当前 SSE 请求已经消费了此前的工具结果，后续只追踪本次恢复提交的新结果。
            recoveryState.recordPendingToolResults(
                    providerErrorTurn.toolExecutions()
                            .stream()
                            .map(
                                    execution -> execution.toolUse().id()
                            )
                            .toList()
            );

            if (!shouldRetry) {
                return false;
            }

            // 记录恢复行为，具体模型请求由当前业务回合继续执行。
            LOGGER.warn(
                    "模型输出被 Provider 拒绝，第 {}/{} 次恢复",
                    previousRejectionCount + 1,
                    MAX_SSE_CONTENT_REJECTION_RECOVERIES
            );
            return true;
        }

        // SSE 用户输入、方向不明确或无法安全定位的内容拒绝不自动重试。
        return false;
    }

    /**
     * 提交 SSE Provider Error 前已经执行的完整工具事实，并可追加模型恢复说明。
     *
     * @param messages 当前真实会话历史
     * @param providerErrorTurn Provider Error 前已闭合的流内容和工具执行
     * @param recoveryPrompt 需要交给模型的恢复说明；第三次拒绝时为 null
     */
    private void commitProviderErrorToolFacts(
            ConversationState conversationState,
            StreamTurn providerErrorTurn,
            String recoveryPrompt
    ) {
        // 只保留 thinking 和 tool_use，避免把被拒绝的普通输出写回历史。
        List<ContentBlockParam> safeAssistantContent =
                providerErrorTurn.assistantContent()
                        .stream()
                        .filter(
                                block -> block.isThinking()
                                        || block.isToolUse()
                        )
                        .toList();

        // 没有工具调用时只追加输出恢复说明，不构造空 assistant 消息。
        if (providerErrorTurn.toolExecutions()
                .isEmpty()) {
            if (recoveryPrompt != null) {
                commitCompletedTurn(
                        conversationState,
                        List.of(MessageParam.builder()
                                .role(
                                        MessageParam.Role.USER
                                )
                                .content(
                                        recoveryPrompt
                                )
                                .build())
                );
            }
            return;
        }

        // 等待并预算已经启动的工具结果，保持原 tool_use_id 的协议配对。
        List<ContentBlockParam> userContent =
                new ArrayList<>(
                        providerErrorTurn.toolResults()
                );

        // Anthropic 要求 tool_result 在同一 user 消息的普通文本之前。
        if (recoveryPrompt != null) {
            userContent.add(
                    textBlock(
                            recoveryPrompt
                    )
            );
        }

        // 先提交完整 assistant 工具调用，再提交对应结果和可选恢复说明。
        commitCompletedTurn(
                conversationState,
                List.of(
                        toAssistantMessage(safeAssistantContent),
                        MessageParam.builder()
                        .role(
                                MessageParam.Role.USER
                        )
                        .contentOfBlockParams(
                                userContent
                        )
                        .build()
                )
        );
    }

    /**
     * 判断 Provider 是否明确返回内容检查失败。
     */
    private static boolean isDataInspectionFailure(
            AnthropicServiceException exception
    ) {
        String code =
                providerErrorCode(
                        exception
                ).replace(
                        "_",
                        ""
                ).toLowerCase(
                        Locale.ROOT
                );
        return "datainspectionfailed".equals(
                code
        );
    }

    /**
     * 按 Provider 原始错误消息识别内容检查方向；同时出现 input/output 时保持未知。
     */
    private static ContentRejectionDirection contentRejectionDirection(
            AnthropicServiceException exception
    ) {
        String message =
                providerErrorMessage(
                        exception
                ).toLowerCase(
                        Locale.ROOT
                );
        boolean input =
                message.contains(
                        "input"
                );
        boolean output =
                message.contains(
                        "output"
                );

        if (input && !output) {
            return ContentRejectionDirection.INPUT;
        }
        if (output && !input) {
            return ContentRejectionDirection.OUTPUT;
        }
        return ContentRejectionDirection.UNKNOWN;
    }

    /**
     * 构造模型输出被拒绝时的模型可见恢复说明。
     */
    private static String outputContentRejectionMessage(
            AnthropicServiceException exception
    ) {
        return OUTPUT_CONTENT_REJECTION_PROMPT
                + "\n"
                + providerErrorSummary(
                exception
        );
    }

    /**
     * 只把服务端明确返回的错误码和消息交给模型，不把原始工具结果带回请求。
     */
    private static String providerErrorSummary(
            AnthropicServiceException exception
    ) {
        return "Provider error code: "
                + providerErrorCode(
                exception
        )
                + "\nProvider message: "
                + providerErrorMessage(
                exception
        );
    }

    /**
     * 读取 Provider 响应中的错误码，兼容百炼根字段和 Anthropic 嵌套 error.type。
     */
    private static String providerErrorCode(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorBody(
                        exception.body()
                );
        String code =
                readBodyString(
                        body,
                        "code"
                );
        if (!code.isBlank()) {
            return code;
        }

        JsonNode errorObject =
                body == null
                        ? null
                        : body.get(
                                "error"
                        );
        code =
                readBodyString(
                        errorObject,
                        "type"
                );
        if (!code.isBlank()) {
            return code;
        }

        // 百炼兼容端点也可能把 Anthropic 错误类型放在响应根字段 type。
        code =
                readBodyString(
                        body,
                        "type"
                );
        if (!code.isBlank()) {
            return code;
        }

        return exception.errorType()
                .map(
                        type -> type.asString()
                )
                .orElse(
                        "未返回"
                );
    }

    /**
     * 读取 Provider 响应中的服务端错误消息。
     */
    private static String providerErrorMessage(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorBody(
                        exception.body()
                );
        String message =
                readBodyString(
                        body,
                        "message"
                );
        if (!message.isBlank()) {
            return message;
        }

        message =
                readBodyString(
                        body == null
                                ? null
                                : body.get(
                                "error"
                        ),
                        "message"
                );
        if (!message.isBlank()) {
            return message;
        }

        return exception.getMessage() == null
                || exception.getMessage().isBlank()
                ? "未返回"
                : exception.getMessage();
    }

    /**
     * 把 SDK JsonValue 错误体转换为 Jackson 节点。
     */
    private static JsonNode providerErrorBody(
            JsonValue value
    ) {
        if (value == null
                || value.isMissing()
                || value.isNull()) {
            return null;
        }

        try {
            return value.convert(
                    JsonNode.class
            );
        } catch (RuntimeException ignored) {
            // Provider 返回非 JSON 错误体时由上层显示原始异常消息。
            return null;
        }
    }

    /**
     * 从 Jackson 错误节点读取一个字符串字段。
     */
    private static String readBodyString(
            JsonNode object,
            String fieldName
    ) {
        if (object == null
                || !object.isObject()) {
            return "";
        }

        JsonNode field =
                object.get(
                        fieldName
                );
        if (field == null
                || !field.isTextual()) {
            return "";
        }

        return field.textValue();
    }

    /**
     * 内容检查错误来自输入、输出或无法确定的方向。
     */
    private enum ContentRejectionDirection {
        INPUT,
        OUTPUT,
        UNKNOWN
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
    @WithSpan("tool.execute")
    private ToolExecutionResult executeTool(
            ToolUseBlock toolUse
    ) {
        // 使用真实工具名称区分本地工具、task 子 Agent 工具和 MCP 工具。
        Span.current().updateName(
                "tool.execute " + toolUse.name()
        );
        GenAiSpanAttributes.recordToolCall(toolUse);

        // 在工具执行管线边界内捕获未处理的运行时异常。
        try {
            // 把 SDK 工具块转换成 Hook 和工具注册表共用的内部对象。
            ToolCall toolCall =
                    ToolCall.from(
                            toolUse
                    );

            // 触发通用工具执行前 Hook，取得扩展决策和输入修改。
            HookEffect beforeEffect =
                    hookRegistry.triggerBeforeToolUse(
                            toolCall
                    );

            // 声明当前工具的最终执行结果。
            ToolExecutionResult executionResult;

            // 初始化用于汇总前后置 Hook 的组合效果。
            HookEffect combinedEffect =
                    beforeEffect;

            // 根据前置 Hook 的决策选择停止管线或继续执行。
            if (beforeEffect.decision()
                    == HookEffect.Decision.BLOCK) {
                // 为被通用前置 Hook 阻止的工具构造失败结果。
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

                // 审批发生在真实工具执行之前，Gate 不执行工具本身。
                if (approvalGate.approve(effectiveToolCall)) {
                    // 真实 ToolRegistry 可能立刻产生外部副作用，必须先 durable RUNNING。
                    turnJournal.markToolRunning(toolUse.id());

                    // 通过工具注册表执行最终工具调用。
                    executionResult =
                            toolRegistry.execute(
                                    effectiveToolCall
                            );

                    // 对已经执行的工具触发 AfterToolUse Hook。
                    // 设计意图：没有真实执行的审批拒绝不应产生后置工具观测。
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
                } else {
                    // 用户拒绝审批时不进入 ToolRegistry，也不触发 AfterToolUse Hook。
                    executionResult =
                            ToolExecutionResult.failure(
                                    "Permission denied by user"
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
        } catch (SessionPersistenceException exception) {
            // Session durable 失败意味着无法证明工具执行状态，必须中止执行链。
            // 不能把它伪装为模型可见的普通工具失败后继续工作。
            throw exception;
        } catch (RuntimeException exception) {
            // 记录 Hook、审批或工具管线抛出的未处理异常。
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
     * 在 Tool 虚拟线程中恢复所属 iteration 的 Context 后执行工具。
     *
     * @param iterationContext Tool 提交时捕获的 iteration Context
     * @param toolUse 已完整关闭的工具调用
     * @return 当前工具的最终执行结果
     */
    private ToolExecutionResult executeInIteration(
            Context iterationContext,
            ToolUseBlock toolUse
    ) {
        // 1. 在真实执行线程恢复 iteration Context，使工具不继承已结束的 LLM Span。
        try (Scope ignored = iterationContext.makeCurrent()) {
            ToolExecutionResult result = executeTool(toolUse);

            // 1. 结果先落库，Future 完成后主循环才能组装 tool_result。
            turnJournal.completeTool(toolUse.id(), result);
            return result;
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

            // 在应用上下文预算前展示完整工具结果。
            // 设计意图：终端截断只影响展示，不应改变模型协议使用的原始结果。
            outputPrinter.printToolResult(
                    execution.toolUse(),
                    result
            );

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
            ConversationState conversationState,
            StreamTurn turn
    ) {
        // 初始化流中断后需要提交的 user 内容列表。
        List<ContentBlockParam> userContent =
                new ArrayList<>();

        // 在中断前存在完整工具调用时收集并追加对应结果。
        if (!turn.toolExecutions()
                .isEmpty()) {
            userContent.addAll(
                    turn.toolResults()
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
        commitCompletedTurn(conversationState, completedTurn);
    }

    /**
     * 先 durable 再把普通正式消息加入会话双视图。
     */
    private void appendCommitted(
            ConversationState conversationState,
            MessageParam message
    ) {
        // 1. SQLite 成功后才改变内存，避免显示不存在的已提交消息。
        turnJournal.appendCommitted(List.of(message));
        conversationState.appendCommitted(message);
    }

    /**
     * 原子封口一批消息并清理对应的 in-flight 事实。
     */
    private void commitCompletedTurn(
            ConversationState conversationState,
            List<MessageParam> completedTurn
    ) {
        // 1. 先把协议对与 in-flight 清理放进同一个数据库 transaction。
        turnJournal.commitCompletedTurn(completedTurn);
        conversationState.appendCommitted(completedTurn);
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
     * @param providerError SSE 流收到的 Provider Error；正常结果为 null
     * @param toolResults iteration 内按调用顺序准备好的 tool_result
     */
    private record StreamTurn(
            List<ContentBlockParam> assistantContent,
            String text,
            List<PendingToolExecution> toolExecutions,
            boolean reachedOutputLimit,
            boolean interrupted,
            AnthropicServiceException providerError,
            List<ContentBlockParam> toolResults
    ) {
        private StreamTurn withToolResults(
                List<ContentBlockParam> toolResults
        ) {
            return new StreamTurn(
                    assistantContent,
                    text,
                    toolExecutions,
                    reachedOutputLimit,
                    interrupted,
                    providerError,
                    List.copyOf(toolResults)
            );
        }
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
        // 设计意图：每一次真实模型请求都从同一入口取得最新提示词。
        SystemPrompt systemPrompt =
                systemPromptManager.refreshFrom(
                        RefreshScope.MODEL_CALL,
                        sessionState
                );

        // 使用最新完整 System Prompt、真实历史和工具定义初始化请求构建器。
        MessageCreateParams.Builder request =
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .enabledThinking(
                                THINKING_BUDGET_TOKENS
                        )
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
