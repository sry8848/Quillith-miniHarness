# Agent 分层架构改造执行文档

## 0. 需求变更记录

2026-09-01 调整本轮范围：

- 本轮只做能够保持现有行为的职责迁移，不因为分层而重新定义错误、返回值或退出码。
- 暂不新增 `AgentResult`，`AgentLoop.run()` 继续返回 `String`。
- 暂不修改 `TaskTool` 对子 Agent 返回字符串的处理。
- Provider Error 的请求、恢复和 history 修改继续完整留在 `AgentLoop`；只允许把当前父 Turn 外层已有的“最终诊断 + rollback + 结束本轮”代码随 Turn 编排整体迁移，不重新设计它。
- 暂不增加 `ExecRunner` 和 `exec` CLI。非交互模式需要先单独确定成功/失败契约、退出码、Memory 和审批策略，再进入下一轮实现。
- 如果某段代码的迁移会迫使本轮定义新语义，允许它暂时留在原处，不为了让架构图完整而强行移动。

这次调整不否定最终目标架构，只把尚未讨论清楚的行为设计移到后续迭代。

---

## 1. 本轮目标

把当前巨大的 `ManualAgentApplication.main()` 拆出三个已经可以明确界定的对象：

```text
ManualAgentApplication
        │
        ▼
InteractiveRunner
        │
        ▼
ManualAgent
        │
        ▼
AgentLoop
```

旁边增加进程级资源所有者：

```text
AgentRuntime
    ├── AnthropicClient
    ├── MCP Client
    ├── ToolRegistry
    ├── BackgroundTaskScheduler
    ├── BashTool
    ├── ContextManager
    ├── parent/subagent AgentLoop
    ├── ManualAgent
    └── close()
```

本轮完成后仍然只有交互模式。所有用户可见行为、调用顺序、异常处理和资源关闭顺序必须与当前代码一致。

---

## 2. 本轮明确不做

- 不新增 `AgentResult`。
- 不修改 `AgentLoop.run()` 的参数、返回类型和返回内容。
- 不修改 `TaskTool`。
- 不增加 `exec` 命令或 `ExecRunner`。
- 不修改 `ApplicationOptions` 的现有 CLI 协议。
- 不设计新的退出码。
- 不把 Provider Error 恢复逻辑移出 `AgentLoop`。
- 不把 Provider 最终诊断移入所有 `AgentLoop`。
- 不修复自动压缩后按对象身份 rollback 可能失效的现有问题。
- 不改变 Memory capture/complete 的时机。
- 不改变 Hook 触发顺序。
- 不改变 System Prompt 的刷新层级。
- 不让子 Agent 经过 `ManualAgent`。
- 不新增 `AgentSession`、`ConversationState`、`SessionRepository`、resume 或 fork。
- 不新增 Factory、Runner 接口、MemoryPolicy、OutputSink 等预留抽象。
- 不重命名现有 `AgentLoop` 或 `systemprompt.RuntimeContext`。

---

## 3. Provider Error 的现有边界

这一节是行为保护说明，不是新的职责设计。

### 3.1 必须留在 AgentLoop 的逻辑

`AgentLoop` 继续负责：

- 创建模型请求；
- 捕获 HTTP `AnthropicServiceException`；
- 调用 `ModelRequestRecoveryManager`；
- 根据恢复结果重试或原样抛出异常；
- 把 SSE Provider Error 保存到 `StreamTurn`；
- 判断内容拒绝方向；
- 提交 Provider Error 前已经完成的 tool facts；
- 修改真实 history 中被拒绝的 Tool Result；
- 维护 pending Tool Result ID 和恢复次数；
- 恢复失败时原样抛出 Provider 异常。

不得因为抽取 `ManualAgent` 而修改上述任何代码。

### 3.2 随父 Turn 编排整体迁移的现有逻辑

当前 `ManualAgentApplication` 在父 `AgentLoop` 最终无法恢复时执行：

```java
catch (AnthropicServiceException exception) {
    printProviderError(exception);
    rollbackFailedTurn(history, userMessage);
    continue;
}
```

抽取 `ManualAgent` 时，只把这段现有行为整体搬入 `ManualAgent.submit()`：

```text
打印同样的 Provider 诊断
→ 使用同一个 userMessage 对象执行同样的 rollback
→ 直接结束本次 submit
→ 不执行 Memory complete
```

