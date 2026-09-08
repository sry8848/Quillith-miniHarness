# Provider 失败时的 Session 事实保留语义执行

## 最小必要修改

生产代码只修改 `AgentLoop` 的 SSE Provider Error 封口。当前 `AgentSession` 已经不再调用 `rollbackFailedTurn()` 或 `restoreAfterFailedTurn()`，`SessionStore` 也已支持完整 block 和工具执行状态先 durable、消息对原子封口，不需要再改。

必要改动为：

1. 任何 SSE Provider Error 在恢复或抛出前都封口已完成事实，而不是只处理 `DataInspectionFailed + OUTPUT`。
2. Provider Error 封口保留所有完整 assistant block，不再过滤完整 text block。
3. 增加一个端到端测试，同时验证完整块保留、未完成块丢弃、工具只执行一次和协议闭合。

## 现状分析

已满足、不修改的路径：

- `AgentSession.submitInternal()` 在 Provider 失败时只输出诊断并重抛，不再回滚 user 或本 Turn 已提交消息。
- 工具批次正常封口后，下一次 HTTP Provider 请求失败不会删除已提交的 assistant / `tool_result` 对。
- `AnthropicIoException` 和 `AnthropicInvalidDataException` 已经走 `commitInterruptedTurn()`，只提交完整块并闭合工具协议。
- 进程恢复已经根据 `NOT_STARTED / RUNNING / COMPLETED` 状态生成对应 `tool_result`，不重跑工具。

尚未满足的路径：

- `streamModel()` 将 SSE `SseException` 交给 `recoverFromProviderError()`；该方法对非 `DataInspectionFailed`、输入方向或方向不明的错误直接返回 `false`，主循环随后抛异常，未将已 durable 的 in-flight assistant / 工具事实转为 canonical history。
- `commitProviderErrorToolFacts()` 只保留 thinking 和 `tool_use`，会丢弃已收到 `content_block_stop` 的 text，与本需求“只丢弃未完成块”相冲突。

真实可能发生的一致性问题：模型先输出完整说明，再调用“发送邮件”工具；邮件已发送，但 Provider 随后在同一 SSE 发出不可恢复的 error event。当前进程不退出而用户继续输入时，下一次模型请求看不到这次邮件操作，可能再次发送。这正是必须补齐 SSE 最终失败封口的原因，不是未来扩展。

## 代码修改

### 1. `src/main/java/dev/learn/agent/manual/AgentLoop.java`

将 `commitProviderErrorToolFacts()` 改名为 `commitProviderErrorFacts()`，职责调整为“封口 Provider Error 前已完成的全部 assistant 事实和工具结果”。

#### 1.1 调整 `recoverFromProviderError()`

保留现有错误识别、输入/输出方向判定、最大恢复次数和日志。只改变返回前的事实提交：

```java
private boolean recoverFromProviderError(
        ConversationState conversationState,
        ModelRequestRecoveryState recoveryState,
        StreamTurn providerErrorTurn,
        AnthropicServiceException exception,
        int previousRejectionCount
) {
    // 1. 不可恢复的 SSE Provider Error 也要在抛出前封口稳定事实。
    if (!isDataInspectionFailure(exception)) {
        commitProviderErrorFacts(conversationState, providerErrorTurn, null);
        return false;
    }

    ContentRejectionDirection direction = contentRejectionDirection(exception);
    if (direction != ContentRejectionDirection.OUTPUT) {
        commitProviderErrorFacts(conversationState, providerErrorTurn, null);
        return false;
    }

    // 2. 仅恢复决策影响是否追加恢复说明，不影响稳定事实是否保留。
    boolean shouldRetry =
            previousRejectionCount < MAX_SSE_CONTENT_REJECTION_RECOVERIES;
    commitProviderErrorFacts(
            conversationState,
            providerErrorTurn,
            shouldRetry ? outputContentRejectionMessage(exception) : null
    );

    recoveryState.recordPendingToolResults(
            providerErrorTurn.toolExecutions().stream()
                    .map(execution -> execution.toolUse().id())
                    .toList()
    );
    if (!shouldRetry) {
        return false;
    }

    LOGGER.warn(
            "模型输出被 Provider 拒绝，第 {}/{} 次恢复",
            previousRejectionCount + 1,
            MAX_SSE_CONTENT_REJECTION_RECOVERIES
    );
    return true;
}
```

