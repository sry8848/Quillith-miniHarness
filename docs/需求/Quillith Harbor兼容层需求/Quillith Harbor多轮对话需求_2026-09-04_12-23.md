# Quillith Harbor 多轮对话需求

> 历史版本：第一阶段范围已收缩，当前版本见 [Quillith Harbor 最小多轮对话需求](./Quillith%20Harbor多轮对话需求_2026-09-04_22-16.md)。

## 需求陈述

在现有 Quillith Harbor 兼容层上增加 Harbor 原生 Multi-step 支持，使一次 Harbor Trial 中按顺序执行的多个 step 能够作为同一个现有 Quillith Interactive 会话中的多轮对话运行。

一次 Harbor Multi-step Trial 对应一次 Quillith Interactive 会话，每个 step 对应该会话中的一次普通用户请求。兼容层应将每个 step 的完整 instruction 依次提交给同一个 `ManualAgent`，并在当前请求处理完成后将控制权交还 Harbor，使 Harbor 能够执行本轮 verifier，再继续提交下一轮请求。

本需求复用 Quillith 已有 Interactive 会话能力，不新增另一套多轮会话模型或 Agent 执行逻辑。Harbor 接入只负责连接 Harbor 的逐轮调用方式与现有 Interactive 会话；每轮最终仍通过现有 `ManualAgent.submit(instruction)` 执行。

本需求中的“多轮对话”特指预先定义的固定多轮任务：

- Harbor 任务通过 `[[steps]]` 声明有序 step；
- 每个 step 的 `instruction.md` 表示该轮新增的一条完整用户消息，包括其中原有的换行和格式，不得按行拆分成多轮消息；
- Harbor 等待当前轮 Quillith 执行完成后，再提交下一轮消息；
- 不引入根据 Quillith 回复动态生成下一条用户消息的用户模拟器。

当 Harbor 为 Multi-step Trial 显式启用 `resume_trajectory` 时，同一 Trial 内的后续 step 必须继续使用首个 step 创建的同一个 Quillith Interactive 会话，包括同一个 `ManualAgent` 和同一个 `AgentRuntime`，不得为每个 step 重新创建。延续范围不是仅依赖工作目录中的文件，而是包括：

- 父 Agent 的完整对话 history，包括此前各轮用户消息、模型回复、工具调用和工具结果；
- 当前 Session 的 `AgentState`；
- Todo、Task、后台任务以及其他 Session 级运行状态；
- 同一 Trial 工作目录中前序 step 已产生的文件系统状态。

因此，后续 step 中出现“刚才的文件”“按上一轮约定继续”等依赖前文的表达时，Quillith 应由现有 Interactive 会话自然保留并使用真实的前序对话和 Session 状态，而不是把历史消息重新拼接成一条新 prompt，也不能仅依赖长期记忆功能推测前文。

多轮能力必须通过 Harbor 原生 resume 协议显式开启。未启用 `resume_trajectory` 的 Multi-step Trial 仍按 Harbor 的独立会话语义运行；现有单轮 Trial 继续保持“一条 instruction 对应一次 Quillith Exec”的行为，不得因本需求发生隐式续聊或其他兼容性变化。

多轮 Session 的生命周期仅限当前 Trial：

- 不跨 Trial 或 Harbor Job 复用；
- 不支持 Quillith 进程异常退出后的会话恢复；
- 不支持从既有 native trajectory 或 ATIF trajectory 加载会话；
- 不支持 handoff。

每个 step 完成后，Harbor 继续按照原生 Multi-step 流程执行该 step 的 verifier、`min_reward` 判断和结果归档。总分使用任务自身配置的 `multi_step_reward_strategy`，兼容层不固定为平均分或最终轮得分，也不改变 Harbor 的提前终止规则。

任一轮 Quillith 执行失败、超时或返回非零退出状态时，兼容层必须如实将失败反馈给 Harbor，不得通过重试、降级、跳过该轮或伪装成功继续后续对话。

日志应按 step 保留并可定位到对应轮次。本需求不包含 ATIF trajectory 生成，也不要求把多个 step 的日志合并成新的统一轨迹格式。

本阶段只建设多轮对话运行能力，不创建或改造具体 Multi-step 测评用例。后续选定真实用例后，另行建立需求文档，定义对话脚本、环境、逐轮验证方式和验收标准。
