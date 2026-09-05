# Quillith Harbor 多轮对话技术评审

> 历史版本：原方案包含超出第一阶段的生命周期与异常设计，当前版本见 [Quillith Harbor 最小多轮对话技术评审](./Quillith%20Harbor多轮对话技术评审_2026-09-04_22-16.md)。

## 1. 评审对象

对应需求：[Quillith Harbor 多轮对话需求](./Quillith%20Harbor多轮对话需求_2026-09-04_12-23.md)。

本评审只讨论 Quillith 如何接入 Harbor 原生 Multi-step 调度，不设计具体测评题目，也不编写验收用例。

## 2. 评审结论

Quillith 不需要新增一套多轮会话模型。现有 Interactive 会话已经具备多轮所需的核心语义：同一个 `AgentRuntime` 持有同一个 `ManualAgent`，每次用户输入继续调用同一个 `ManualAgent.submit(instruction)`。

需要增加的只是 Harbor 与现有 Interactive 会话之间的机器输入边界：

- 第一个 step 创建一次 Quillith Interactive 会话；
- 每个 step 向该会话提交一条完整 instruction；
- 当前 Turn 完成后，Quillith 通知 Harbor Adapter，使本次 `run` 或 `resume` 返回；
- Harbor 执行本轮 verifier；
- 后续 step 继续向同一个会话提交 instruction；
- Trial 结束时由 Harbor 销毁隔离环境，会话随之结束。

这里的“机器输入边界”不是新的 Agent 模式。它不持有第二份 history，不实现第二套 Tool Loop，也不改变 `ManualAgent` 的 Turn 语义；它只解决人工终端输入无法直接被 Harbor 分轮调用的问题。

## 3. 决策推导过程

### 3.1 先确认真正需要保持的状态

如果需求只要求多个 step 共享文件，那么 Harbor 自己保持同一个 Trial 环境就足够，不需要修改 Quillith。

但本需求还要求保留前序对话、Todo、Task、后台任务和 Session 级状态。代码现状表明这些状态并不都在文件系统中：

- `ManualAgent` 在内存中持有父 Agent 的 `history`；
- `AgentRuntime.create()` 创建 `TodoState`、`TaskStore`、父子 Agent 的 `BackgroundTaskScheduler`、Bash Tool、MCP Client 和模型 Client；
- `AgentRuntime.close()` 会关闭后台调度器、Bash Tool、MCP Client 和模型 Client。

由此得到第一个结论：后续 step 必须继续使用同一个未关闭的 `ManualAgent` 和 `AgentRuntime`。仅共享工作目录，或者每轮重新创建 Runtime 后恢复部分消息，都不满足需求。

### 3.2 再确认现有 Interactive 是否已经满足会话语义

现有 `InteractiveRunner` 在循环中持续读取用户输入，并把普通输入交给同一个 `manualAgent.submit(query)`。`ManualAgent.submit()` 会把本轮用户消息加入同一份 history，再运行既有 Model/Tool Loop。

因此，一次 Harbor Multi-step Trial 与现有 Interactive 会话在概念上完全一致：

```text
Harbor Multi-step Trial      Quillith
-------------------------------------------------
整个 Trial                   一个 Interactive 会话
一个 step                    一次用户请求
step instruction             submit() 的 query
后续 step                    同一 ManualAgent 的下一次 submit()
```

由此得到第二个结论：不应在 Agent 核心层增加“Harbor 多轮 history”或“Harbor Session”之类的新模型。现有 Interactive 会话就是要复用的模型。

### 3.3 为什么现有人工终端入口不能原样交给 Harbor

会话语义可以直接复用，但当前人工终端的输入输出形式与 Harbor 的调用边界不同：

