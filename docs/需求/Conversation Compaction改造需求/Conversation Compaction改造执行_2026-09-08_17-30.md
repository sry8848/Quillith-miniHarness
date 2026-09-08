# Conversation Compaction 改造执行

## 最小必要修改

本次只做以下生产改动：

1. `SessionStore` 增加带 `seq/turn_seq` 的 canonical message 查询。
2. 新增具体类 `ConversationCompactor`，集中负责自动阈值判断之外的完整压缩事务。
3. `ContextManager` 删除旧 Conversation Compression，只保留现有 ToolResult 大结果预算。
4. `AgentLoop` 在每次父 Agent 模型请求前判断阈值，命中后调用 `ConversationCompactor.compact()`。
5. `AgentSession` 只为 `/compact` 提供当前父 Session 的手动命令入口；`InteractiveRunner` 只解析命令。
6. 新增父 Agent 专用只读工具 `get_session_messages`。
7. `AgentRuntime` 只负责父、子 Agent 的能力装配，不增加功能布尔配置。

`ToolDefinitionFactory` 只在现有方法无法描述 seq 可为 0 时增加一个非负整数 schema helper。除此之外不扩展工具框架。

不新增摘要表、压缩仓储接口、策略层、事件系统、模型能力配置或兼容迁移；不修改 ToolResult 大结果预算。

## 现状分析

### 已满足，直接复用

- `ConversationState.messages()` 已保留完整 committed history，`replaceModelContext()` 只替换模型视图。
- SQLite `messages` 已保存连续 `seq` 和父用户请求级 `turn_seq`。
- `context_checkpoints` 与 `AgentSession.resume()` 已支持“Checkpoint + 后续 canonical messages”恢复。
- 父、子 Agent 已使用独立 ToolRegistry 和不同 TurnJournal；SubAgent 使用 `NoOpTurnJournal`，没有自己的持久化 Session。

### 必须修改

- `AgentLoop.runInternal()` 当前依次调用 `snipMiddle()`、`compactOldToolResults()` 和 `compactHistory()`，三种规则会分别改变 Conversation 边界。
- `compactHistory()` 把全部活动上下文压成一条消息；递归时会再次提交旧摘要已经覆盖的原文。
- `saveTranscript()` 仍是压缩前置条件和回查入口，与 SQLite 唯一事实源冲突。
- `SessionStore.loadSession()` 只返回 `List<MessageParam>`，没有暴露压缩分段和精确回查需要的 seq/turn_seq。
- 当前遗留摘要只包含 transcript 路径和摘要正文，没有 `session_id` 或 coverage，不能用于递归增量压缩。本需求明确不兼容该格式，开发环境重建 Session DB，不写兼容解析。
- `InteractiveRunner` 没有 `/compact`，模型也没有按 seq 查询 canonical messages 的工具。

自动判断仍要位于每次模型请求前，不能只放在 `AgentSession.submit()` 开始处。真实例子是：当前 Turn 的工具返回 190,000 字符，尚未超过现有 200,000 字符 ToolResult 批次预算，但下一轮模型请求已经超过 50,000 字符近似阈值；只在 submit 开头判断会漏掉本 Turn 内的增长。

## 修改后的职责

| 组件 | 单一职责 |
| --- | --- |
| `SessionStore` | 查询 canonical messages，保存现有 Context Checkpoint |
| `ConversationCompactor` | 读取原文、计算 Turn 区间、生成递归摘要、提交 modelContext 与 Checkpoint |
| `ContextManager` | 只处理 ToolResult 大结果预算 |
| `AgentLoop` | 在模型请求边界判断是否自动触发压缩 |
| `AgentSession` | 暴露当前父 Session 的手动压缩命令 |
| `InteractiveRunner` | 解析 `/compact` 并显示结果 |
| `AgentRuntime` | 装配父 Agent 的压缩能力，SubAgent 不装配 |

## 代码修改

### 1. `src/main/java/dev/learn/agent/manual/session/SessionStore.java`

新增只表示查询结果的普通 record，不增加数据库模型：

