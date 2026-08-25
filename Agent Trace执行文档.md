# manual-agent Agent Trace 执行文档

## 1. 需求

当前需要解决两个独立问题：

1. 使用 Trace 查看一次 Agent 执行的调用结构和各步骤耗时；
2. 把现有终端输出保存为文件，用于复盘 Thinking、Tool Call、Tool Result 和最终回答。

三类信息的职责如下：

| 信息 | 负责回答的问题 | 当前实现方式 |
|---|---|---|
| Trace | 谁调用了谁、每一步耗时多久 | OpenTelemetry + Jaeger |
| 终端记录 | Agent 当时具体输出了什么 | 保存 stdout |
| 诊断日志 | 程序内部为什么失败 | 保留现有 SLF4J |

Trace 和终端记录通过同一个 `traceId` 关联。Trace 不重复保存完整提示词、Thinking、工具参数或工具结果。

## 2. 设计

### 2.1 Trace 结构

第一版只建立三个业务 Span：

```text
agent.run
├── llm.call
├── tool.execute <tool-name>
│   └── agent.run                 子 Agent 调用 task 工具时自然形成
└── llm.call
```

埋点边界使用现有方法：

| Span | 方法 | 选择原因 |
|---|---|---|
| `agent.run` | `AgentLoop.run()` | 一次调用覆盖从收到本轮任务到返回最终结论的完整 Agent 生命周期 |
| `llm.call` | `AgentLoop.createStreamingTurn()` | 方法返回时模型流已经消费完成，耗时包含完整流式响应 |
| `tool.execute` | `AgentLoop.executeTool()` | 方法覆盖 Hook、工具执行和工具结果构造，且在真实工具线程中运行 |

OpenTelemetry 负责生成 `traceId`、`spanId`、父子关系和时间数据，并通过 OTLP 导出到 Jaeger。项目不实现自己的 Trace ID、事件协议、JSONL Trace 或树重建逻辑。

### 2.2 终端记录

`StreamOutputPrinter` 继续只负责终端显示，不增加 Trace 文件职责。运行程序时把 stdout 同时输出到终端和文件：

```text
System.out
├── 终端
└── .task_outputs/transcripts/<本次运行>.log
```

现有 SLF4J 诊断日志不迁入 Trace。第一版也不要求把 stderr 合并进终端记录。

### 2.3 本次不做

- 不新增 `TraceEvent`、`TraceRecorder`、`EventBus` 或自定义 Exporter；
- 不把模型正文、Thinking、工具参数和工具结果写入 Span 属性；
- 不追踪后台命令从启动到最终结束的独立生命周期；
- 不给日志统一注入 Trace ID；
- 不建设 Trace 查询页面，直接使用 Jaeger；
- 不修改 `StreamOutputPrinter` 的现有输出格式和截断规则。

## 3. 阶段一：准备 OpenTelemetry 和 Jaeger

### 3.1 修改 Maven 依赖

修改 `demo/manual-agent/pom.xml`。

在 `<properties>` 中增加版本：

```xml
<!-- OpenTelemetry API 由业务代码用于读取当前 Span。 -->
<opentelemetry.version>1.64.0</opentelemetry.version>

<!-- 注解版本与本地使用的 OpenTelemetry Java Agent 版本保持一致。 -->
<opentelemetry-instrumentation.version>2.30.0</opentelemetry-instrumentation.version>
```

在 `<dependencies>` 中增加：

```xml
<!-- Java Agent 启动时会把这些 API 调用接入真实 OpenTelemetry SDK。 -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>

<!-- @WithSpan 用于声明 Agent、LLM 和 Tool 的方法级 Span。 -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-instrumentation-annotations</artifactId>
    <version>${opentelemetry-instrumentation.version}</version>
</dependency>
```

不添加 `opentelemetry-sdk` 和 OTLP Exporter 依赖；它们由 Java Agent 在运行时提供。

### 3.2 准备 Java Agent

从 OpenTelemetry Java Instrumentation Releases 下载 `opentelemetry-javaagent.jar` 2.30.0，保存到固定位置，例如：

```text
C:\tools\opentelemetry-javaagent.jar
```

该文件属于本地运行工具，不提交到 Git。

### 3.3 启动 Jaeger

手动执行：

```powershell
# 启动本地 Jaeger，4318 接收 OTLP/HTTP Trace，16686 提供查询页面。
docker run --rm --name jaeger `
  -p 16686:16686 `
  -p 4318:4318 `
  cr.jaegertracing.io/jaegertracing/jaeger:2.20.0
```

浏览器打开：

```text
http://localhost:16686
```

### 3.4 配置程序启动参数

在 IntelliJ IDEA 的 `ManualAgentApplication` 运行配置中增加 VM option：

```text
-javaagent:C:\tools\opentelemetry-javaagent.jar
```

增加环境变量：

```text
OTEL_SERVICE_NAME=manual-agent
OTEL_TRACES_EXPORTER=otlp
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

