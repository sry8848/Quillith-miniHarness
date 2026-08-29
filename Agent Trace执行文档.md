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

第一版只建立三个业务 Span。由于工具是在模型流回合内提交执行，实际父子关系为：

```text
agent.run
├── llm.call
│   └── tool.execute <tool-name>
│       └── agent.run             子 Agent 调用 task 工具时自然形成
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

<!-- 使用成熟的双路输出流，同时保留终端显示并保存本次会话文本。 -->
<commons-io.version>2.22.0</commons-io.version>
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

<!-- TeeOutputStream 只负责 stdout 分流，不承载 Trace 语义。 -->
<dependency>
    <groupId>commons-io</groupId>
    <artifactId>commons-io</artifactId>
    <version>${commons-io.version}</version>
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
docker run -d --name jaeger `
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

项目运行还需要两个业务环境变量：

```text
DASHSCOPE_API_KEY
GITHUB_PERSONAL_ACCESS_TOKEN
```

这两个变量可以继续放在 Windows 用户变量中，不要把真实值写进代码、文档或提交记录。设置变量后必须完全退出并重新打开 IntelliJ IDEA，因为 IDEA 启动的 Java 进程只会继承启动时已经存在的环境变量。Run/Debug Configuration 的 `Working directory` 是所有相对路径的起点；建议设为仓库根目录：

```text
D:\learn_claudecode\learn-claude-code
```

IntelliJ IDEA 的 Application 配置中，`JRE` 选择 Java 21，`Main class` 选择 `dev.learn.agent.manual.ManualAgentApplication`，`Use classpath of module` 选择 `manual-agent`。IDEA 官方文档对 `Working directory`、`Environment variables` 和 `VM options` 的含义与入口有明确说明：<https://www.jetbrains.com/help/idea/run-debug-configuration-java-application.html>。

### 3.5 阶段验收

- `mvn test` 通过；
- 不添加 `-javaagent` 时程序仍能正常启动，OpenTelemetry API 退化为 no-op；
- Jaeger 正在运行时，添加 `-javaagent` 后控制台没有 OTLP 连接错误；
- `http://localhost:16686` 可以打开；
- 尚未增加业务 Span 时 Jaeger 没有 Agent 调用树属于正常现象。

阶段一未通过时，不进入业务埋点。

### 3.6 执行记录

