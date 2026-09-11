package dev.learn.agent.manual;

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.session.ConversationState;
import dev.learn.agent.manual.context.ConversationCompactor;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.telemetry.GenAiSpanAttributes;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 持有父 Agent 的会话历史，并编排一条用户消息的完整 Turn 生命周期。
 */
public final class AgentSession {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    AgentSession.class
            );

    private final AgentLoop agentLoop;
    private final MemoryRuntime memoryRuntime;
    private final HookRegistry hookRegistry;
    private final SystemPromptManager systemPromptManager;
    private final SessionState sessionState;
    private final SessionStore sessionStore;
    private final ConversationState conversationState;
    private boolean persisted;
    private final ConversationCompactor conversationCompactor;

    /**
     * 创建带有显式 Session 存储和双视图状态的父会话。
     */
    public AgentSession(
            AgentLoop agentLoop,
            MemoryRuntime memoryRuntime,
            HookRegistry hookRegistry,
            SystemPromptManager systemPromptManager,
            SessionState sessionState,
            SessionStore sessionStore,
            ConversationState conversationState,
            ConversationCompactor conversationCompactor
    ) {
        this.agentLoop =
                Objects.requireNonNull(
                        agentLoop,
                        "AgentLoop 不能为空"
                );
        this.memoryRuntime =
                Objects.requireNonNull(
                        memoryRuntime,
                        "MemoryRuntime 不能为空"
                );
        this.hookRegistry =
                Objects.requireNonNull(
                        hookRegistry,
                        "HookRegistry 不能为空"
                );
        this.systemPromptManager =
                Objects.requireNonNull(
                        systemPromptManager,
                        "SystemPromptManager 不能为空"
                );
        this.sessionState =
                Objects.requireNonNull(
                        sessionState,
                        "SessionState 不能为空"
                );
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore 不能为空");
        this.conversationState = Objects.requireNonNull(conversationState, "conversationState 不能为空");
        this.conversationCompactor = conversationCompactor;
    }

    /** 手动压缩当前持久化会话；没有新增中间 Turn 时返回 false。 */
    public boolean compact() {
        // 1. 空会话没有数据库记录，命令不创建新的 Session 或 Turn。
        return persisted && conversationCompactor.compact(conversationState);
    }

    /**
     * 按现有父用户 Turn 顺序处理一条消息。
     *
     * @param query 用户输入
     * @return 当前 Turn 的最终 assistant 文本；输入被 Hook 阻止时为空字符串
     * @throws IOException 回答前的记忆召回读取失败
     * @throws AnthropicServiceException Provider Error 无法恢复时，在诊断后抛出
     */
    public String submit(
            String query
    ) throws IOException {
        return submitInternal(
                query,
                null
        );
    }

    /**
     * 按正常 Turn 生命周期提交固定的 user/assistant 历史。
     *
     * @param userText 固定历史中的 user 文本
     * @param assistantText 固定历史中的 assistant 文本
     * @return 已经提交的固定 assistant 文本；输入被 Hook 阻止时为空字符串
     * @throws IOException 回答前的记忆召回读取失败
     */
    public String submitRecorded(
            String userText,
            String assistantText
    ) throws IOException {
        Objects.requireNonNull(
                assistantText,
                "assistantText 不能为 null"
        );
        return submitInternal(
                userText,
                assistantText
        );
    }

    /**
     * 执行 live 和 recorded Turn 共用的 Session 与 Memory 生命周期。
     *
     * @param query 用户输入
     * @param recordedAssistant 固定 assistant；{@code null} 表示调用真实模型
     * @return 当前 Turn 的最终 assistant 文本；输入被 Hook 阻止时为空字符串
     * @throws IOException 回答前的记忆召回读取失败
     */
    @WithSpan("agent.turn")
    private String submitInternal(
            String query,
            String recordedAssistant
    ) throws IOException {
        Objects.requireNonNull(
                query,
                "query 不能为 null"
        );

        // 1. 每次 submit 使用一个新的递增 Turn 序号；未持久化 Session 的首个 Turn 固定为 0。
        long turnSeq = persisted
                ? sessionStore.nextTurnSequence(sessionState.sessionId())
                : 0;
        sessionState.beginTurn(turnSeq);

        // 2. 将完整 Turn 关联到标准 GenAI Agent 操作和稳定 Session。
        GenAiSpanAttributes.recordAgentInvocation(
                sessionState.sessionId()
        );

        // 3. UserPromptSubmit Hook 可以阻止当前输入，或为本轮提供额外上下文。
        HookEffect promptEffect =
                hookRegistry
                        .triggerUserPromptSubmit(
                                query
                        );

        if (promptEffect.decision()
                == HookEffect.Decision.BLOCK) {
            System.out.println(
                    "消息被 Hook 阻止："
                            + promptEffect.reason()
            );
            return "";
        }

        // 4. 保持现有 SESSION Prompt 刷新和长期记忆召回时机。
        systemPromptManager.refreshFrom(
                RefreshScope.SESSION,
                sessionState
        );

        String recalledMemories =
                memoryRuntime.recall(
                        query
                );

        // 5. 首条通过 Hook 的用户消息在请求 Provider 前创建 Session。
        // 用户输入一旦被接受就是已发生事实，Provider 失败不能撤销它。
        MessageParam userMessage =
                MessageParam.builder()
                        .role(
                                MessageParam.Role.USER
                        )
                        .content(query)
                        .build();
        if (persisted) {
            sessionStore.appendCommitted(sessionState.sessionId(), turnSeq, List.of(userMessage));
        } else {
            sessionStore.createSessionWithFirstMessage(
                    sessionState.sessionId(),
                    sessionState.workspace().toString(),
                    turnSeq,
                    userMessage
            );
            persisted = true;
        }
        conversationState.appendCommitted(userMessage);

        // 6. Hook 上下文仍作为独立的隐藏用户提醒进入会话历史。
        if (!promptEffect.additionalContexts()
                .isEmpty()) {
            MessageParam hookMessage = MessageParam.builder()
                            .role(
                                    MessageParam.Role.USER
                            )
                            .content(
                                    "<system-reminder>\n"
                                            + String.join(
                                            "\n\n",
                                            promptEffect
                                                    .additionalContexts()
                                    )
                                            + "\n</system-reminder>"
                            )
                            .build();
            sessionStore.appendCommitted(sessionState.sessionId(), turnSeq, List.of(hookMessage));
            conversationState.appendCommitted(hookMessage);
        }

        // 7. 核心 Model/Tool 循环保持不变，AgentSession 只处理 Turn 外层行为。
        String output;
        try {
            output = recordedAssistant == null
                    ? agentLoop.run(
                    conversationState,
                    recalledMemories
            )
                    : agentLoop.runRecorded(
                    conversationState,
                    recalledMemories,
                    recordedAssistant
            );
        } catch (AnthropicServiceException exception) {
            // 8. Provider 失败只终止本次执行，已经 durable 的 Session 事实保持不变。
            printProviderError(
                    exception
            );
            throw exception;
        }

        try {
            // 9. AgentLoop 正常返回后，读取本轮完整消息并仅将提取工作写入后台队列。
            memoryRuntime.enqueue(
                    sessionStore.listTurnMessages(
                            sessionState.sessionId(),
                            sessionState.workspace().toString(),
                            turnSeq
                    ),
                    conversationState.modelContext()
            );
        } catch (Exception exception) {
            // 10. 入队失败不影响已完成主回答；后台失败会保留其已持久化工作自行重试。
            /*
             * 记忆提取和整理是回答完成后的辅助写路径。
             * 它们失败时保留已提交的主回答，避免把成功 Turn 反向判为失败。
             */
            LOGGER.warn(
                    "本轮回答已完成，但记忆后处理失败；"
                            + "保留主回答。session_id="
                            + sessionState.sessionId(),
                    exception
            );
        }

        return output;
    }

    /**
     * 返回当前父会话是否启用记忆。
     */
    public boolean memoryEnabled() {
        return sessionState.memoryEnabled();
    }

    /**
     * 等待此前后台记忆工作并再执行一次正常处理批次。
     *
     * @return 批次结束后仍待处理的 extraction task 数量
     */
    public int waitForMemoryIdle() {
        return memoryRuntime.runPendingAndWait();
    }

    /**
     * 返回当前 Session 的稳定 ID。
     */
    public String sessionId() {
        return sessionState.sessionId();
    }

    /**
     * 创建不继承当前活动上下文的新 Session，同时保留工作区长期记忆。
     *
     * @return 新 Session ID
     */
    public String startNewSession() {
        // 1. 清空完整历史、活动模型上下文和旧 Checkpoint 位置。
        conversationState.restore(
                List.of(),
                List.of(),
                -1
        );

        // 2. 切换 Session 身份，让下一条用户消息创建新的持久化记录。
        String newSessionId =
                sessionState.startNewSession();
        persisted = false;

        // 新 Session 必须刷新动态提示词，但继续读取同一个 workspace Memory。
        systemPromptManager.refreshFrom(
                RefreshScope.SESSION,
                sessionState
        );
        return newSessionId;
    }

    /**
     * 修改当前父会话的记忆开关，并立即刷新 SESSION Prompt。
     */
    public void setMemoryEnabled(
            boolean enabled
    ) {
        memoryRuntime.setEnabled(enabled);
        systemPromptManager.refreshFrom(
                RefreshScope.SESSION,
                sessionState
        );
    }

    /**
     * 返回已持久化 Session 的展示摘要。
     *
     * @return 按最后更新时间倒序的摘要
     */
    public List<SessionStore.SessionSummary> listSessions() {
        return sessionStore.listSessions();
    }

    /**
     * 打开指定 Session，并只封口上次中断的稳定内容。
     *
     * @param sessionId 要恢复的 Session ID
     */
    public void resume(
            String sessionId
    ) {
        SessionStore.LoadedSession loaded = sessionStore.loadSession(
                sessionId,
                sessionState.workspace().toString()
        );

        // 1. Checkpoint 已经压缩 throughSeq 之前的历史，后续消息按原序追加即可。
        List<MessageParam> modelContext = new ArrayList<>();
        long throughSeq = -1;
        if (loaded.checkpoint() == null) {
            modelContext.addAll(loaded.messages());
        } else {
            throughSeq = loaded.checkpoint().throughSeq();
            modelContext.addAll(loaded.checkpoint().context());
            for (int index = (int) throughSeq + 1;
                 index < loaded.messages().size();
                 index++) {
                modelContext.add(loaded.messages().get(index));
            }
        }

        // 2. 全部读取成功后才替换当前内存 Session，失败不会破坏当前输入状态。
        conversationState.restore(loaded.messages(), modelContext, throughSeq);
        sessionState.restoreSessionId(sessionId);
        persisted = true;

        if (loaded.inflight() == null) {
            return;
        }

        // 3. 不重跑工具；每个完整 tool_use 都从已持久化状态构造可配对结果。
        List<ContentBlockParam> recoveryContent = new ArrayList<>();
        for (ContentBlockParam block : loaded.inflight().assistantContent()) {
            if (!block.isToolUse()) {
                continue;
            }
            var toolUse = block.asToolUse();
            SessionStore.PersistedToolExecution execution = loaded.inflight()
                    .toolExecutions().get(toolUse.id());
            if (execution == null) {
                throw new IllegalStateException("in-flight tool_use 缺少执行状态：" + toolUse.id());
            }
            ToolExecutionResult result = switch (execution.state()) {
                case NOT_STARTED -> ToolExecutionResult.failure("工具在上次中断前尚未执行");
                case RUNNING -> ToolExecutionResult.failure("工具执行过程中进程中断，最终外部状态未知，重新执行前需要先检查现实状态");
                case COMPLETED -> execution.result();
            };
            recoveryContent.add(toolResultBlock(toolUse.id(), result));
        }
        recoveryContent.add(textBlock(STREAM_INTERRUPTION_PROMPT));

        // 4. assistant 内容与恢复 user 消息一次性封口，再删除 in-flight 事实。
        List<MessageParam> repaired = new ArrayList<>();
        if (!loaded.inflight().assistantContent().isEmpty()) {
            repaired.add(toAssistantMessage(loaded.inflight().assistantContent()));
        }
        repaired.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(recoveryContent)
                .build());
        sessionStore.commitCompletedTurn(sessionId, loaded.latestTurnSeq(), repaired);
        conversationState.appendCommitted(repaired);
    }

    private static final String STREAM_INTERRUPTION_PROMPT =
            "The previous assistant stream ended unexpectedly. Only fully completed content blocks were preserved; "
                    + "the unfinished block was discarded. Continue without repeating completed work.";

    /** 把完整 assistant blocks 封装为协议消息。 */
    private static MessageParam toAssistantMessage(List<ContentBlockParam> content) {
        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(content)
                .build();
    }

    /** 把内部工具结果转换为 Anthropic tool_result。 */
    private static ContentBlockParam toolResultBlock(String toolUseId, ToolExecutionResult result) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(toolUseId)
                .content(result.content())
                .isError(result.error())
                .build());
    }

    /** 构造恢复说明使用的文本内容块。 */
    private static ContentBlockParam textBlock(String text) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(text).build());
    }

    /**
     * 输出 SDK 和 Provider 原始响应提供的错误诊断。
     */
    private static void printProviderError(
            AnthropicServiceException exception
    ) {
        System.out.println(
                "\n[模型请求失败]"
        );
        System.out.println(
                "HTTP: "
                        + exception.statusCode()
        );
        System.out.println(
                "type: "
                        + providerErrorType(
                        exception
                )
        );
        System.out.println(
                "message: "
                        + providerErrorMessage(
                        exception
                )
        );
        System.out.println(
                "requestId: "
                        + providerRequestId(
                        exception
                )
        );
        System.out.println(
                "body: "
                        + providerErrorBody(
                        exception
                )
        );
    }

    private static String providerErrorType(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorNode(
                        exception.body()
                );
        String type =
                readNodeString(
                        body,
                        "code"
                );
        if (!type.isBlank()) {
            return type;
        }

        type =
                readNodeString(
                        body == null
                                ? null
                                : body.get(
                                "error"
                        ),
                        "type"
                );
        if (!type.isBlank()) {
            return type;
        }

        type =
                readNodeString(
                        body,
                        "type"
                );
        if (!type.isBlank()) {
            return type;
        }

        return exception.errorType()
                .map(
                        errorType -> errorType.asString()
                )
                .orElse(
                        "未返回"
                );
    }

    private static String providerErrorMessage(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorNode(
                        exception.body()
                );
        String message =
                readNodeString(
                        body,
                        "message"
                );
        if (!message.isBlank()) {
            return message;
        }

        message =
                readNodeString(
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

    private static String providerRequestId(
            AnthropicServiceException exception
    ) {
        JsonNode body =
                providerErrorNode(
                        exception.body()
                );
        String requestId =
                readNodeString(
                        body,
                        "request_id"
                );
        if (requestId.isBlank()) {
            requestId =
                    readNodeString(
                            body,
                            "requestId"
                    );
        }
        if (!requestId.isBlank()) {
            return requestId;
        }

        if (exception.headers() != null) {
            for (String headerName
                    : exception.headers().names()) {
                if (!"request-id".equalsIgnoreCase(
                        headerName
                ) && !"x-request-id".equalsIgnoreCase(
                        headerName
                )) {
                    continue;
                }

                List<String> values =
                        exception.headers()
                                .values(
                                        headerName
                                );
                if (!values.isEmpty()
                        && !values.get(0).isBlank()) {
                    return values.get(0);
                }
            }
        }

        return "未返回";
    }

    private static JsonNode providerErrorNode(
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
            return null;
        }
    }

    private static String readNodeString(
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
        return field == null
                || !field.isTextual()
                ? ""
                : field.textValue();
    }

    private static String providerErrorBody(
            AnthropicServiceException exception
    ) {
        JsonValue body =
                exception.body();
        if (body == null
                || body.isMissing()
                || body.isNull()) {
            return "未返回";
        }

        JsonNode node =
                providerErrorNode(
                        body
                );
        return node == null
                ? body.toString()
                : node.toString();
    }

}