这不表示 `ManualAgent` 拥有 Provider Error 恢复。它只承接父用户 Turn 当前已经存在的最终失败收尾。

以下诊断 helper 可以随该 catch 块原样移动到 `ManualAgent`：

```text
printProviderError(...)
providerErrorType(...)
providerErrorMessage(...)
providerRequestId(...)
providerErrorNode(...)
readNodeString(...)
providerErrorBody(...)
rollbackFailedTurn(...)
```

不要合并或替换 `AgentLoop` 内部用于恢复判断的 Provider 解析方法。两套代码当前用途不同，本轮只迁移，不整理重复实现。

### 3.3 子 Agent 行为必须保持

`TaskTool` 继续直接调用子 `AgentLoop`：

```text
TaskTool → subagentLoop.run(...)
```

子 Agent 不创建 `ManualAgent`，因此：

- 不触发 UserPromptSubmit Hook；
- 不使用长期 Memory；
- 不持有跨 task history；
- Provider 异常仍由父 Agent 的工具执行管线转换成 Tool Result；
- 不新增父 Turn 的 Provider 诊断或 rollback。

---

## 4. 新增 ManualAgent

新增：

```text
src/main/java/dev/learn/agent/manual/ManualAgent.java
```

### 4.1 职责与字段

`ManualAgent` 只承接当前 Application 中已经存在的一条父用户 Turn 流程，并持有当前父 conversation history：

```java
private final AgentLoop agentLoop;
private final MemoryRuntime memoryRuntime;
private final HookRegistry hookRegistry;
private final SystemPromptManager systemPromptManager;
private final RuntimeContext runtimeContext;
private final List<MessageParam> history = new ArrayList<>();
```

一份 `ManualAgent` 绑定一份 history。本轮不支持替换 history、多会话或并发 `submit()`。

### 4.2 公共方法

```java
public void submit(String query) throws IOException

public boolean memoryEnabled()

public void setMemoryEnabled(boolean enabled)
```

`submit()` 返回 `void` 是为了保持当前真实行为：Application 当前忽略 `AgentLoop.run()` 返回的字符串，最终文本已经由 `StreamOutputPrinter` 实时输出。本轮不要为了未来 Exec 改造返回契约。

### 4.3 submit 的精确顺序

从当前 Application 原样迁移，并保持以下顺序：

```text
1. triggerUserPromptSubmit(query)
2. Hook BLOCK：打印当前提示并 return
3. refreshFrom(SESSION, runtimeContext)
4. memoryRuntime.recall(query)
5. 创建 userMessage
6. 把同一个 userMessage 对象加入 history
7. 追加 UserPromptSubmit Hook additionalContexts
8. memoryRuntime.capture(history)
9. agentLoop.run(history, recalledMemories)
10. 不可恢复 AnthropicServiceException：
    - 打印当前 Provider 诊断
    - rollbackFailedTurn(history, userMessage)
    - return
11. memoryRuntime.completeTurn(snapshot)
12. 按当前格式打印保存和整理数量
```

特别注意：

- Memory recall 仍然发生在添加 userMessage 前。
- Memory capture 仍然发生在 AgentLoop 前。
- recalled memories 仍然只通过 `turnContext` 进入模型请求，不写入 history。
- Provider Error 后仍然跳过 Memory complete。
- AgentLoop 正常返回错误字符串时，仍按当前逻辑执行 Memory complete；本轮不重新解释该字符串。
- `IOException` 继续向外抛出，不新增吞错或包装。

### 4.4 Memory 开关

`setMemoryEnabled(...)` 原样包含当前命令处理后的 Prompt 刷新：

```java
memoryRuntime.setEnabled(enabled);
systemPromptManager.refreshFrom(
        RefreshScope.SESSION,
        runtimeContext
);
```

`ManualAgent` 不解析 `/memory` 字符串，也不打印命令用法。

---

## 5. 新增 InteractiveRunner

新增：

```text
src/main/java/dev/learn/agent/manual/cli/InteractiveRunner.java
```

字段和公共方法：

```java
private final Scanner scanner;

public void run(ManualAgent manualAgent) throws IOException
```

Runner 借用 Application 创建的 Scanner，不负责关闭它。

从 Application 原样迁移：

- banner 和输入说明；
- `while (true)`；
- `s11 >> ` prompt；
- EOF、空输入、`q`、`exit`；
- `isMemoryCommand(...)`；
- `/memory on|off|status` 的参数解析和现有输出文案。