`OTEL_METRICS_EXPORTER` 和 `OTEL_LOGS_EXPORTER` 设为 `none`，保证本阶段只处理 Trace。

### 3.5 阶段验收

- `mvn test` 通过；
- 不添加 `-javaagent` 时程序仍能正常启动，OpenTelemetry API 退化为 no-op；
- 添加 `-javaagent` 后控制台没有 OTLP 连接错误；
- `http://localhost:16686` 可以打开；
- 尚未增加业务 Span 时 Jaeger 没有 Agent 调用树属于正常现象。

阶段一未通过时，不进入业务埋点。

## 4. 阶段二：增加三个业务 Span

### 4.1 增加 import

修改 `AgentLoop.java`，增加：

```java
// 读取 Java Agent 建立的当前 Span，并补充动态名称。
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
```

### 4.2 记录 Agent 生命周期

在 `AgentLoop.run()` 上增加注解：

```java
/**
 * 执行一次完整 Agent 调用。
 *
 * @param messages 可修改的会话消息历史
 * @param turnContext 只进入本轮模型请求、不写入会话历史的临时上下文
 * @return 当前任务最终 assistant 回复中的文本结论
 */
@WithSpan("agent.run")
public String run(
        List<MessageParam> messages,
        String turnContext
) {
    // 保留现有方法实现，不改变 Agent Loop 控制流。
}
```

主 Agent 和子 Agent 使用同一个 `AgentLoop.run()` 边界。`TaskTool` 同步调用子 Agent 时，子 `agent.run` 会成为当前 `tool.execute task` 的子 Span，不新增子 Agent 专用事件。

### 4.3 记录完整模型流耗时

在 `AgentLoop.createStreamingTurn()` 上增加注解，并在方法开头补充动态名称：

```java
/**
 * 创建并完整消费一次模型响应流。
 *
 * @param request 已构造完成的模型请求
 * @return 本次模型流可安全进入历史的内容和工具执行
 */
@WithSpan("llm.call")
private StreamTurn createStreamingTurn(
        MessageCreateParams request
) {
    // 在 Jaeger 中直接显示当前模型，具体请求和响应内容仍只进入终端。
    Span.current().updateName(
            "llm.call " + model
    );

    // 保留现有流式消费实现。
}
```

不要把 Span 放在 `client.messages().createStreaming(...)` 单行外面；Span 必须覆盖后续事件消费，才能得到用户实际等待的时间。

### 4.4 记录工具执行耗时

在 `AgentLoop.executeTool()` 上增加注解，并使用现有工具名称更新 Span：

```java
/**
 * 执行一个已经完整关闭的工具调用。
 *
 * @param toolUse 已收到 content_block_stop 的工具调用
 * @return 保留真实成功或失败状态的工具执行结果
 */
@WithSpan("tool.execute")
private ToolExecutionResult executeTool(
        ToolUseBlock toolUse
) {
    // 使用真实工具名区分本地工具、task 子 Agent 工具和 MCP 工具。
    Span.current().updateName(
            "tool.execute " + toolUse.name()
    );

    // 保留现有 Hook 和工具执行管线。
}
```

第一版不写工具输入、结果和错误详情到 Span。MCP 工具沿用 `mcp__<namespace>__<tool>` 名称，不增加平行的 MCP Span。

### 4.5 阶段验收

执行一个不调用工具的简单问题，Jaeger 中必须出现：

```text
agent.run
└── llm.call <model>
```

执行一个会调用 `read_file` 或其他本地工具的问题，必须出现：

```text
agent.run
├── llm.call <model>
├── tool.execute read_file
└── llm.call <model>
```

验收时确认：

- `llm.call` 的耗时覆盖终端等待模型流的时间，不是只记录建立 HTTP 连接的时间；
- `tool.execute` 名称包含真实工具名；
- 同一次用户任务中的三个 Span 使用同一个 `traceId`；
- 原有终端文本、工具调用和工具结果格式不变。

## 5. 阶段三：验证虚拟线程父子关系

本阶段先验证，不默认修改代码。

### 5.1 验证前台工具

执行一个确定会调用工具的任务，在 Jaeger 中检查 `tool.execute` 是否位于对应 `agent.run` 下。

如果父子关系正确，本阶段不改 `ToolExecutionScheduler`。

如果 `tool.execute` 成为独立 Trace，再把 `ToolExecutionScheduler` 的 executor 改为 OpenTelemetry 提供的包装器：

```java
// 为每次提交捕获当时的 OpenTelemetry Context，使虚拟线程中的 Tool Span 继承 Agent Span。
private final ExecutorService executor =
        Context.taskWrapping(
                Executors.newVirtualThreadPerTaskExecutor()
        );
```

同时增加：

```java
// 使用框架提供的跨线程上下文传播，不维护自定义 parentId 或 ThreadLocal。
import io.opentelemetry.context.Context;
```

