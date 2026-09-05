# AgentSession 与模型调用 Telemetry 需求

## 一、目标

统一 Agent 会话、用户提交、模型调用和工具调用的语义，并补充模型调用过程中后续成本分析、Trace、Eval 和问题排查所需的基础 Telemetry。

本需求只建立必要的业务 Identity 和 OpenTelemetry 数据，不建设新的 Metrics 系统、Observability 平台或独立 Telemetry 存储。

---

## 二、AgentSession

`AgentSession` 表示一段连续且共享对话历史的 Agent 会话。

同一个 `AgentSession` 中可以发生多次用户提交，这些提交共享：

```text
conversation history
session state
sessionId
```

系统统一使用 `AgentSession` 表达当前 `ManualAgent` 所承担的会话语义，不再把 Agent 实例与 Session 描述为两个彼此独立的业务概念。

当前交互模式下一次程序启动到退出对应一个 `AgentSession`；单次执行模式下一次程序执行对应一个 `AgentSession`。

本阶段不要求支持进程重启后恢复旧 Session，也不要求一个 `AgentSession` 同时承载多份对话历史。

---

## 三、Session Identity

每个 `AgentSession` 必须拥有唯一的：

```text
sessionId
```

`sessionId` 用于关联同一段连续对话产生的多次 Trace，以及后续接入的 Logs、Transcript、Eval 和其他运行时数据。

在 OpenTelemetry 中，`sessionId` 使用标准属性表达为：

```text
gen_ai.conversation.id
```

同一个 `AgentSession` 产生的相关 Span 必须使用相同的 `gen_ai.conversation.id`。

`sessionId` 是业务 Identity，不能使用 `traceId`、`spanId` 或 Provider 返回的 ID 代替。

---

## 四、Turn

一次有效的：

```text
AgentSession.submit(...)
```

对应一个 Turn。

一次 Turn 使用一条 OpenTelemetry Trace 表达，由 `traceId` 标识，不再额外创建 `turnId`。

Turn 的范围覆盖该次提交触发的完整同步执行链，包括必要的 Hook、上下文处理、模型调用、工具调用、Sub-Agent 调用和记忆处理。

输入未满足 `submit` 调用契约时不产生 Turn。输入通过调用契约校验后，即使后续被 Hook 阻止或执行失败，仍然视为一次已经发生的 Turn，并保留对应 Trace。

同一个 Turn 内发生的多个操作通过 Span 层级和调用关系关联，不再为 Turn 建立第二套业务 Identity。

---

## 五、Model Call

Model Call 表示业务代码通过模型 SDK 发起的一次逻辑模型推理。

每个 Model Call 使用一个独立的 OpenTelemetry GenAI inference Span 表达，由 OpenTelemetry 自动生成的 `spanId` 标识，不再额外创建 `modelCallId`。

Model Call 按逻辑 SDK 调用划分，而不是按底层物理 HTTP 请求划分：

- SDK 在一次逻辑调用内部执行的透明 HTTP Retry，仍属于同一个 Model Call；
- 业务代码再次调用 SDK 发起新的推理，属于新的 Model Call；
- 工具结果返回模型后的下一轮推理，属于新的 Model Call；
- 达到输出上限后发起的续写，属于新的 Model Call；
- 错误恢复流程重新发起的模型推理，属于新的 Model Call。

所有由系统实际发起的逻辑模型推理都属于 Model Call，不因业务用途不同而排除，包括：

```text
父 Agent 推理
Sub-Agent 推理
Context 模型摘要
Memory 选择、提取和整理
输出续写
错误恢复后重新推理
其他通过模型 SDK 发起的推理
```

不调用模型的本地历史裁剪、文本处理或状态变更不属于 Model Call，不创建 inference Span。

---

## 六、Model Call Telemetry

每个 Model Call 的 inference Span 应记录 Provider 或 SDK 实际提供的以下数据：

```text
input_tokens
output_tokens
cache_read_tokens
cache_creation_tokens
finish_reason
actual_model
response_id
```

字段语义如下：