普通输入只执行：

```java
manualAgent.submit(query);
```

Runner 不触发 Hook、不操作 history、不调用 Memory recall/complete、不捕获 Provider Error。

空输入当前会结束程序，必须保持，不要改成忽略空行。

---

## 6. 新增 AgentRuntime

新增：

```text
src/main/java/dev/learn/agent/manual/AgentRuntime.java
```

声明和创建方法：

```java
public final class AgentRuntime implements AutoCloseable {

    public static AgentRuntime create(
            Path cwd,
            boolean memoryEnabled,
            Scanner scanner
    )
}
```

本轮只有交互模式，因此审批模式继续使用当前的 `ToolApprovalMode.ASK`。不要借这次迁移引入 mode 参数。

Scanner 由 Application 创建并关闭；Runtime 和 ToolApprovalGate 只借用。

### 6.1 从 Application 原样迁移的装配顺序

1. 可选 gitRoot 和 `RuntimeContext`；
2. API Key、GitHub Token 校验；
3. WorkspacePathResolver、SkillRegistry、TodoState、TaskStore；
4. 父子 BackgroundTaskScheduler 和 BashTool；
5. 共享文件工具；
6. 父 ToolRegistry 及当前工具；
7. Git/GitHub MCP 及当前注册规则；
8. 子 ToolRegistry 及当前白名单；
9. 共享 ASK ToolApprovalGate；
10. 父子 HookRegistry，保持注册顺序；
11. AnthropicClient、MemoryRuntime、ContextManager、RecoveryManager；
12. 父子 StreamOutputPrinter；
13. 父子 SystemPromptManager 及 APPLICATION 刷新；
14. subagentLoop；
15. `TaskTool(subagentLoop)` 注册；
16. parent AgentLoop；
17. `ManualAgent`。

`registerMcpTools(...)` 移入 `AgentRuntime`，保持原实现。

不要改变父子 Agent 共享对象、工具/Hook/Prompt 列表、MCP 规则、模型参数、轮次上限或输出可见性。

### 6.2 字段和访问

保存之后需要访问或关闭的对象：

```java
private final ManualAgent manualAgent;
private final BackgroundTaskScheduler parentBackgroundScheduler;
private final BackgroundTaskScheduler subagentBackgroundScheduler;
private final BashTool parentBashTool;
private final BashTool subagentBashTool;
private final GitMcpClient gitMcpClient; // nullable
private final GitHubMcpClient githubMcpClient;
private final AnthropicClient client;
```

只提供：

```java
public ManualAgent manualAgent()
```

不要暴露 ToolRegistry、HookRegistry、MemoryRuntime 或 AgentLoop getter。

### 6.3 close

把当前 finally 的顺序原样迁移：

```text
parentBackgroundScheduler.close()
subagentBackgroundScheduler.close()
parentBashTool.close()
subagentBashTool.close()
githubMcpClient.close()
gitMcpClient != null 时 close()
client.close()
```

Runtime 不关闭 Scanner、System.in、System.out 或 transcript。

启动失败清理只保留当前已经存在的 MCP 局部清理。本轮不顺便设计新的通用资源回滚框架；如果发现新的真实泄漏，单独记录。

---

## 7. 精简 ManualAgentApplication

保留：

- `main(String[] args)` 和现有 `ApplicationOptions.parse(args)`；
- `Path.of("").toRealPath()`；
- transcript 创建、`System.setOut()` 和 shutdown hook；
- Scanner 创建和关闭；
- Runtime 的 try-with-resources；
- 调用 InteractiveRunner。

必须先建立 transcript，再创建 Runtime。父 `StreamOutputPrinter` 需要取得已经指向 tee stream 的 `System.out`。

迁出：

- Agent 资源装配 → `AgentRuntime`；
- history 和单 Turn 编排 → `ManualAgent`；
- 交互循环和 memory 命令 → `InteractiveRunner`；
- MCP 注册 helper → `AgentRuntime`；
- Runtime close 列表 → `AgentRuntime.close()`；
- 父 Turn 最终 Provider 诊断和 rollback helpers → 随原 catch 块迁入 `ManualAgent`。

保持现有 `throws IOException` 和异常语义，不新增 `System.exit()`。最终调用关系：

