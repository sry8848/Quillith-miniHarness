# Quillith Harbor 最小多轮对话需求

## 需求陈述

第一阶段只建设 Harbor Multi-step 的最小可运行多轮链路。

当 Harbor Trial 显式启用 `resume_trajectory` 时，一次 Trial 必须对应同一个 Quillith 进程、同一个 `AgentRuntime` 和同一个 `ManualAgent`。每个 Harbor step 的完整 instruction 作为该会话中的一条用户消息，按顺序调用一次 `ManualAgent.submit(instruction)`。

执行关系为：

```text
step 1
→ 启动一次 Quillith
→ 创建一次 AgentRuntime
→ 取得其中唯一的 ManualAgent
→ submit(instruction 1)
→ 返回本轮完成信号
→ Harbor verifier

step 2
→ 不重新启动 Quillith
→ 继续使用原 AgentRuntime 和 ManualAgent
→ submit(instruction 2)
→ 返回本轮完成信号
→ Harbor verifier
```

后续 step 同理。

本需求复用 Quillith 已有 Interactive 会话语义，不新增另一套 Agent、history、Tool Loop 或 Session 模型。不得把历史消息重新拼接成新 prompt，不做 history、`AgentState` 或 Runtime 序列化恢复。

每条 instruction 必须作为一条完整用户消息提交，包括原有换行和格式，不得按行拆成多个 Turn。当前 `submit()` 返回后，兼容层才可以通知 Harbor 本轮完成，使 Harbor 继续执行 verifier。

第一阶段只要求支持显式启用 `resume_trajectory` 的 Multi-step Trial。对于同一 Trial 中出现第二次非 resume `run()` 的情况，兼容层必须明确失败，不能隐式复用原会话。本阶段不实现“未启用 resume 时每个 step 自动创建独立 Session”。

现有命令行单轮 `exec` 和人工 Interactive 的入口与行为不得改变。Harbor 可以使用独立的机器输入入口，但该入口只能负责完整 instruction 的传输、调用现有 `ManualAgent.submit()` 和返回完成信号。

Trial 结束后，第一阶段直接依赖 Harbor 销毁隔离容器来结束 Quillith 进程，不主动实现 Session 关闭协议。

第一阶段不实现：

- 进程异常退出后的恢复；
- 自动重试或 fallback 到新 Session；
- Session 完整状态机；
- PID、READY、exited marker 或 zombie 防护；
- 主动 CLOSE 协议；
- 多层 timeout 或 watchdog；
- 通用 Channel、RPC 或消息框架；
- 复杂日志协议；
- ATIF、trajectory load 或 handoff；
- 动态用户模拟器；
- Harbor 框架源码修改。

通信方式不是需求目标。实现只应选择能够在 Harbor `environment.exec()` 与同一个 Java 进程之间传递完整多行 instruction，并返回本轮完成信号的最简单可靠方式。若采用 FIFO、Base64 或其他机制，必须分别说明它对最小正常链路不可替代的原因，不得围绕通信机制扩展额外能力。

第一阶段验证重点：

1. 使用真实存在的三轮用例，不自行编造测评题目；
2. 三轮使用同一个 Quillith 进程；
3. 三轮使用同一个 `AgentRuntime` 和 `ManualAgent`；
4. 每个 step 只调用一次 `ManualAgent.submit()`；
5. 第二轮模型请求能够包含第一轮对话 history；
6. 每轮 `submit()` 返回后 Harbor 能继续执行 verifier；
7. 单轮 Exec 和人工 Interactive 原行为不受影响。

真实用例的引入、环境适配和 verifier 配置仍作为独立需求记录。本能力实现完成后，必须通过该真实用例验证，才能确认端到端 Multi-step 链路已经跑通。