```java
public record SessionMessage(
        long seq,
        long turnSeq,
        MessageParam message
) {
}
```

新增两个直接查询方法：

```java
public synchronized List<SessionMessage> listSessionMessages(
        String sessionId,
        String workspace
)

public synchronized List<SessionMessage> getSessionMessages(
        String sessionId,
        String workspace,
        long fromSeq,
        long toSeq
)
```

实现要求：

1. 两个方法先复用 `readWorkspace()` 校验 Session 属于当前 workspace。
2. 全量查询执行：

   ```sql
   SELECT seq, turn_seq, message_json
   FROM messages
   WHERE session_id = ?
   ORDER BY seq
   ```

3. 范围查询增加 `seq >= ? AND seq <= ?`，范围是闭区间。`fromSeq < 0` 或 `toSeq < fromSeq` 直接抛 `IllegalArgumentException`。
4. 抽取一个私有查询方法供全量查询、范围查询和现有 `readMessages()` 复用。`loadSession()` 的公开结构保持不变，只把查询结果映射回 `MessageParam`。
5. 查询不更新 `sessions.updated_at`。

### 2. 新增 `src/main/java/dev/learn/agent/manual/context/ConversationCompactor.java`

该类是唯一 Conversation Compaction 实现。构造器只接收已有的：

- `AnthropicClient`；
- 当前模型名称；
- `SessionStore`；
- `SessionState`。

不依赖 `WorkspacePathResolver`，workspace 和当前 `session_id` 统一从 `SessionState` 读取。

公开方法只有：

```java
public boolean shouldAutoCompact(
        List<MessageParam> modelContext
)

public boolean compact(
        ConversationState conversationState
)
```

`shouldAutoCompact()` 直接迁移 `ContextManager` 现有 50,000 字符近似阈值逻辑，不调用模型、不读写数据库。它只决定自动路径是否调用 `compact()`，手动 `/compact` 不经过该阈值。

#### 2.1 查询并划分 canonical Turn

`compact()` 按以下顺序执行：

1. 调用：

   ```java
   sessionStore.listSessionMessages(
           sessionState.sessionId(),
           sessionState.workspace().toString()
   );
   ```

2. 按相邻且相同的 `turnSeq` 分组。不要统计 `role == USER`，因为 Hook reminder 和 `tool_result` 也使用 user 角色。
3. Turn 数小于 9 时返回 `false`，不调用 summarizer、不修改 Context、不写 Checkpoint。
4. 从 canonical rows 计算：最前 3 Turn、中间 Turn、最近 5 Turn。中间区首末 row 的 seq 是本次目标 `fromSeq/toSeq`。

#### 2.2 区分首次压缩和递归压缩

压缩后的固定结构是 `canonical head + 一条摘要 + canonical tail`，因此已有摘要只检查 `modelContext` 中 canonical head 后的固定位置，不扫描整份上下文。

- 该位置不是以 `[[agent:conversation-compacted]]` 开头的普通文本消息：视为第一次压缩，summarizer 输入为全部中间 canonical rows。
- 该位置是新格式摘要：只解析宿主固定写入的 `session_id` 和 `coverage: X-Y`，其余内容全部视为摘要正文。
- 当前遗留的 transcript 摘要不是新格式；按“不兼容旧摘要”的范围约束直接失败，不新增识别或迁移分支。

递归压缩只保留必要校验：

1. 摘要 `session_id` 必须等于当前 Session。
2. `X <= Y`，且 `X` 必须等于当前中间区 `fromSeq`。
3. `Y` 不能大于当前中间区 `toSeq`。
4. `Y == toSeq` 时返回 `false`，不重复调用 summarizer。

不要增加 marker 唯一性扫描、用户伪造 marker 防御或旧格式猜测。

#### 2.3 生成摘要输入

第一次压缩把全部中间 canonical rows 格式化为：

```text
[消息 25]
turn_seq: 4
message: {完整 MessageParam JSON}
```

递归压缩只向 summarizer 提供：