### 5.2 验证子 Agent

执行一个会调用 `task` 工具的任务。期望结构：

```text
agent.run
├── llm.call <model>
├── tool.execute task
│   └── agent.run
│       ├── llm.call <model>
│       └── tool.execute <subagent-tool>
└── llm.call <model>
```

子 Agent 的终端仍保持静默；静默只影响显示，不应影响 Trace。

### 5.3 验证 MCP

执行一个 Git 或 GitHub MCP 工具。期望只出现一个业务工具 Span：

```text
tool.execute mcp__git__<tool>
```

底层 SDK 或 HTTP 自动埋点如果产生子 Span，可以保留；不得再手工增加一套 `mcp.started/mcp.finished`。

### 5.4 阶段验收

- 本地工具、`task` 和 MCP 工具都属于发起它们的 `agent.run`；
- 子 Agent 的 `agent.run` 位于 `tool.execute task` 下；
- 并发工具可以显示为同一 Agent 下时间范围重叠的兄弟 Span；
- 只有实际出现断链时才提交 `Context.taskWrapping` 改动；
- 本阶段不验收后台命令实际运行到结束的十分钟生命周期，`tool.execute` 只覆盖启动工具返回前的时间。

## 6. 阶段四：保存终端输出并关联 Trace

### 6.1 保存终端输出

优先使用运行工具提供的控制台保存能力，不修改 `StreamOutputPrinter`。

IntelliJ IDEA 操作：

1. 打开“运行/调试配置”；
2. 选择 `ManualAgentApplication`；
3. 在“修改选项”中启用“将控制台输出保存到文件（Save console output to file）”；
4. 保存到仓库下的 `.task_outputs/transcripts/manual-agent.log`；
5. `.task_outputs/` 已在仓库根 `.gitignore` 中排除，不修改现有忽略规则。

该配置保存控制台输出。用户通过键盘输入的原文不是程序输出，第一版不要求自动保存。

### 6.2 输出 Trace ID

在 `AgentLoop.run()` 方法体开头读取当前 Span。只有 Java Agent 已建立有效 Trace 时才打印：

```java
// 取得本轮 Agent Span，用于关联 Jaeger Trace 和终端记录。
Span currentAgentSpan =
        Span.current();

// Java Agent 未启用时不输出无效的全零 Trace ID。
if (currentAgentSpan.getSpanContext().isValid()) {
    outputPrinter.printLocalMessage(
            "[Trace ID] "
                    + currentAgentSpan.getSpanContext()
                            .getTraceId()
    );
}
```

父 Agent 终端会显示该行；子 Agent 使用静默 `StreamOutputPrinter`，不会增加终端噪声，但仍属于同一个 Trace。

### 6.3 阶段验收

- 启动一次 Agent 后生成终端记录文件；
- 文件中包含 Thinking、正文、Tool Call、Tool Result、最终回答和 `[Trace ID]`；
- 使用文件中的 Trace ID 可以在 Jaeger 找到唯一 Trace；
- Jaeger 中不保存完整 Thinking、工具参数和工具结果；
- 未启用 Java Agent 时程序照常运行，终端不打印全零 Trace ID。

## 7. 最终验收

按以下用例顺序执行，每个用例保留 Jaeger Trace 和终端记录：

| 用例 | 操作 | Trace 验收 | 终端记录验收 |
|---|---|---|---|
| 纯模型回答 | 提问一个无需工具的问题 | `agent.run → llm.call` | 包含正文和 Trace ID |
| 本地工具 | 要求读取一个明确文件 | 出现 `tool.execute read_file` | 包含工具参数预览和结果 |
| 子 Agent | 要求委派 `task` 工具 | task Span 下出现子 `agent.run` | 保持现有 spawned/done 展示 |
| MCP | 要求执行 Git 或 GitHub MCP 查询 | 出现一个带 MCP 名称的 Tool Span | 包含 MCP 调用和结果 |
| 并发工具 | 触发两个可并发读取工具 | 两个 Tool Span 时间范围允许重叠 | 工具结果仍按现有协议顺序交付 |

最终必须同时满足：

```text
mvn test 通过
Trace 结构和耗时可在 Jaeger 查看
终端内容已持久化
Trace ID 可以关联两者
没有新增自研 Trace 框架
```

## 8. 参考资料

- OpenTelemetry Java Agent：<https://opentelemetry.io/docs/zero-code/java/agent/>
- Java Agent 自定义 API 埋点：<https://opentelemetry.io/docs/zero-code/java/agent/api/>
- `@WithSpan` 注解：<https://opentelemetry.io/docs/zero-code/java/agent/annotations/>
- OpenTelemetry Java Context：<https://opentelemetry.io/docs/languages/java/api/>
- Jaeger 本地启动：<https://www.jaegertracing.io/docs/2.20/getting-started/>
