package dev.learn.agent.manual;

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.hook.HookEffect;
import dev.learn.agent.manual.hook.HookRegistry;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.memory.MemoryTurnResult;
import dev.learn.agent.manual.systemprompt.RefreshScope;
import dev.learn.agent.manual.systemprompt.RuntimeContext;
import dev.learn.agent.manual.systemprompt.SystemPromptManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 持有父 Agent 的会话历史，并编排一条用户消息的完整 Turn 生命周期。
 */
public final class ManualAgent {

    private final AgentLoop agentLoop;
    private final MemoryRuntime memoryRuntime;
    private final HookRegistry hookRegistry;
    private final SystemPromptManager systemPromptManager;
    private final RuntimeContext runtimeContext;
    private final List<MessageParam> history = new ArrayList<>();

    /**
     * 创建绑定一份父会话历史的 ManualAgent。
     */
    public ManualAgent(
            AgentLoop agentLoop,
            MemoryRuntime memoryRuntime,
            HookRegistry hookRegistry,
            SystemPromptManager systemPromptManager,
            RuntimeContext runtimeContext
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
        this.runtimeContext =
                Objects.requireNonNull(
                        runtimeContext,
                        "RuntimeContext 不能为空"
                );
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
                runtimeContext
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
        history.add(
                userMessage
        );

        // 4. Hook 上下文仍作为独立的隐藏用户提醒进入会话历史。
        if (!promptEffect.additionalContexts()
                .isEmpty()) {
            history.add(
                    MessageParam.builder()
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
                            .build()
            );
        }

        // 5. AgentLoop 可能压缩 history，因此先保存 Memory complete 使用的文本视图。
        List<MessageParam> memoryExtractionSnapshot =
                memoryRuntime.capture(
                        history
                );

        // 6. 核心 Model/Tool 循环保持不变，ManualAgent 只处理 Turn 外层行为。
        try {
            agentLoop.run(
                    history,
                    recalledMemories
            );
        } catch (AnthropicServiceException exception) {
            // 7. 保留现有诊断和回滚，再把失败交给 Runner 决定继续会话还是结束进程。
            printProviderError(
                    exception
            );
            rollbackFailedTurn(
                    history,
                    userMessage
            );
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
        return memoryRuntime.enabled();
    }

    /**
     * 修改当前父会话的记忆开关，并立即刷新 SESSION Prompt。
     */
    public void setMemoryEnabled(
            boolean enabled
    ) {
        memoryRuntime.setEnabled(
                enabled
        );
        systemPromptManager.refreshFrom(
                RefreshScope.SESSION,
                runtimeContext
        );
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
