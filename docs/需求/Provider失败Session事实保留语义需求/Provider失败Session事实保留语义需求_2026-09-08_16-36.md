# Provider 失败时的 Session 事实保留语义需求

## 目的

统一 Provider 最终失败、流式中断、进程恢复和工具执行恢复的 Session 语义：Session 记录已经发生的稳定事实，不把一次 `submit()` 当成整体成功或整体回滚的事务。

核心不变式：

> Provider 异常最终向上抛出时，保留本次执行中已稳定化的 canonical history；只丢弃未完整关闭的流式内容块；任何已稳定化的 `tool_use` 都必须通过同 ID 的 `tool_result` 闭合。

## 事实判定

1. 通过 UserPromptSubmit Hook 的 user 消息是已发生事实，不因后续 Provider 失败删除。
2. 收到 `content_block_stop` 的 assistant text、thinking 或 `tool_use` 是稳定内容块，应保留原内容和原顺序。Provider 错误类型不改变这一判定。
3. 只有已开始但尚未收到 `content_block_stop` 的当前块丢弃，已经流式打印的局部增量也不进入 canonical history。
4. 完整 `tool_use` 已经执行时，等待现有执行结果，按原 `tool_use_id` 追加 `tool_result`；工具失败也以 `is_error=true` 的结果闭合，不删除工具调用事实。
5. 闭合稳定事实后，Provider 异常仍保持原类型、原实例向上抛出；不将失败转换为成功。

| Provider 最终失败时的状态 | canonical history |
| --- | --- |
| 尚无 assistant 完整块 | 保留已接受的 user 及 Hook 消息 |
| 已有完整 assistant 块 | 保留完整块，丢弃当前未完成块 |
| 已有完整 `tool_use` | 保留 assistant，并追加对应 `tool_result` |
| 失败前已提交过工具协议对 | 原样保留，不回滚 |

## 控制边界

- 本需求只统一 Session 事实保留与工具协议闭合，不改变 Provider 错误识别、重试次数或恢复提示词。
- 不重跑工具，不尝试回滚工具已产生的外部副作用，不新增自动补偿。
- 不改变普通流式中断和进程恢复的既有规则，只使 Provider Error 遵守同一规则。
- 不新增持久化表、状态机或事务补偿层；复用现有 in-flight journal 和 `commitCompletedTurn()` 原子封口。
- Provider 失败后仍跳过 Memory complete；日志、终端诊断和 Runner 的失败映射保持不变。

## 验收

1. Provider 在首次请求前直接失败时，已接受 user 仍在内存和 SQLite Session 中。
2. Provider 在同一 SSE 中于完整 text、完整 `tool_use` 和工具执行之后最终失败时，text、`tool_use` 与 `tool_result` 全部保留，当前未完成块不保留。
3. 上述工具只执行一次，`tool_use_id` 配对一致，封口后不再遗留 in-flight 记录。
4. 同一 AgentSession 的下一次 `submit()` 可看到失败前保留的稳定事实，Provider 异常仍正常向上抛出。
