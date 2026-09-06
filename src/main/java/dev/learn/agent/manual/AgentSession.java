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
import dev.learn.agent.manual.memory.MemoryTurnResult;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;
import dev.learn.agent.manual.session.ConversationState;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 持有父 Agent 的会话历史，并编排一条用户消息的完整 Turn 生命周期。
 */
public final class AgentSession {

    private final AgentLoop agentLoop;
    private final MemoryRuntime memoryRuntime;
    private final HookRegistry hookRegistry;
    private final SystemPromptManager systemPromptManager;
    private final SessionState sessionState;
    private final SessionStore sessionStore;
    private final ConversationState conversationState;
    private boolean persisted;

    /**
     * 创建绑定一份父会话历史的 AgentSession。
     */
    public AgentSession(
            AgentLoop agentLoop,
            MemoryRuntime memoryRuntime,
            HookRegistry hookRegistry,
            SystemPromptManager systemPromptManager,
            SessionState sessionState
    ) {
        this(agentLoop, memoryRuntime, hookRegistry, systemPromptManager, sessionState,
                createSessionStore(sessionState), new ConversationState());
    }

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
            ConversationState conversationState
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
    }

    private static SessionStore createSessionStore(SessionState sessionState) {
        try {
            return new SessionStore(sessionState.agentHome());
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException("创建 SessionStore 失败", exception);
        }
    }

    /**
     * 按现有父用户 Turn 顺序处理一条消息。
     *
     * @param query 用户输入
     * @throws IOException 记忆读取或写入失败
     * @throws AnthropicServiceException Provider Error 无法恢复时，在诊断和回滚后抛出
     */
    public void submit(
            String query
    ) throws IOException {
        Objects.requireNonNull(
                query,
                "query 不能为 null"
        );

        // 1. UserPromptSubmit Hook 可以阻止当前输入，或为本轮提供额外上下文。
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
            return;
        }

        // 2. 保持现有 SESSION Prompt 刷新和长期记忆召回时机。
        systemPromptManager.refreshFrom(
                RefreshScope.SESSION,
                sessionState
        );

        String recalledMemories =
                memoryRuntime.recall(
                        query
                );

        // 3. 记录本轮原始用户消息，Provider 最终失败时以它作为回滚边界。
        MessageParam userMessage =
                MessageParam.builder()
                        .role(
                                MessageParam.Role.USER
                        )
                        .content(query)
                        .build();
        if (persisted) {
            sessionStore.appendCommitted(sessionState.sessionId(), List.of(userMessage));
        } else {
            sessionStore.createSessionWithFirstMessage(
                    sessionState.sessionId(),
                    sessionState.workspace().toString(),
                    userMessage
            );
            persisted = true;
        }
        conversationState.appendCommitted(userMessage);

        // 4. Hook 上下文仍作为独立的隐藏用户提醒进入会话历史。
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
            sessionStore.appendCommitted(sessionState.sessionId(), List.of(hookMessage));
            conversationState.appendCommitted(hookMessage);
        }

        // 5. AgentLoop 可能压缩 history，因此先保存 Memory complete 使用的文本视图。
        List<MessageParam> memoryExtractionSnapshot =
                memoryRuntime.capture(
                        conversationState.messages()
                );

        // 6. 核心 Model/Tool 循环保持不变，AgentSession 只处理 Turn 外层行为。
        try {
            agentLoop.run(
                    conversationState,
                    recalledMemories
            );
        } catch (AnthropicServiceException exception) {
            // 7. 保留现有诊断和回滚，再把失败交给 Runner 决定继续会话还是结束进程。
            printProviderError(
                    exception
            );
            rollbackFailedTurn(
                    conversationState.messages(),
                    userMessage
            );
            rollbackFailedTurn(conversationState.modelContext(), userMessage);
            sessionStore.restoreAfterFailedTurn(
                    sessionState.sessionId(),
                    conversationState.messages(),
                    conversationState
            );
            persisted = !conversationState.messages().isEmpty();
            throw exception;
        }

        // 8. 只有 AgentLoop 正常返回后才提取并持久化本轮记忆。
        MemoryTurnResult memoryTurnResult =
                memoryRuntime.completeTurn(
                        memoryExtractionSnapshot
                );

        if (memoryTurnResult.savedCount() > 0) {
            System.out.println(
                    "[Memory：已保存 "
                            + memoryTurnResult.savedCount()
                            + " 条记忆]"
            );

            if (memoryTurnResult.consolidatedCount() > 0) {
                System.out.println(
                        "[Memory：整理后保留 "
                                + memoryTurnResult.consolidatedCount()
                                + " 条记忆]"
                );
            }
        }
    }

    /**
     * 返回当前父会话是否启用记忆。
     */
    public boolean memoryEnabled() {
        return sessionState.memoryEnabled();
    }

    /**
     * 返回当前 Session 的稳定 ID。
     */
    public String sessionId() {
        return sessionState.sessionId();
    }

    /**
     * 修改当前父会话的记忆开关，并立即刷新 SESSION Prompt。
     */
    public void setMemoryEnabled(
            boolean enabled
    ) {
        sessionState.setMemoryEnabled(
                enabled
        );
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
        sessionStore.commitCompletedTurn(sessionId, repaired);
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

    private static void rollbackFailedTurn(
            List<MessageParam> history,
            MessageParam userMessage
    ) {
        for (int index = 0;
             index < history.size();
             index++) {
            if (history.get(index)
                    != userMessage) {
                continue;
            }

            history.subList(
                    index,
                    history.size()
            ).clear();
            return;
        }
    }
}