不在 `AgentSession` catch 中调用封口。到达该层时已丢失 `StreamTurn`、完整 block 和工具 Future 的结构化信息，无法正确判断什么可以提交。

#### 1.2 实现 `commitProviderErrorFacts()`

删除只保留 thinking / `tool_use` 的 filter，直接使用 `providerErrorTurn.assistantContent()`。按以下顺序组装并且只调用一次 `commitCompletedTurn()`：

```java
private void commitProviderErrorFacts(
        ConversationState conversationState,
        StreamTurn providerErrorTurn,
        String recoveryPrompt
) {
    // 1. assistantContent 只包含收到 content_block_stop 的稳定块。
    List<MessageParam> completedMessages = new ArrayList<>();
    if (!providerErrorTurn.assistantContent().isEmpty()) {
        completedMessages.add(
                toAssistantMessage(providerErrorTurn.assistantContent())
        );
    }

    // 2. 工具结果必须位于同一 user 消息的普通文本之前。
    List<ContentBlockParam> userContent =
            new ArrayList<>(providerErrorTurn.toolResults());
    if (recoveryPrompt != null) {
        userContent.add(textBlock(recoveryPrompt));
    }
    if (!userContent.isEmpty()) {
        completedMessages.add(
                MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(userContent)
                        .build()
        );
    }

    // 3. 没有完整 block 且不需要恢复说明时，没有 in-flight 事实需要封口。
    if (!completedMessages.isEmpty()) {
        commitCompletedTurn(conversationState, completedMessages);
    }
}
```

不复用 `commitInterruptedTurn()` 的固定中断提示词：Provider 最终失败仍会抛出，不应在 canonical history 中伪造“本次已转入自动续写”。

### 2. `src/test/java/dev/learn/agent/manual/AgentSessionTest.java`

新增一个全链路测试，不为每个分支拆分重复用例：

1. 在 fixture 的 `ToolRegistry` 注册一个测试工具，`execute()` 对 `AtomicInteger` 加一并返回固定成功结果。
2. 首个 HTTP 响应返回 SSE：完整 text block → 完整 `tool_use` block → 另一个只有 start/delta 的未完成 text block → 通用 `server_error` error event。
3. 断言首次 `submit()` 抛出原 `AnthropicServiceException`，工具计数是 `1`。
4. 从 `SessionStore.loadSession()` 断言历史依次为 user、assistant（完整 text + `tool_use`）、user（对应 `tool_result`）；未完成 text 不存在，并且 `inflight()` 为 `null`。
5. 第二次 `submit()` 使用正常回复，断言其请求体包含上述稳定事实和新 user 输入，工具计数仍为 `1`。

现有 `retainsFirstUserMessageAndAllowsResumeAfterProviderError()`、`retainsCompletedToolFactsWhenNextProviderRoundFails()` 和 `providerErrorSkipsMemoryCompletion()` 保留，它们分别锁定请求前 HTTP 失败、已封口工具事实后的 HTTP 失败和 Memory 边界。

## 明确不修改

- 不修改 `AgentSession.java`、`SessionStore.java`、`ConversationState.java` 和 TurnJournal 接口。
- 不改变 `MAX_SSE_CONTENT_REJECTION_RECOVERIES`、`ContentRejectionDirection` 或 `ModelRequestRecoveryManager`。
- 不新增 Provider Error 降级、工具重试、副作用补偿或自动续写。
- 不修改既有需求/设计文档，不处理当前工作树的其他未提交改动。

## 验证顺序

1. 运行 `mvn -Dtest=AgentSessionTest test`，确认新增失败路径和既有 AgentSession 语义。
2. 运行 `mvn test`，确认 Provider 请求恢复、流式中断、Session 恢复和工具调度没有回归。
3. 最后检查 Git diff，生产修改应只落在 `AgentLoop.java` 的 Provider Error 封口，测试修改只落在相关测试类。