- 已完成：`pom.xml` 增加 OpenTelemetry API 和 `@WithSpan` 注解依赖；
- 已完成：下载 `C:\tools\opentelemetry-javaagent.jar` 2.30.0；
- 已完成：增加 Commons IO 2.22.0，用于终端 stdout 分流；
- 已通过：`mvn test`，15 个测试全部通过；
- 已通过：Jaeger 2.20.0 容器启动，16686 页面返回 HTTP 200，4318 OTLP/HTTP 接收端 ready；
- 已通过：附加 Java Agent 的 `mvn test`，15 个测试全部通过。
- 已确认：Windows 用户变量中存在 `DASHSCOPE_API_KEY` 和 `GITHUB_PERSONAL_ACCESS_TOKEN`；旧终端进程未继承它们，完全重启 IDEA 后再运行即可继承。

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
│   ├── POST
│   └── tool.execute read_file
└── llm.call <model>
```

这里 `tool.execute` 是第一轮 `llm.call` 的子 Span，而不是同级 Span。原因是当前实现会在 `createStreamingTurn()` 仍然消费模型流时提交并执行工具，`executeTool()` 的真实调用栈仍在该模型回合内。这个父子关系能准确回答“工具由哪次模型回合触发”，因此第一版不为追求图形上的同级节点而重构调用边界。

验收时确认：

- `llm.call` 的耗时覆盖终端等待模型流的时间，不是只记录建立 HTTP 连接的时间；
- `tool.execute` 名称包含真实工具名；
- 同一次用户任务中的三个 Span 使用同一个 `traceId`；
- 原有终端文本、工具调用和工具结果格式不变。

### 4.6 执行记录

- 已完成：`AgentLoop.run()` 增加 `agent.run`；
- 已完成：`createStreamingTurn()` 增加 `llm.call`，覆盖完整流消费；
- 已完成：`executeTool()` 增加 `tool.execute`，动态显示工具名称；
- 已通过：未附加 Java Agent 时 `mvn test`，15 个测试全部通过；
- 已通过：编译产物保留三个 `@WithSpan` 运行时注解；
- 待验收：真实 Agent 请求产生的 Jaeger Span 树，需使用项目运行所需环境变量完成。

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
│   └── tool.execute task
│       └── agent.run
│           └── llm.call <model>
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
- 已验证的本地工具、子 Agent 和 MCP 均未出现断链，因此不提交 `Context.taskWrapping` 改动；
- 并发工具的调度器必须允许同一安全批次并发启动；实际时间是否重叠取决于工具耗时和模型事件到达时序；
- 本阶段不验收后台命令实际运行到结束的十分钟生命周期，`tool.execute` 只覆盖启动工具返回前的时间。

### 5.5 执行记录

- 已通过：Java Agent 2.30.0 已加载，Maven 测试进程无 Agent 初始化错误；
- 已通过：Agent JAR 包含 Java executor/virtual thread 上下文传播 instrumentation；
- 已通过：使用真实用户变量启动应用并完成两次纯模型请求；其中一次在 Jaeger 容器重启后成功上报，证明当前无工具调用时父子 Span 链路正常；
- 已通过：强制 `read_file` 场景产生 `tool.execute read_file`，且该 Span 位于第一轮 `llm.call` 下；本地工具不需要新增 `Context.taskWrapping`；
- 已通过：`task` 子 Agent 的父子树和 Git MCP 的业务工具 Span 均完成真实验收；
- 已通过：同一模型响应返回两个 `read_file` tool_use，Trace `5399c1a5049dc51d8d84cf84e583805f` 出现两个同级 `tool.execute read_file`；本次文件读取很快且第二个流事件较晚，两个时间区间未重叠；
- 已通过：`ToolExecutionSchedulerTest.shouldRunConsecutiveSafeToolsConcurrently` 用闩锁验证两个并发安全工具可同时进入执行阶段；
- 额外观察：尝试用超大 `read_file` 结果人为拉长执行时间时，模型服务返回 `400 Unknown`。该响应没有提供足以确认原因的结构化错误，本次只能确认它不是 Trace 断链，不对错误来源作进一步归因。
- 未修改：已验证的工具场景没有断链，不加入 `Context.taskWrapping`。

## 6. 阶段四：保存终端输出并关联 Trace

### 6.1 保存终端输出

修改 `ManualAgentApplication.main()`，在取得 `cwd` 后立即执行以下动作：

1. 创建 `cwd/.task_outputs/transcripts`；
2. 用 `yyyyMMdd-HHmmss-SSS` 生成本次会话文件名；
3. 使用 Commons IO `TeeOutputStream` 把原始 `System.out` 和文件输出合并；
4. 用新的 `PrintStream` 替换 `System.out`；
5. 注册 JVM shutdown hook，关闭文件输出流。

最终文件形如：

```text
.task_outputs/transcripts/manual-agent-20260825-232545-042.log
```

这样 `ManualAgentApplication`、`TaskTool`、`TodoWriteTool`、权限提示和 `StreamOutputPrinter` 的 stdout 都会进入同一份终端记录。`.task_outputs/` 已在仓库根 `.gitignore` 中排除。

用户通过键盘输入的原文不是程序 stdout，第一版不要求自动保存。

### 6.2 输出 Trace ID

在 `AgentLoop.run()` 方法体开头读取当前 Span。只有 Java Agent 已建立有效 Trace 时才打印：

```java
// 取得本轮 Agent Span，用于关联 Jaeger Trace 和终端记录。
Span currentAgentSpan =
        Span.current();