1. `InteractiveRunner.run()` 会一直等待下一行输入，直到 `q`、`exit` 或 EOF 才返回。如果 Harbor 在第一个 step 前台启动它，本轮 Agent Phase 就不会返回，Harbor 无法开始 verifier。
2. Harbor 0.21.0 的 `BaseEnvironment.exec()` 是一次命令调用，只接收 command、cwd、env、timeout 和 user，不向 Agent Adapter 暴露一个可以跨多次 `run()` 持续写入的 stdin/PTY 句柄。
3. 当前 `InteractiveRunner` 使用 `Scanner.nextLine()`，一行代表一次用户请求；Harbor 的 `instruction.md` 通常是多行 Markdown，直接写入 stdin 会被错误拆成多个 Turn。
4. 人工 Interactive 使用 ASK 权限模式，并与 ASK 工具共用同一个 Scanner；Harbor 当前依赖隔离环境中的 BYPASS 权限模式，不能让审批输入与测评 instruction 混用。
5. 人工提示符和模型输出是给人看的文本，不是稳定的完成协议。Adapter 需要明确知道本轮正常完成还是失败。

由此得到第三个结论：复用现有 Interactive 不等于让 Harbor 模拟键盘操作人工终端。需要一个很小的机器输入边界，把“一条完整 instruction”交给现有会话，并在一次 `submit()` 返回后给出明确结果。

### 3.4 候选方案比较

| 方案 | 优点 | 问题 | 结论 |
| --- | --- | --- | --- |
| 每个 step 继续启动一次 `exec`，只序列化 history | Adapter 改动直观 | 无法保留后台调度器、运行中的 Bash、Todo 内存状态、MCP Client 等完整 Runtime；恢复模型会不断扩大 | 排除 |
| 前台运行现有人工 Interactive | Java 几乎不改 | 第一个 step 永不返回；多行 instruction 会被拆分；ASK 输入会混入测评 | 排除 |
| 使用 tmux/PTY 模拟人工输入并识别提示符 | 表面上可以不改 Java 协议 | 增加 tmux 依赖；需要解析人类提示符；多行输入、异常和退出码边界脆弱 | 排除 |
| 同一 JVM 保持现有 Runtime，增加显式的逐 Turn 机器通道 | 完整保留 Session 状态；每轮可明确返回；不改 Agent 核心 | Java 边界和 Adapter 需要少量协议代码 | 采用 |

选择最后一个方案的原因不是为了扩展架构，而是它是同时满足“完整 Session 延续”和“Harbor 每轮必须返回”两个约束的最小方案。

## 4. 概念逻辑模型

### 4.1 核心对象关系

```text
一个 Harbor Trial
└── 一个 Quillith 进程
    └── 一个 AgentRuntime
        └── 一个 ManualAgent
            ├── Turn 1：submit(step 1 instruction)
            ├── Turn 2：submit(step 2 instruction)
            └── Turn N：submit(step N instruction)
```

Harbor 的 verifier 位于相邻 Turn 之间运行，但不会关闭 Quillith 进程：

```text
Adapter 提交 Turn 1
    → ManualAgent.submit() 完成
    → Adapter.run() 返回
    → Harbor verifier 1
    → Adapter.resume() 提交 Turn 2
    → ManualAgent.submit() 完成
    → Adapter.resume() 返回
    → Harbor verifier 2
```

### 4.2 必须保持的不变量

启用 `resume_trajectory` 时：

- 一个 Trial 内只能有一个有效的 Quillith Interactive 会话；
- 后续 step 不得重新创建 `AgentRuntime` 或 `ManualAgent`；
- 一条 instruction 必须完整对应一次 `submit()`，不得按换行拆分；
- 同一时间只处理一个 step，不支持并发 `submit()`；
- verifier 运行期间会话仍然存在；
- 会话丢失后必须失败，不得静默新建会话伪装成 resume。

未启用 `resume_trajectory` 时：

- Harbor 每个 step 调用普通 `run()`；
- 每个 `run()` 必须创建新的 Quillith 会话；
- Harbor Trial 的工作目录仍按 Harbor 原生语义共享，但 Quillith 的对话和 Runtime 状态不得沿用。

## 5. 技术方案

### 5.1 Java 侧职责