```java
Scanner scanner = new Scanner(System.in);

try (AgentRuntime runtime =
             AgentRuntime.create(
                     cwd,
                     options.memoryEnabled(),
                     scanner
             )) {
    new InteractiveRunner(scanner)
            .run(runtime.manualAgent());
} finally {
    scanner.close();
}
```

transcript 局部变量和 shutdown hook 继续使用当前实现，不自行简化。

---

## 8. 行为保护边界

### 8.1 Hook

| 事件 | 本轮触发位置 |
|---|---|
| `UserPromptSubmit` | 从 Application 原样迁入 `ManualAgent` |
| `BeforeModelCall` | `AgentLoop`，不改 |
| `BeforeToolUse` | `AgentLoop`，不改 |
| `AfterToolUse` | `AgentLoop`，不改 |
| `Stop` | `AgentLoop`，不改 |

### 8.2 System Prompt

| Scope | 本轮触发位置 |
|---|---|
| `APPLICATION` | 从 Application 原样迁入 `AgentRuntime` |
| 父 Agent `SESSION` | 从 Application 原样迁入 `ManualAgent` |
| `TURN` | `AgentLoop`，不改 |
| `MODEL_CALL` | `AgentLoop`，不改 |

不要让所有 `AgentLoop.run()` 自动刷新 SESSION，否则会改变子 Agent 行为。

### 8.3 Memory 和 Context

- MemoryRuntime 仍只服务父 ManualAgent。
- `capture()` 仍在 AgentLoop 前。
- `completeTurn()` 仍只在 AgentLoop 正常返回后执行。
- 本轮不讨论快照是否应该包含本轮 assistant/tool 输出。
- ContextManager 继续只负责上下文窗口治理，不持有 history，不变成 Session。
- 父子 Agent 继续共享同一个 ContextManager。

---

## 9. 现有问题记录，但本轮不修

### 9.1 自动压缩后的 rollback

当前 rollback 使用 `userMessage` 对象身份定位当前 Turn。如果 AgentLoop 在 Provider Error 前已经把 history 自动压缩成新 summary，原 userMessage 可能不再位于 history，rollback 可能找不到它。

可靠方案可能需要 Turn 开始前 history snapshot，但它会改变回滚语义，必须单独设计和测试，本轮只记录。

### 9.2 AgentLoop 字符串返回值

当前最大模型轮次耗尽、输出续写耗尽和正常最终回答都通过 `String` 返回。`TaskTool` 当前也把所有返回字符串当作成功工具结果。

是否需要结构化结果属于后续错误模型设计，本轮不改。

### 9.3 Exec 错误契约

增加 Exec 前需要单独确定：

- Hook BLOCK 是否是进程失败；
- Provider Error 如何转换成退出码；
- 最大轮次和输出耗尽是否退出非零；
- Memory 默认是否启用；
- BYPASS 是否是唯一非交互审批策略；
- 最终文本由 streaming 输出还是 Runner 再输出。

这些问题未确定前不新增 ExecRunner。

---

## 10. 测试要求

### 10.1 既有测试

不得修改 AgentLoop、Recovery、Approval 和 Memory 测试来适配新行为，因为本轮没有新行为。迁移导致既有测试失败时，应修复迁移错误，不要修改断言。

### 10.2 新增 ManualAgentTest

新增：

```text
src/test/java/dev/learn/agent/manual/ManualAgentTest.java
```

沿用现有本地假 Anthropic HTTP 服务方式，不为测试增加生产接口或 mock 框架。至少覆盖：

1. 连续两次 submit 时，第二次模型请求包含第一轮 history。
2. UserPromptSubmit Hook BLOCK 时不调用模型且不写入 history。
3. 不可恢复 Provider Error 后，下一次 submit 不包含失败 Turn 的 userMessage。
4. Provider Error 后不执行 Memory complete。
5. Memory 关闭时不进行 recall/complete 模型流程。

暂不增加自动压缩后 rollback 测试；该问题属于后续修复。

### 10.3 手工 smoke test

```text
1. 启动 banner 和提示文案不变。
2. 空输入、q、exit、EOF 的行为不变。
3. 可以连续多轮对话，history 保留。
4. /memory status、off、on 行为和输出不变。
5. bash/MCP 审批仍读取同一个 Scanner。
6. 父 Provider Error 诊断格式不变，之后仍能继续输入。
7. 子 Agent 仍静默，Provider 异常仍转为父工具失败结果。
8. transcript 仍包含终端输出。
9. 退出时资源关闭行为不变。
```