```text
input_tokens
= Provider 返回的输入 Token 数

output_tokens
= Provider 返回的输出 Token 数

cache_read_tokens
= Provider 返回的缓存读取 Token 数

cache_creation_tokens
= Provider 返回的缓存创建 Token 数

finish_reason
= Provider 返回的原始停止原因

actual_model
= Provider 响应声明的实际模型标识

response_id
= Provider 返回的响应标识
```

`actual_model` 不表示配置中的模型名称或 Router alias。配置模型与实际响应模型是不同语义，不得互相覆盖。

`finish_reason` 保留 Provider 或 SDK 的原始值，本阶段不建立跨 Provider Enum，也不进行统一映射。

---

## 七、缺失值

Telemetry 只记录 Provider 或 SDK 明确提供的事实。

Provider 明确返回字段且值为 `0` 时，对应 Span Attribute 必须记录为 `0`。

Provider 没有返回某个字段，或请求失败前尚未取得该字段时，不设置对应 Span Attribute，不得使用 `0` 代替未知值。

已经取得的字段应正常保留。一次调用部分失败时，不因其他字段缺失而丢弃已经明确返回的数据。

---

## 八、压缩与续写

使用模型生成上下文摘要时，该摘要请求作为普通 Model Call 记录在对应的上下文处理操作下。

后续模型调用使用压缩后的对话上下文时，应通过 OpenTelemetry 标准属性表达：

```text
gen_ai.conversation.compacted = true
```

纯本地上下文裁剪不创建 Model Call Span。

续写请求作为新的 Model Call 记录。前一次调用的 `finish_reason` 和 Span 调用顺序已经能够表达续写原因，本阶段不额外增加续写专用 Identity 或状态字段。

---

## 九、Tool Call

每次工具调用保留 Provider 返回的：

```text
toolCallId
```

每个 Tool Call 使用独立 Tool Span 表达，并通过现有 Trace、Span 层级和模型迭代调用关系关联到产生它的 Model Call。

一次 Model Call 可以产生：

```text
0..N Tool Call
```

不得在 Model Call 上使用单个 `toolCallId` 表示关联，也不额外创建用于替代 Provider `toolCallId` 的业务工具调用 ID。

---

## 十、数据关系

最终运行关系为：

```text
AgentSession
│
│ sessionId
│
├── Turn / Trace
│   ├── Agent Span
│   ├── Model Call / Inference Span
│   ├── Tool Call / Tool Span
│   └── Context 或 Memory Span
│       └── Model Call / Inference Span
│
└── Turn / Trace
    └── ...
```

Identity 分为：

```text
业务 Identity
└── sessionId

OpenTelemetry Identity
├── traceId
└── spanId

Provider Identity
├── responseId
└── toolCallId
```

本阶段不建立：

```text
turnId
modelCallId
```

---

## 十一、Turn Telemetry

Model Call Span 是 Token 和模型响应 Telemetry 的原始事实来源。

本阶段不在 Turn 层重复保存以下聚合字段：

```text
total_input_tokens
total_output_tokens
total_cache_read_tokens
total_cache_creation_tokens
```

需要按 Turn、Session、模型类型或调用用途统计时，应基于 Trace 中的原始 Model Call Span 派生。

在没有明确的实时汇总消费者之前，不在 Agent 执行链中维护第二份 Token 汇总状态。

---

## 十二、本阶段边界

本阶段包括：

- 建立统一的 `AgentSession` 语义；
- 建立唯一的业务 `sessionId`；
- 使用 `gen_ai.conversation.id` 关联同一 Session 的 Span；
- 使用 Trace 表达一次 Turn；
- 使用 inference Span 表达每次逻辑 Model Call；
- 为所有模型用途统一记录 Provider 实际提供的核心 Telemetry；
- 保留 Tool Call 自身的 Provider Identity 和调用关系。

本阶段不包括：

- 新建 Metrics 系统；
- 新建独立 Telemetry 存储；
- Turn Token 聚合；
- 跨进程 Session 恢复；
- 独立 `turnId`；
- 独立 `modelCallId`；
- 跨 Provider 的 `finish_reason` 统一语义层；
- Logs、Transcript、Eval 和其他模块的完整接入改造。