在应用边界增加 Harbor 使用的机器输入入口。该入口只承担以下职责：

1. 使用 BYPASS 权限创建一次 `AgentState` 和 `AgentRuntime`；
2. 循环接收一条完整的 Harbor instruction；
3. 将 instruction 交给现有 `ManualAgent.submit()`；
4. 本轮成功时返回明确的成功状态；
5. 本轮失败时返回明确的失败状态并终止会话；
6. 收到正常关闭指令时退出循环，让现有 try-with-resources 关闭 `AgentRuntime`。

该入口不处理模型对话、不保存 history、不实现工具循环。`ManualAgent`、`AgentLoop`、Tool、Hook、Memory 和后台任务逻辑均保持原样。

现有 `ExecRunner.run(manualAgent, instruction)` 已负责把一条 instruction 交给 `ManualAgent`，并把不可恢复 Provider Error 映射为失败结果。实现时应优先判断能否直接复用它作为单个 Turn 的执行入口，避免复制异常映射逻辑。

### 5.2 Adapter 与 Java 之间的通信

Harbor 的环境接口不能保留跨调用 stdin，因此 Quillith 进程需要在 Trial 容器内继续运行，Adapter 的每次 `run()`/`resume()`通过容器内通信提交一轮请求。

建议使用两个位于固定 Session 目录下的 POSIX named pipe：一个传请求，一个传结果。选择 named pipe 的原因是 Harbor 当前目标环境为 Linux，且它不需要额外服务或第三方依赖，也不需要轮询文件。

每轮协议保持最小：

- instruction 以 Base64 单行传输，解码后原样交给 `submit()`；Base64 只解决换行和 Shell 边界，不改变模型看到的文本；
- Java 完成本轮后返回 `OK`；
- Java 捕获到本轮最终异常时返回 `ERROR`，随后让异常继续到进程边界并结束会话；
- 使用独立的 `CLOSE` 控制消息正常关闭旧会话，控制消息不能作为用户 instruction 进入 history。

不使用 JSON RPC、HTTP Server、Socket 服务或通用消息框架，因为当前协议只有顺序请求、成功/失败和关闭三种行为，引入这些能力没有对应需求。

### 5.3 Harbor Adapter 职责

`QuillithHarborAgent` 声明 `SUPPORTS_RESUME = True`，继续使用 Harbor 基类已有的 `resume()`。Harbor 基类会在调用 `run()` 前临时把 `_resume` 设为 `True`，因此 Adapter 不需要重写第二套 resume 调度。

Adapter 的 `run()` 根据 `_resume` 处理：

- `_resume == False`：关闭当前可能存在的旧会话，创建新的 Session 通道并启动一个新的 Quillith 进程，然后提交本轮 instruction；
- `_resume == True`：确认原 Quillith 进程和通道仍然存在，直接提交本轮 instruction；如果会话不存在，立即失败，不重启、不降级为新会话。

当前 Harbor 在第一个 step 调用 `run()`，只有第二个及后续 step 才调用 `resume()`。Adapter 在第一个 step 时不知道之后是否还会 resume，因此第一个 step 必须启动一个可以继续接收输入的 Interactive 会话。

这意味着单轮 Trial 内部也会通过同一机器输入边界提交唯一的一条 instruction，然后随 Trial 环境结束。单轮对外行为仍是一条 instruction、一个 Turn、一个结果；本评审不把需求文档中的“Quillith Exec”解释为必须继续调用 `ExecRunner` 后立即退出 JVM 的进程实现约束。

### 5.4 会话身份和工作目录

通信目录只存在于当前 Trial 容器中，不写入宿主长期目录，也不跨 Trial 复用。可以使用 Adapter 实例对应的 Harbor Session 身份构造目录，但不增加用户可配置路径。

Quillith 进程在第一个 step 的 Harbor 工作目录中启动。Harbor Multi-step 会在同一个环境工作目录上叠加各 step 的 workdir 内容，因此后续 Turn 继续使用同一个 Quillith workspace。