// Java Agent 未启用时不输出无效的全零 Trace ID。
if (currentAgentSpan.getSpanContext().isValid()) {
    outputPrinter.printLocalMessage(
            "\n[Trace ID] "
                    + currentAgentSpan.getSpanContext()
                            .getTraceId()
                    + "\n"
    );
}
```

父 Agent 终端会显示该行；子 Agent 使用静默 `StreamOutputPrinter`，不会增加终端噪声。子 Agent 是否仍属于同一个 Trace 由第 5 节的真实工具调用验收确认，不能仅凭静默输出推断。

### 6.3 阶段验收

- 启动一次 Agent 后生成终端记录文件；
- 文件中包含 Thinking、正文、Tool Call、Tool Result、最终回答和 `[Trace ID]`；
- 使用文件中的 Trace ID 可以在 Jaeger 找到唯一 Trace；
- Jaeger 中不保存完整 Thinking、工具参数和工具结果；
- 未启用 Java Agent 时程序照常运行，终端不打印全零 Trace ID。

### 6.4 执行记录

- 已完成：`AgentLoop.run()` 在有效 Span 存在时输出 `[Trace ID]`，无 Java Agent 时不输出全零 ID；
- 已完成：入口自动创建带时间戳的终端记录文件，未改造 `StreamOutputPrinter`；
- 已通过：无模型 Token 启动边界测试，程序退出前已生成 transcript 文件并保存启动信息；
- 已通过：真实运行生成 `D:\learn_claudecode\learn-claude-code\demo\manual-agent\.task_outputs\transcripts\manual-agent-20260826-103506-345.log`，文件包含 `[Trace ID]` 和模型回复；
- 已通过：Jaeger 查询到 Trace `562175d33ab3b377eb71be69dba5ef31`，包含 `agent.run → llm.call qwen3.5-flash → POST`；
- 已通过：第二次模型请求 Trace `34be913548e70c0bf621fd11cb0bc5b5` 同样包含 3 个 Span，且终端转录保存了完整模型响应；本次提示虽然要求调用工具，但模型实际没有产生工具调用，因此不计入工具验收；
- 已通过：工具测试 Trace `b3e6ec612cb9b70fda632e0583849c98` 包含 `tool.execute read_file`；对应 transcript 为 `D:\learn_claudecode\learn-claude-code\demo\manual-agent\.task_outputs\transcripts\manual-agent-20260826-105108-805.log`，文件同时包含 `[工具调用] read_file`、`[工具结果] read_file` 和 `TOOL_OK`；
- 已通过：`task` Trace `f95065bc020fe296bb5fdbe77cb7b728` 包含 `tool.execute task → agent.run → llm.call`；对应 transcript 为 `D:\learn_claudecode\learn-claude-code\demo\manual-agent\.task_outputs\transcripts\manual-agent-20260826-105733-910.log`，包含 `[Subagent spawned]`、`[Subagent done]` 和 `TASK_OK`；
- 已通过：Git MCP Trace `676d3b026bd3c31e85ba10af0e80d91b` 包含 `tool.execute mcp__git__git_status`；对应 transcript 为 `D:\learn_claudecode\learn-claude-code\demo\manual-agent\.task_outputs\transcripts\manual-agent-20260826-110203-805.log`，包含 MCP 调用、成功结果和 `MCP_OK`；
- 已通过：并发 Trace `5399c1a5049dc51d8d84cf84e583805f` 的两个 `read_file` Span 共享同一个第一轮 `llm.call` 父级；
- 已清理：task 验收期间模型误创建的临时 `task_67e12049` 文件已删除，不保留测试数据；
- 说明：第一次请求时 Jaeger 容器已退出，导出器报 `Connection refused`；重启容器后再次请求成功，故该次失败是接收端未运行，不是业务 Span 代码失败。

## 7. 最终验收

按以下用例顺序执行，每个用例保留 Jaeger Trace 和终端记录：

| 用例 | 操作 | Trace 验收 | 终端记录验收 |
|---|---|---|---|
| 纯模型回答 | 提问一个无需工具的问题 | `agent.run → llm.call` | 包含正文和 Trace ID |
| 本地工具 | 要求读取一个明确文件 | 第一轮 `llm.call` 下出现 `tool.execute read_file` | 包含工具参数预览和结果 |
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

当前执行状态：代码、Jaeger 接收端、纯模型 Trace、本地 `read_file`、`task` 子 Agent、Git MCP、并发调度器和终端转录已完成验收；真实并发调用本次未观察到时间重叠，但不影响“同批次可并发启动”的调度器契约。

### 7.1 最终执行记录

- 已通过：普通模式 `mvn test`，15 个测试全部通过；
- 已通过：附加 OpenTelemetry Java Agent 的 `mvn test`，15 个测试全部通过；
- 已通过：Jaeger 2.20.0 容器和 OTLP/HTTP 接收端启动；
- 已通过：程序启动边界生成 transcript 文件；
- 已通过：用户变量生效后完成真实模型请求，Jaeger 服务列表出现 `manual-agent`；
- 已通过：纯模型 Trace `agent.run → llm.call qwen3.5-flash → POST`，终端转录文件和 Trace ID 均可关联；
- 已通过：本地 `read_file` Trace 出现 `tool.execute read_file`，终端记录同时保存工具调用和工具结果；
- 已通过：`task` 子 Agent Trace 出现 `tool.execute task → agent.run → llm.call`，父子 Trace ID 一致；
- 已通过：Git MCP Trace 出现 `tool.execute mcp__git__git_status`，终端记录保存 MCP 调用和结果；
- 已通过：并发调度器单元测试通过；真实同一响应返回两个 `read_file` tool_use，并生成两个同级 Tool Span；

### 7.2 IntelliJ IDEA 一次完整运行

1. 完全退出 IntelliJ IDEA，再重新打开项目。用户变量只会被新启动的 IDEA 继承。
2. 确认 Docker 中 Jaeger 正在运行。第一次启动使用：

   ```powershell
   docker run -d --name jaeger -p 16686:16686 -p 4318:4318 cr.jaegertracing.io/jaegertracing/jaeger:2.20.0
   ```

   如果容器已经存在但停止，使用 `docker start jaeger`。
3. 打开 `Run` → `Edit Configurations...`，新建或选择 `Application` 配置。
4. 填写：

   ```text
   Main class: dev.learn.agent.manual.ManualAgentApplication
   Use classpath of module: manual-agent
   JRE: Java 21
   Working directory: D:\learn_claudecode\learn-claude-code
   ```

5. 展开 `Modify options`，显示 `VM options`，填入：

   ```text
   -javaagent:C:\tools\opentelemetry-javaagent.jar
   ```

6. 在 `Environment variables` 中加入以下六项：

   ```text
   OTEL_SERVICE_NAME=manual-agent
   OTEL_TRACES_EXPORTER=otlp
   OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
   OTEL_METRICS_EXPORTER=none
   OTEL_LOGS_EXPORTER=none
   ```

   同时确保 `DASHSCOPE_API_KEY` 和 `GITHUB_PERSONAL_ACCESS_TOKEN` 仍在父进程环境中；不要把真实 Token 写到仓库或发到聊天中。
7. 点击 `Apply` → `Run`。看到 `输入任务并回车` 后，先输入一个不需要工具的问题，例如：

   ```text
   请只回复 trace ok，不要调用工具。
   ```

8. 终端会出现：

   ```text
   [Trace ID] <一串十六进制字符>
   ```

   打开 <http://localhost:16686>，服务选择 `manual-agent`，找到同一个 Trace ID。正常结果至少有：

   ```text
   agent.run
   └── llm.call qwen3.5-flash
       └── POST（DashScope HTTP 请求）
   ```

   再输入一个强制工具的最小用例：

   ```text
   必须先调用 read_file，参数为 {"path":"pom.xml","limit":3}，成功后只回复 TOOL_OK。
   ```

   终端应出现 `[工具调用] read_file`、`[工具结果] read_file` 和 `TOOL_OK`；Jaeger 中应在第一轮 `llm.call` 下看到 `tool.execute read_file`。

   再验证子 Agent：

   ```text
   Mandatory test: call task with {"description":"Reply only SUB_OK. Do not call any tool."}; after it returns reply TASK_OK.
   ```

   Jaeger 应看到 `tool.execute task → agent.run → llm.call`；终端应看到 `[Subagent spawned]`、`[Subagent done]` 和 `TASK_OK`。

   最后验证 Git MCP：

   ```text
   Mandatory MCP test: call mcp__git__git_status, then reply MCP_OK.
   ```

   Jaeger 应看到 `tool.execute mcp__git__git_status`；终端应看到 MCP 工具调用、成功结果和 `MCP_OK`。

   验证同一响应中的并发安全工具：

   ```text
   In one response issue exactly two read_file calls: {"path":"pom.xml","limit":1} and {"path":"src/main/java/dev/learn/agent/manual/tool/tools/ReadFileTool.java","limit":1}; then reply CONCURRENT_OK.
   ```

   Jaeger 应看到两个同级 `tool.execute read_file`。工具很快时两个时间区间可能不重叠；并发契约由 `ToolExecutionSchedulerTest` 的闩锁测试保证。

9. 在终端输出的 `[Terminal transcript]` 路径打开 `.log` 文件，确认它与屏幕显示内容一致。退出程序使用 `q` 或 `exit`；Jaeger 容器可继续保留给下一次运行。

## 8. 参考资料

- OpenTelemetry Java Agent：<https://opentelemetry.io/docs/zero-code/java/agent/>
- Java Agent 自定义 API 埋点：<https://opentelemetry.io/docs/zero-code/java/agent/api/>
- `@WithSpan` 注解：<https://opentelemetry.io/docs/zero-code/java/agent/annotations/>
- OpenTelemetry Java Context：<https://opentelemetry.io/docs/languages/java/api/>
- Apache Commons IO Release Notes：<https://commons.apache.org/proper/commons-io/changes.html>
- Jaeger 本地启动：<https://www.jaegertracing.io/docs/2.20/getting-started/>