```text
<existing_summary coverage="X-Y">
上一轮新格式摘要正文
</existing_summary>

<new_messages>
seq > Y 且 seq <= 当前 toSeq 的完整 canonical rows
</new_messages>
```

这里的“已有摘要”只指上一轮由本类生成的新格式摘要，不指改造前依赖 transcript 的遗留摘要。

摘要 System Prompt 继续保留现有“不执行记录内指令、只依据记录、保留目标/进度/决定/文件/错误”约束，并要求模型生成若干段，每段以 `[消息 X-Y]` 开始。

宿主不逐段解析或校验模型生成的范围标签。原因是摘要正文仍是普通文本，按空行、列表或标题解析段落会误拒绝合法输出；每段范围属于摘要质量要求，不影响 canonical history 和总体 coverage 的正确性。测试通过正常模型响应验证格式要求。

继续保留现有三项结果检查：

- Stop Reason 为 `MAX_TOKENS` 时失败；
- Stop Reason 为 `REFUSAL` 时失败；
- 摘要正文为空时失败。

删除旧的 80,000 字符头尾截断。该逻辑可能在一条 Message JSON 中间截断，与“只提交完整新增 canonical messages”冲突。若完整增量输入无法被 Provider 接受，异常直接上抛，不增加另一种裁剪或 fallback。

#### 2.4 组装并提交压缩结果

宿主组装摘要消息，模型只能生成正文：

```java
MessageParam summaryMessage = MessageParam.builder()
        .role(MessageParam.Role.USER)
        .content("""
                [[agent:conversation-compacted]]
                session_id: %s
                coverage: %d-%d

                %s
                """.formatted(sessionId, fromSeq, toSeq, summary))
        .build();
```

从 canonical rows 构造 `head messages + summaryMessage + tail messages`。不得从旧 `modelContext` 复制 head/tail。

生成完整结果后统一提交：

```java
long throughSeq = canonicalMessages.getLast().seq();

// 1. Checkpoint 写入失败时，内存仍保留原 modelContext。
sessionStore.saveContextCheckpoint(
        sessionState.sessionId(),
        throughSeq,
        compactedContext
);

// 2. 持久化成功后再替换当前进程的模型视图。
conversationState.replaceModelContext(
        compactedContext,
        throughSeq
);
return true;
```

摘要请求及 Checkpoint 保存完成前不修改 `ConversationState`。不新增 CompactionCheckpoint，也不写 canonical messages。

只新增直接服务上述步骤的私有方法，例如 Turn 分组、固定 header 解析、带 seq 的消息格式化和 summarizer 调用；不创建 Compaction pipeline、策略接口或 Summary hierarchy。

### 3. `src/main/java/dev/learn/agent/manual/context/ContextManager.java`

原样保留 `applyToolResultBudget()`、`buildPersistedReference()`、`writePersistedToolResult()` 及其所需常量。

摘要能力移出后，删除不再使用的 `AnthropicClient`、`model` 字段及对应构造参数；构造器只保留 `WorkspacePathResolver`。不要让 ToolResult 预算类继续持有无用的模型客户端。

删除以下 Conversation Compression 实现及只为它们服务的常量、import 和 helper：

- `snipMiddle()`；
- `compactOldToolResults()`；
- `shouldAutoCompact()`；
- `compactHistory()`、旧 `compactMessages()` 和 `summarizeHistory()`；
- `saveTranscript()`；
- 消息数量裁剪、旧 ToolResult 微压缩、摘要和 transcript JSONL 的专用常量。

`.transcripts` 目录和已有文件不删除。不要借本次改造重命名 `ContextManager`；虽然改造后它只负责 ToolResult 上下文预算，但改名会扩大无关调用点。

### 4. `src/main/java/dev/learn/agent/manual/AgentLoop.java`

新增字段和构造参数：

```java
private final Optional<ConversationCompactor> conversationCompactor;
```

它表达当前 Loop 是否拥有持久化 Conversation Compaction 能力，不增加 `conversationCompactionEnabled` 等功能布尔值。

将请求前 L1/L2/L4 管线替换为：

