# Conversation Compaction 设计

> 时间：2026-09-09 14:33  
> 适用范围：持久化父 Agent Session

## 1. 设计目的

Conversation Compaction 的职责是在模型上下文接近容量时，缩短 `modelContext`，同时保证 SQLite 中的 canonical messages 始终保留完整原文。

压缩后的上下文固定由三部分组成：最前 3 个用户 Turn、覆盖中间 Turn 的一条摘要、最近 5 个用户 Turn。Turn 内的 user、assistant、tool_use、tool_result 消息保持完整，避免破坏工具调用协议。

摘要不是新的持久化事实源；它只是 canonical messages 的派生视图。摘要缺少细节时，模型通过范围标记精确回查原文。

## 2. 核心设计

```text
SQLite canonical messages
          │
          ▼
ConversationCompactor ──► modelContext（头部 Turn + 摘要 + 尾部 Turn）
          │                                      │
          └──────────────────────────────► context_checkpoints
                                             （当前上下文快照）
```

- canonical messages 是唯一原文事实源，压缩不删除、不覆盖其中任何消息；旧 `.transcripts` 文件不参与新逻辑。
- 摘要消息外层由宿主写入 `session_id` 与 `coverage: from-to`。正文由模型生成，并以 `[消息 X-Y]` 标示各段所依据的原文范围。
- 再次压缩时，summarizer 只接收旧摘要和 coverage 之后新进入中间区的 canonical messages；新摘要替换旧摘要并扩大 coverage，不重复发送已覆盖原文。
- 摘要失败时不替换当前 `modelContext`；成功时先保存 checkpoint，再切换内存中的模型上下文。

## 3. 核心模块职责

| 模块 | 职责 |
| --- | --- |
| `SessionStore` | 持久化 canonical messages，并按 `session_id`、seq 范围读取原文。 |
| `ConversationCompactor` | 判断是否需要自动压缩；按 Turn 划分头部、中间和尾部；生成递归摘要；构造并替换模型上下文。 |
| `ConversationState` | 持有当前会话的 `modelContext` 与 checkpoint 写入所需状态；不持有 canonical history。 |
| `AgentLoop` | 在父 Agent 发起模型请求前调用自动压缩判断和 `compact()`。 |
| `AgentSession` / `InteractiveRunner` | 为持久化父 Session 提供手动 `/compact` 入口，复用同一个 `compact()`。 |
| `GetSessionMessagesTool` | 让模型按 `session_id + from_seq + to_seq` 精确读取 canonical messages。 |
| `ContextManager` | 仅负责 ToolResult 大结果预算，不再参与 Conversation Compaction。 |

## 4. 控制边界

- 当前仅持久化父 Agent Session 接入压缩与原文回查；SubAgent 不接入。
- 自动触发阈值可以独立演进为模型窗口占用或剩余 token 预留策略，不能改变 `compact()` 的输入、结果和递归规则。
- 不新增摘要表、摘要块或 CompactionCheckpoint；继续使用现有 `context_checkpoints` 保存当前模型上下文快照。
