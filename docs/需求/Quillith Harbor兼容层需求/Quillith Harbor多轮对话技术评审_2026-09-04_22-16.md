# Quillith Harbor 最小多轮对话技术评审

## 1. 评审对象

对应需求：[Quillith Harbor 最小多轮对话需求](./Quillith%20Harbor多轮对话需求_2026-09-04_22-16.md)。

本版替代此前把正常链路和生产级进程治理一起设计的技术方案。第一阶段只证明同一个 Harbor Trial 可以连续驱动同一个 Quillith 会话。

## 2. 结论

核心实现只有两部分：

1. Java 增加一个 Harbor 机器输入入口，在一个进程中创建一次 `AgentRuntime`，然后对其中同一个 `ManualAgent` 连续调用 `submit()`；
2. Python Adapter 第一次 `run()` 启动该进程，每次 step 发送一条完整 instruction，并等待一个正常完成信号；后续 `resume()` 继续向同一个进程发送。

不实现进程恢复、主动关闭、PID 管理、READY 握手、复杂错误协议和通用 IPC 抽象。

## 3. 推导过程

### 3.1 多轮能力已经存在于 Agent Core

`InteractiveRunner` 已经把多个用户输入依次提交给同一个 `ManualAgent`。`ManualAgent` 自己持有 history，`AgentRuntime` 持有 Todo、后台调度器、Tool、MCP Client 和模型 Client。

因此本需求不是给 Agent Core 增加多轮，而是让 Harbor 的多个 step 进入同一个现有会话。

### 3.2 不能继续每轮启动 Exec

当前 Adapter 每次 `run()` 都启动一次：

```text
java -jar quillith.jar exec <instruction>
```

`exec` 完成后进程退出，`AgentRuntime.close()` 随之执行。下一轮即使共享文件系统，也不再拥有原来的 history 和 Runtime 状态，所以不能满足需求。

### 3.3 为什么不能直接驱动人工 Interactive

人工 Interactive 有三个与 Harbor 不匹配的地方：

- 它按行读取，一条多行 instruction 会被拆成多个 Turn；
- 它使用 ASK 权限并与审批共用 Scanner；
- 它只有会话结束才返回，Harbor 无法在每个 Turn 后执行 verifier。

所以必须增加机器输入入口，但入口只负责输入边界，不复制 Interactive 的 Agent 逻辑。

## 4. 最小正常链路

```text
Adapter 第一次 run()
→ 创建两个 FIFO
→ 后台启动 java -jar quillith.jar harbor
→ Adapter 写入 TURN
→ Java 读取并解码完整 instruction
→ 原 ManualAgent.submit(instruction)
→ submit 正常返回
→ Java 写入 OK
→ Adapter.run() 返回
→ Harbor verifier

Adapter 后续 resume()
→ 不启动新进程
→ 向同一个 FIFO 写入 TURN
→ 同一个 ManualAgent.submit(instruction)
→ Java 写入 OK
→ Adapter.resume() 返回
→ Harbor verifier
```

Trial 最后一个 verifier 完成后，Harbor 销毁容器，Java 进程随容器结束。

## 5. 除核心链路外保留的最小设计及必要性

本节逐项说明为什么这些设计不能再删除。

### 5.1 Harbor 机器输入入口

保留内容：增加 `harbor` 子命令。

必要性：

- 人工 Interactive 的 Scanner 会按行拆分 Harbor instruction；
- 人工 Interactive 使用 ASK，Harbor 需要 BYPASS；
- 人工 Interactive 无法在单个 Turn 完成后结束本次 Harbor Agent Phase。

没有独立机器入口，就必须修改人工 Interactive 的用户协议，反而更容易破坏现有行为。

该入口只创建一次 Runtime、循环读取 instruction、调用 `ManualAgent.submit()`、写完成信号，不增加其他职责。

### 5.2 两个 FIFO

保留内容：一个请求 FIFO、一个响应 FIFO。

必要性：

- Harbor 0.21.0 的 `BaseEnvironment.exec()` 是一次性命令接口，不提供跨多次 step 持续写入的 stdin 或 PTY 句柄；
- Java 必须在第一次 Agent Phase 返回后继续存在；
- 后续 `resume()` 需要从新的 `environment.exec()` 调用把 instruction 交给原 Java 进程；
- Adapter 还需要反向等待 Java 的本轮完成信号。

一个 FIFO 同时双向使用容易让双方读到自己写入的数据。两个单向 FIFO 是满足双向顺序通信的最小结构。

不增加 Channel 接口、Socket、HTTP Server、RPC、请求 ID或并发能力。

### 5.3 Base64 单行编码

保留内容：Python 将 UTF-8 instruction 编码为一行 Base64，Java 解码后提交原文。

必要性：

- Harbor instruction 通常是多行 Markdown；
- FIFO 协议按一行识别一条请求；
- 直接写原文会把换行误认为多条请求；
- Shell quoting 不能同时稳定承担任意多行内容和协议分帧。

Base64 使用 Python 和 Java 标准库，不增加依赖。它只负责传输边界，不进入模型 history，模型收到的仍是原始 instruction。

### 5.4 一个正常完成信号

保留内容：Java 在 `submit()` 正常返回后写入 `OK`。

必要性：

- Harbor 必须知道 Agent 本轮何时完成，才能开始 verifier；
- 不能用固定等待时间判断；
- 不能解析模型自然语言输出或人工提示符判断；
- FIFO 写入成功只代表 Java 收到请求，不代表 `submit()` 已完成。