### 5.5 step 级用户限制

Harbor 允许每个 step 单独配置 `agent.user`，但同一个运行中的 JVM 不能在后续 Turn 改变自身 Unix 用户。

本方案建议把“启用 resume 的所有 step 必须使用同一 agent user”定义为当前能力边界。如果后续 step 的有效用户与首轮不同，Adapter 应在提交 instruction 前失败，不应继续用首轮用户悄悄执行。

该限制不影响已经选中的 Harbor 官方 Multi-step 示例，但它属于此前需求中没有明确讨论的 Harbor 配置边界，进入执行阶段前需要确认是否接受。若必须支持跨 step 切换用户，则需要重新评估进程模型，不能在本方案中假设实现。

## 6. 生命周期

### 6.1 启用 resume 的 Multi-step Trial

1. Adapter 安装并验证 JAR、Java、Git 和 Bash。
2. step 1 调用 `run()`，Adapter 创建通信通道并启动 Quillith。
3. Quillith 创建一次 `AgentRuntime`。
4. Adapter 提交 step 1 instruction，等待 `OK` 或 `ERROR`。
5. `OK` 后 `run()` 返回，Harbor 执行 verifier 并归档本轮结果。
6. step 2 调用 `resume()`，基类设置 `_resume = True` 后再次进入同一个 `run()`。
7. Adapter 把 instruction 发给原进程，重复第 4～5 步。
8. Trial 完成或提前终止后，Harbor 停止隔离环境，Quillith Session 随环境结束。

### 6.2 未启用 resume 的 Multi-step Trial

每个 step 都调用普通 `run()`。Adapter 在提交新 instruction 前正常关闭旧会话并创建新会话，因此对话 history、Todo 和 Runtime 状态不延续；Harbor 自己维护的共享文件系统仍然保留。

### 6.3 会话异常退出

本需求不支持进程异常退出后的恢复。后续 `resume()` 发现进程或通信通道不存在时直接失败。禁止自动启动新进程后继续执行，因为这会把“丢失历史”伪装成成功 resume。

## 7. 异常与超时处理

### 7.1 Turn 内异常

`ManualAgent.submit()` 已经负责 Provider Error 的诊断和失败 Turn 回滚。机器输入边界不重复记录同一异常，只负责把本轮结果通知 Adapter。

对于继续抛出的未知异常，机器输入边界属于 Java 进程最终边界：

- 先向结果通道返回 `ERROR`，避免 Adapter 只能等待到超时；
- 再让异常结束进程并保留完整堆栈；
- Adapter 将本次 Harbor Agent Phase 置为失败。

不得捕获异常后继续处理下一轮。

### 7.2 协议异常

无法解码 instruction、收到未知控制消息、结果状态非法、Session 通道缺失或进程已退出，均表示兼容层状态不可信，应立即失败。不得把原始编码文本提交给模型，也不得创建新会话兜底。

### 7.3 Harbor 超时

Adapter 等待本轮结果的时间受 Harbor 当前 step 的 Agent timeout 控制。超时后不重试本轮，也不恢复 Session。

如果 Adapter 已因超时离开，而 Quillith 稍后才完成，Trial 已经进入失败路径，不再接收该结果；最终由 Harbor 销毁环境。当前需求不增加超时后的协商取消协议。

## 8. 日志方案

Quillith 进程继续把 stdout 和 stderr 写入 Harbor 的 `/logs/agent`。每轮只增加不包含 instruction 正文的 Turn 边界标记，例如顺序编号、开始、成功或失败，用于区分累计日志中的各轮。

Harbor 0.21.0 在下一 step 需要 resume 时会保留 Agent 目录，并把当时内容复制到对应 step 的归档目录。因此长生命周期进程产生的日志可能是累计日志，而不是每轮从空文件开始。依靠明确的 Turn 边界即可定位，不需要为了日志拆分再创建一套轨迹格式。

日志中不得记录 API Key、Token 或完整环境变量。协议失败和未知系统异常只在最终边界记录一次完整堆栈，避免 Adapter 与 Java 重复 `log + throw`。

