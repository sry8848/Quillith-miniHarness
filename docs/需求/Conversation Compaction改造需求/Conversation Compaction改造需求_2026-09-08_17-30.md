# Conversation Compaction 改造需求

## 目的

将父 Agent Session 现有的中段裁剪、旧 ToolResult 压缩和模型摘要收敛为一套可递归、可精确回查的 Conversation Compaction。

核心不变式：

> SQLite `messages` 是会话原文的唯一事实源；压缩只改变 `modelContext`，任何摘要都不能删除、覆盖或替代 canonical messages。

## 已有基础与当前缺口

已有能力直接沿用：

- `ConversationState` 已区分完整 `messages` 与活动 `modelContext`。
- SQLite 已按 `session_id + seq + turn_seq` 保存 canonical messages。
- `context_checkpoints(through_seq, context_json)` 已能保存和恢复压缩后的活动上下文。
- 单轮 ToolResult 大结果预算与文件引用机制继续独立生效。

当前缺口是：请求前仍依次执行 `snipMiddle()`、`compactOldToolResults()` 和 `compactHistory()`；摘要会压缩全部活动消息并依赖 `.transcripts` 回查；没有 `/compact` 命令、递归增量摘要和按 seq 查询原文的工具；同一个 `AgentLoop` 还会让 SubAgent 进入现有压缩流程。

## 核心行为

### 1. 唯一压缩入口

自动触发与手动 `/compact` 必须调用同一个 `compact()`，得到完全相同的分段、摘要和 Checkpoint 语义。

压缩后的 `modelContext` 固定为：

```text
最前 3 个用户 Turn 的 canonical messages
+ 1 条中间摘要消息
+ 最近 5 个用户 Turn 的 canonical messages
```

“用户 Turn”以 SQLite `turn_seq` 为边界，不按 `MessageParam.role == USER` 的消息数计算。一个 Turn 内的用户输入、Hook reminder、assistant、`tool_use` 和 `tool_result` 必须整体保留或整体进入摘要，不能拆开协议消息。

`snipMiddle()` 和 `compactOldToolResults()` 不再参与请求前 Conversation Compression；不保留另一条可被误调用的旧压缩管线。

### 2. Canonical messages

- 压缩分段和摘要输入必须从当前 Session 的 SQLite `messages` 读取，不能把已压缩的 `modelContext` 当作原文。
- 压缩前后不删除、不更新 canonical messages。
- 摘要只存在于 `modelContext` 及其现有 Context Checkpoint 中，不新增 Summary 表、SummaryBlock、CompactionCheckpoint 或第二份消息历史。
- 旧 `.transcripts` 文件保持原样，不迁移也不删除；新压缩不创建、不读取、不引用 `.transcripts`。

这里的 canonical ToolResult 沿用现有含义：ToolResult 大结果预算在消息提交前已经生成的文件引用仍是 canonical message，本需求不改变该预算和原始大结果文件。

### 3. 摘要格式与覆盖范围

摘要消息仍使用普通 user 文本消息。宿主生成不可由 summarizer 决定的外层元数据，至少包含：

```text
[[agent:conversation-compacted]]
session_id: <session id>
coverage: <from_seq>-<to_seq>

[消息 18-24]
摘要段落……

[消息 25-31]
摘要段落……
```

`coverage` 使用 SQLite `seq` 的闭区间，表示该摘要总体替代的全部中间 canonical messages。摘要正文由模型生成若干段，每段以 `[消息 X-Y]` 标明其事实来源范围；宿主负责外层 `session_id` 和总体 coverage

### 4. 递归压缩

第一次压缩只把当前中间区域的 canonical messages 发送给 summarizer。

后续压缩时，随着最近 5 个 Turn 的边界后移，只向 summarizer 提供：

1. 当前摘要正文及其既有 coverage；
2. 新进入中间区域、且 `seq` 大于既有 `to_seq` 的 canonical messages。

新摘要替换旧摘要，`from_seq` 保持不变，`to_seq` 扩大到当前中间区域末尾。已经被旧摘要覆盖的原始消息不得再次发送。

### 5. 原文精确回查

父 Agent 新增只读工具 `get_session_messages`：

- 输入：`session_id`、`from_seq`、`to_seq`；
- 范围：`from_seq` 与 `to_seq` 均为闭区间；
- 输出：按 seq 升序返回每条记录的 `seq`、`turn_seq` 和完整 `MessageParam` JSON；
- 数据源：直接查询 `SessionStore.messages`，不读取 Checkpoint、摘要或 transcript。

工具只允许读取当前 workspace 下的 Session，并且只注册给父 Agent。否则同一 Agent Home 中另一个 workspace 的 Session ID 一旦出现在上下文或日志里，模型就可能跨工作区读取用户原始会话，这是真实的数据边界问题。

### 6. Checkpoint 与失败语义

压缩成功后只替换 `ConversationState.modelContext`，并继续用现有 `saveContextCheckpoint(session_id, through_seq, context_json)` 保存快照；`through_seq` 是压缩时最新 canonical message 的 seq。恢复仍按 `checkpoint context + seq > through_seq 的 canonical messages` 组装，不增加新恢复路径。

摘要请求失败、返回空文本、被拒绝或达到输出上限时，`modelContext` 和 Checkpoint 都保持原值，异常继续向上抛出，不使用旧裁剪、transcript 或空摘要兜底。

## 触发与范围边界

- 当前自动触发继续使用现有近似字符阈值；阈值判断只负责决定是否调用 `compact()`，不能改变压缩算法。
- 后续模型能力配置完成后，触发条件改为“窗口占用比例达到阈值”或“剩余 token 低于预留量”中的较早者；本次不实现模型能力配置或 token 精算。
- 本机制只用于已持久化的父 Agent Session。SubAgent 不执行自动或手动 Conversation Compaction，也不注册 `get_session_messages`。
- 不兼容旧数据库、旧 Checkpoint 摘要或旧摘要格式；开发环境直接重建 Session DB。
- 不改变 `applyToolResultBudget()` 及其文件持久化规则。
- 不承诺每次压缩后一定低于自动触发阈值。例如最近一个 Turn 含 100,000 字符 ToolResult，虽然低于现有单轮 200,000 字符预算，但它属于必须原样保留的最近 5 Turn；本次不能另加裁剪规则破坏明确的保留边界。

## 验收

1. 9 个及以上 Turn 的父 Session 压缩后，最前 3 Turn 和最近 5 Turn 与 SQLite 原文一致，中间只有一条带合法 metadata 的摘要。
2. 自动触发与 `/compact` 经过同一个 `compact()`；`snipMiddle()`、`compactOldToolResults()` 和 `.transcripts` 不再出现在新压缩调用链。
3. 第二次压缩的 summarizer 请求包含旧摘要和新增中间消息，不包含旧 coverage 已覆盖的 canonical messages；新摘要 coverage 正确扩大。
4. 压缩和多次递归压缩前后，SQLite `messages` 的条数、seq、turn_seq 和 message JSON 不变。
5. 重启恢复后得到“Checkpoint + 后续 canonical messages”的同一活动上下文，并可通过 `get_session_messages` 按摘要段落范围读回原文。
6. `get_session_messages` 不能读取其他 workspace 的 Session，且 SubAgent 看不到该工具。
7. 摘要失败时不提交新活动上下文或 Checkpoint；ToolResult 大结果预算测试保持原行为。