第一版只有正常完成信号，不设计复杂响应对象、错误码或状态机。

### 5.5 后台进程输出重定向

保留内容：启动 Java 时把 stdin 连接 `/dev/null`，stdout/stderr 直接写 `/logs/agent`。

必要性：

- Java 需要在启动它的 `environment.exec()` 返回后继续运行；
- 如果后台进程继续持有 Docker exec 的 stdout/stderr，启动命令可能一直不返回；
- Harbor 仍需要保留 Agent 输出用于查看三轮执行情况。

这只是让后台进程脱离首次命令，不增加日志轮转、结构化日志或脱敏协议。

### 5.6 一个 started 布尔值

保留内容：Adapter 只记录当前 Trial 是否已启动 Quillith。

必要性：

- `resume()` 在首轮之前被错误调用时不能创建新会话伪装成功；
- 第二次普通非 resume `run()` 不能隐式复用原会话；
- 第一版没有 CLOSE，必须明确拒绝超出支持范围的第二次普通 `run()`。

它只有未启动和已启动两种值，不扩展 `BROKEN`、`EXITED` 等状态。

### 5.7 最小 Turn 标记

保留内容：每轮输出 Turn 序号和当前进程 PID，例如：

```text
[Harbor Turn 1 Start, pid=123]
[Harbor Turn 1 Success, pid=123]
```

必要性：

- 第一阶段必须验证三轮确实运行在同一个 Quillith 进程；
- Harbor 的任务结果只能证明文件结果，不能证明进程身份；
- `ProcessHandle.current().pid()` 是 JDK 原生能力，不需要 PID 文件。

不记录 instruction、Base64、Token 或环境变量，不扩展复杂日志格式。

## 6. 为什么第一版不强制复用 ExecRunner

`ExecRunner.run()` 当前只是：

1. 调用 `ManualAgent.submit()`；
2. 捕获 `AnthropicServiceException`；
3. 把它转换为单次进程退出码。

它没有需要复用的 Session 生命周期能力。Harbor 第一版的核心不变量是“每条 instruction 只直接调用一次同一个 `ManualAgent.submit()`”，因此 Harbor 机器入口直接调用 `submit()` 更明显，也更少一层。

这不是否定 `ExecRunner`。命令行 `exec` 继续使用它，保持现有行为不变。

## 7. 第一版明确接受的限制

### 7.1 Java 异常可能表现为 Harbor timeout

第一版只实现正常完成信号。如果 Java 在写 `OK` 前异常退出，Adapter 可能阻塞在 FIFO，直到 Harbor 外层 step timeout。

接受原因：

- Harbor 已有外层 timeout，最终不会把失败当成功；
- 本阶段只验证正常三轮链路；
- 增加 ERROR、进程监控、exited marker 和 watchdog 会立即引入失败状态协调问题；
- 等最小链路跑通后，再依据实际失败形态单独评审。

不得在第一版加入重试或重建 Session。

### 7.2 不支持第二次非 resume run

Adapter 第一次普通 `run()` 会启动长驻进程。若同一 Trial 再次收到普通 `run()`，第一版直接失败。

必要性：

- Harbor 第一个 step 调用 `run()` 时，Adapter 不知道后面是否会 resume，所以首轮必须启动可续用进程；
- 没有主动 CLOSE 时，无法在不遗留 Runtime 和后台任务的情况下建立真正独立的新 Session；
- 隐式复用会违反 Harbor 非 resume 语义。

因此第一版只承诺启用 `resume_trajectory` 的 Multi-step。非 resume Multi-step 独立 Session 作为后续增强评审。

### 7.3 不主动结束最后一个进程

Adapter 无法从单次 `run()`/`resume()` 调用判断当前是否最后一个 step。第一版不猜测、不新增 teardown 协议，直接使用 Harbor 容器生命周期作为进程生命周期边界。

## 8. 后续增强

以下内容不进入第一版。只有真实运行暴露对应问题，或需求范围扩大后，才单独评审：

- Java 异常的即时 ERROR 信号；
- PID、READY、exited marker；
- 进程存活检查和 zombie/PID reuse 防护；
- 主动 CLOSE 和非 resume Multi-step 独立 Session；
- step 切换 Unix user；
- timeout 协商、取消和 watchdog；
- 崩溃恢复、重试和 fallback；
- history、AgentState 或 Runtime 序列化；
- 日志结构化、轮转和生产级脱敏审计；
- 通用 Channel、Socket、HTTP 或 RPC 抽象。

这些项目不只是“多写几行保护”，它们都会引入新的状态、时序或失败语义，必须结合真实问题单独决定。

## 9. 验证分工

现有 `ManualAgentTest.keepsConversationHistoryAcrossSubmissions()` 用于证明同一个 `ManualAgent` 第二次提交会携带第一次 history。

真实存在的 Harbor 三轮用例用于证明：

- 一个进程跨三轮存在；
- 每轮完成后 Harbor 能进入 verifier；
- 后续 step 通过 resume 回到原进程。

官方 Cookbook 用例本身不能只靠最终文件证明 history，因此 history 证据来自现有 Agent 测试和实际请求记录，而不是另外编造一个测评题目。

具体用例导入、Java/Git 环境适配和 verifier 隔离配置继续作为独立需求，不混入本能力实现。