验证命令：

```powershell
mvn -q test
git diff --check
git status --short
```

---

## 11. 文件改动清单

### 11.1 新增

```text
src/main/java/dev/learn/agent/manual/ManualAgent.java
src/main/java/dev/learn/agent/manual/AgentRuntime.java
src/main/java/dev/learn/agent/manual/cli/InteractiveRunner.java
src/test/java/dev/learn/agent/manual/ManualAgentTest.java
```

### 11.2 修改

```text
src/main/java/dev/learn/agent/manual/ManualAgentApplication.java
```

### 11.3 本轮明确不修改

```text
src/main/java/dev/learn/agent/manual/AgentLoop.java
src/main/java/dev/learn/agent/manual/cli/ApplicationOptions.java
src/main/java/dev/learn/agent/manual/tool/tools/TaskTool.java
src/main/java/dev/learn/agent/manual/context/ContextManager.java
src/main/java/dev/learn/agent/manual/memory/*
src/main/java/dev/learn/agent/manual/hook/*
src/main/java/dev/learn/agent/manual/systemprompt/*
src/main/java/dev/learn/agent/manual/tool/approval/*
src/main/java/dev/learn/agent/manual/mcp/*
```

除迁移导致的必要 import 调整外，不修改这些文件。

---

## 12. 推荐实施顺序

1. 新增 `ManualAgent`，逐行搬入父 Turn 流程，不改顺序和返回语义。
2. 新增 `ManualAgentTest`，先验证多轮 history、Hook BLOCK 和 Provider rollback。
3. 新增 `InteractiveRunner`，逐行搬入 Scanner while 和 memory 命令。
4. 运行完整测试，确认交互逻辑迁移没有改变行为。
5. 新增 `AgentRuntime`，按当前顺序搬入装配和 close 逻辑。
6. 精简 `ManualAgentApplication`，保持 transcript → Scanner → Runtime → Runner 的顺序。
7. 再次运行完整测试和手工 smoke test。
8. 检查 diff，确认没有 `AgentResult`、Exec、TaskTool 或 AgentLoop 修改。

每完成一个边界就运行 `mvn -q test`，不要一次搬完所有代码后再验证。

---

## 13. 验收标准

- [ ] 仅新增 `ManualAgent`、`InteractiveRunner`、`AgentRuntime` 和必要测试。
- [ ] `AgentLoop.java` 没有业务修改。
- [ ] `TaskTool.java` 没有修改。
- [ ] 没有新增 `AgentResult`。
- [ ] 没有新增 ExecRunner 或 exec CLI。
- [ ] `ManualAgent.submit()` 返回 `void`，保持当前流式输出方式。
- [ ] 父 Turn 的执行顺序与当前 Application 一致。
- [ ] Provider Error 请求和恢复逻辑仍全部位于 AgentLoop。
- [ ] 父 Provider Error 的诊断内容、rollback 和跳过 Memory complete 行为不变。
- [ ] 子 Agent 不经过 ManualAgent。
- [ ] 子 Agent 的异常和静默输出行为不变。
- [ ] Memory capture/complete 时机不变。
- [ ] Hook 触发顺序不变。
- [ ] System Prompt 刷新层级不变。
- [ ] 父子 Agent 的工具、Hook、Prompt、审批和 MCP 能力不变。
- [ ] Scanner 仍由 Application 创建和关闭，并与 ToolApprovalGate 共享。
- [ ] transcript 在 Runtime 前创建。
- [ ] Runtime close 顺序与当前 finally 一致。
- [ ] 所有既有测试无需修改断言即可通过。
- [ ] 新增 ManualAgent 行为测试通过。
- [ ] `git diff --check` 通过。

---

## 14. 后续迭代

下一轮先讨论并确定：

```text
Agent 执行结果契约
Provider/Hook/轮次耗尽的失败语义
Exec 退出码
Exec Memory 默认值
Exec 审批策略
最终输出边界
```

确定后再增加 `ExecRunner`、exec CLI，以及确有必要的结构化执行结果。

更后续真正需要 resume 时，再增加：

```text
SessionRepository
      │
      ▼
ConversationState
      │
      ▼
ManualAgent
```

Session 持久化位于 `ManualAgent` 外侧，不插入 `ManualAgent → AgentLoop` 中间。