## 9. Harbor verifier 隔离风险

本机 Harbor 0.21.0 的 Multi-step 实现会在运行“当前 step 的 shared verifier”之前清理 `/tests` 和 `/logs/verifier`，但在进入“下一 step 的 Agent Phase”之前没有先清理上一轮 verifier 内容。

因此，使用 shared verifier 时，下一轮 Agent 可能看到上一轮上传的测试或 verifier 日志。这不是 Quillith Session 接入造成的问题，但会影响多轮测评可信度。

本需求不应擅自修改 Harbor 内部代码，也不应在 Quillith Adapter 中删除 Harbor 目录作为隐藏兜底。建议在后续具体用例需求中二选一并明确记录：

- 使用 separate verifier；
- 升级或修复 Harbor，并验证下一轮 Agent Phase 开始前已清理上一轮 verifier 内容。

在该决策完成前，可以开发和验证多轮传输能力，但不应把 shared verifier 下的正式分数视为已经排除测试泄漏的可信结果。

## 10. 明确不做的内容

- 不新增动态用户模拟器；
- 不在 Quillith 内解析 Harbor 的 `task.toml` 或 `[[steps]]`；
- 不把所有 step 合并成一个大 prompt；
- 不序列化和恢复部分 Runtime 来模拟 Session；
- 不支持跨 Trial、跨 Job 或进程崩溃后的 Session 恢复；
- 不支持 native trajectory、ATIF load 或 handoff；
- 不并发处理多个 step；
- 不新增重试、降级或失败后继续下一轮；
- 不创建或改造具体测评用例；
- 不修改 `ManualAgent`、`AgentLoop`、Tool 和 Memory 的业务语义。

## 11. 代码影响范围

预计只涉及以下边界，不在技术评审阶段规定逐行修改：

- Java CLI 入口：识别 Harbor 机器输入入口，并使用 BYPASS 创建一次 Runtime；
- Java 输入边界：顺序读取完整 instruction、调用现有 Turn 执行逻辑并返回状态；
- `harbor_adapter.py`：声明 resume 能力、管理 Trial 内进程和通信通道、按 `_resume` 决定新建或复用会话；
- 对应单元测试：覆盖完整多行 instruction、连续 Turn、失败状态和关闭行为；
- Adapter 测试：覆盖 `run → resume` 复用、普通 `run → run` 隔离以及丢失 Session 时快速失败。

不应为了该需求重构现有 Interactive、Exec 或 Agent 核心层的无关代码。

## 12. 性价比评估

### 收益

- 直接获得 Harbor 原生 Multi-step 的连续对话能力；
- 完整复用现有 Interactive Session 状态，不需要设计状态序列化格式；
- 不侵入 `ManualAgent` 和 `AgentLoop`，单轮 Turn 的行为来源保持唯一；
- 后续 Harbor step 数量增加时，不需要增加新的 Agent 逻辑。

### 成本

- Java 增加一个很小的机器输入边界；
- Adapter 增加 Session 进程和两个通信通道的生命周期管理；
- 日志从单进程单轮变为同一进程内带 Turn 边界的累计输出；
- 当前不支持 resume step 切换 Unix 用户。

### 判断

该方案的新增复杂度集中在 Harbor 与 CLI 的必要边界上，没有扩散到 Agent 核心。与序列化 Runtime 或模拟人工终端相比，它的实现量、状态数量和失败路径都更少，同时能够满足完整 Session 延续要求，性价比最高。

## 13. 执行前待确认事项

当前技术方案只剩一个此前没有确认过的能力边界：

> 启用 `resume_trajectory` 的同一 Trial 内，各 step 必须使用同一个有效 `agent.user`；不同用户时快速失败。

其余方案均来自已确认的需求：复用现有 Interactive 会话、每个 step 作为一次完整用户请求、保持同一个 `ManualAgent` 和 `AgentRuntime`、失败不恢复也不兜底。