```java
// 1. 只有装配了持久化压缩能力的父 Agent 才进入自动判断。
if (conversationCompactor.isPresent()) {
    ConversationCompactor compactor = conversationCompactor.get();

    // 2. 触发策略独立于 compact()，手动调用不受该阈值限制。
    if (compactor.shouldAutoCompact(messages)) {
        compactor.compact(conversationState);
    }
}
```

删除 `snipMiddle()`、`compactOldToolResults()` 和旧 `compactHistory()` 调用。压缩结果替换及 Checkpoint 保存已经由 `ConversationCompactor.compact()` 完成，`AgentLoop` 不再重复处理。

`applyToolResultBudget()` 的调用位置和内容不改。

### 5. `src/main/java/dev/learn/agent/manual/AgentSession.java`

父 `AgentSession` 构造器增加同一个 `ConversationCompactor` 依赖。新增的方法只作为手动命令门面：

```java
public boolean compact() {
    // 1. 尚未产生首条 canonical message 时没有可压缩 Session。
    if (!persisted) {
        return false;
    }

    // 2. 完整压缩和 Checkpoint 提交由唯一 Compactor 负责。
    return conversationCompactor.compact(
            conversationState
    );
}
```

`AgentSession` 不计算 Turn 边界、不调用摘要模型、不组装摘要、不替换 Context，也不直接保存本次压缩 Checkpoint。

该方法不创建新 Turn，不调用 Hook、Memory 或主 Agent 模型循环。异常原样上抛，不回退旧压缩。

删除当前仓库没有调用的五参数便捷构造器。它会在 `AgentSession` 内部另建一个 `SessionStore`，无法与外部创建的 `ConversationCompactor` 保证使用同一持久化边界；继续保留它只能通过增加第二套隐式装配来维持。保留并更新 Runtime 与测试实际使用的显式构造器，使 `SessionStore`、`ConversationState` 和 `ConversationCompactor` 都由同一个装配点传入。

### 6. `src/main/java/dev/learn/agent/manual/cli/InteractiveRunner.java`

在 `/session`、`/resume` 同层增加精确命令：

```java
if ("/compact".equalsIgnoreCase(command)) {
    boolean compacted = agentSession.compact();
    System.out.println(compacted
            ? "[Context：压缩完成]"
            : "[Context：当前没有可压缩的中间 Turn]");
    continue;
}
```

不增加 `/compact <focus>`、强制重摘要或其他子命令。

### 7. `src/main/java/dev/learn/agent/manual/tool/tools/GetSessionMessagesTool.java`

新增只读工具，依赖 `SessionStore` 和 `SessionState`：

- 工具名固定为 `get_session_messages`；
- required 参数为 `session_id`、`from_seq`、`to_seq`；
- `from_seq/to_seq` 是允许 0 的整数；必要时只向 `ToolDefinitionFactory` 增加复用两次的 `nonNegativeIntegerProperty()`；
- `execute()` 调用：

  ```java
  sessionStore.getSessionMessages(
          sessionId,
          sessionState.workspace().toString(),
          fromSeq,
          toSeq
  );
  ```

- 使用 `ObjectMappers.jsonMapper()` 将 `SessionMessage` 列表序列化为 JSON 字符串，不把结构化消息手工降级为纯文本；
- `isConcurrencySafe()` 返回 `true`，因为查询不修改 Session。

参数错误、Session 不存在、workspace 不匹配或数据库错误按现有 ToolRegistry 异常语义失败，不返回伪造的空成功结果。

### 8. `src/main/java/dev/learn/agent/manual/AgentRuntime.java`

1. 使用已有 `paths` 创建 `ContextManager`，不再传入 `client/model`；它只处理父子 Agent 共用的 ToolResult 预算。
2. 使用已有 `client/model/sessionStore/sessionState` 创建唯一 `ConversationCompactor`。
3. 父 `AgentLoop` 传 `Optional.of(conversationCompactor)`；SubAgent `AgentLoop` 传 `Optional.empty()`。
4. 父 `AgentSession` 接收同一个 `ConversationCompactor`。
5. `GetSessionMessagesTool` 只注册到父 `toolRegistry`，不注册到 `subagentToolRegistry`。

父子差异来自需求的明确范围：“当前只对持久化父 Agent Session 使用，SubAgent 暂不接入”。不借本需求重构既有父子 ToolRegistry、HookRegistry、System Prompt 或输出器装配。

## 测试修改

### `SessionStoreTest`

新增一个测试覆盖：多个 Turn、同 Turn 多消息时返回正确 `seq/turn_seq`；范围查询使用闭区间且顺序稳定；跨 workspace 读取明确失败。原 `loadSession()` 和 Checkpoint 测试保留。

### 新增 `ConversationCompactorTest`

使用可控本地模型响应覆盖：

1. 首次压缩 9 个 Turn：head 3 Turn 和 tail 5 Turn 来自 canonical 原文，中间摘要 metadata 覆盖第 4 Turn 的完整 seq 范围。
2. 追加第 10 个 Turn后递归压缩：摘要请求包含上一轮新格式摘要和新进入中间区的第 5 Turn，不包含旧 coverage 已覆盖的第 4 Turn原文；新 coverage 正确扩大。
3. 8 个 Turn和 coverage 未变化时不请求摘要模型、不修改 Context、不写新 Checkpoint。
4. 摘要 `MAX_TOKENS`、`REFUSAL`、空文本或 metadata 与当前 Session/范围冲突时失败，原 modelContext 和 Checkpoint 不变。
5. 压缩前后 SQLite canonical messages 的 seq、turn_seq 和 JSON 不变。

删除 `ContextManagerTest.shouldArchiveCompleteHistoryBeforeSnippingMiddle()`；不为已明确保持不变的 ToolResult 预算重写测试体系。

### `AgentSessionTest` / `InteractiveRunner` 相关测试

1. `/compact` 不作为用户消息提交，不触发主 Agent 请求；有中间区时调用唯一 Compactor，无中间区时返回 no-op。
2. 自动与手动路径产生相同 marker、metadata 和 head/summary/tail 结构。
3. 压缩后继续提交消息并重启 `/resume`，恢复结果等于 Checkpoint 加后续 canonical messages，SQLite 原文条数不变。

### 新增 `GetSessionMessagesToolTest`

验证闭区间 JSON 输出保留 `seq`、`turn_seq`、role 和结构化 `tool_use/tool_result`；验证非法范围和 workspace 不匹配失败。

### SubAgent 排除测试

只增加一个针对明确范围的测试：SubAgent 使用 `Optional.empty()` 装配后，即使活动消息超过自动阈值也不会调用 summarizer。`get_session_messages` 只出现在父 ToolRegistry 的约束由 `AgentRuntime` 注册位置和工具测试覆盖，不为测试新增运行时内部访问器。

## 明确不修改

- 不修改 `ConversationState` 双视图和 `context_checkpoints` 表结构。
- 不新增或更新设计文档、旧需求文档和 `.transcripts` 文件。
- 不实现旧 DB、旧摘要或旧 Checkpoint 迁移。
- 不修改 Provider 错误恢复、Memory、Hook、Tool 重试和审批语义。
- 不修改 `applyToolResultBudget()` 的 200,000 字符预算、预览与文件引用。
- 不处理“最近 5 Turn 本身超过模型窗口”的新降级策略。
- 不整体重构父子 Agent 装配。
- 不清理当前工作树中与本需求无关的已有修改或 `target/` 产物。

## 验证顺序

1. 运行 `mvn -Dtest=SessionStoreTest,ConversationCompactorTest,GetSessionMessagesToolTest test`。
2. 运行 `mvn -Dtest=AgentSessionTest,AgentLoopModelRequestRecoveryIntegrationTest test`，确认手动/自动压缩、恢复和 Provider 错误路径。
3. 运行 `mvn test`。
4. 检查 Git diff：生产改动只应落在上述职责文件、新工具及必要的 `ToolDefinitionFactory` helper；确认没有生成、删除或改写 `.transcripts`，没有修改 SQLite schema。
