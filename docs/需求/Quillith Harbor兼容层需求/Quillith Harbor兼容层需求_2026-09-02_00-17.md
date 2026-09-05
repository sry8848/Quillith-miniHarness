# Quillith Harbor 兼容层需求

## 需求描述

为 Quillith 增加一层 Harbor 适配，使 Harbor 能把 Quillith 作为评测 Agent 安装并运行。兼容层只负责连接 Harbor 的评测生命周期与 Quillith 已确定的 CLI 行为，不在 Harbor 侧重新实现 Agent、权限或记忆逻辑。

一次 Harbor 任务对应一次 Quillith Exec 进程：Harbor 提供的完整 instruction 作为一条 Agent Prompt 传给 `Quillith exec [options] <instruction>`，Quillith 在任务工作目录中完成执行后退出，不进入 Interactive 输入循环。

## 核心行为

- Exec 默认启用 Memory，并使用 `BYPASS` 权限模式；Harbor 的容器隔离环境承担外部安全边界。
- instruction 必须原样传递，空格、换行和 Shell 特殊字符不能因命令拼接而被修改或执行。
- 运行期间不能等待终端输入、工具审批或 Slash Command。
- Quillith 的标准输出和错误输出应保存到 Harbor 的 Agent 日志目录，并保持评测过程可观察。
- Quillith 正常完成时 Harbor 任务正常结束；Quillith 非零退出或启动失败时，兼容层应向 Harbor 暴露真实失败，不吞错或伪装成功。
- Agent 对任务工作目录产生的文件修改应直接保留，供 Harbor 后续验证器检查。

## 兼容层职责

兼容层需要完成三件事：

1. 在 Harbor 任务环境中准备并校验可执行的 Quillith。
2. 接收 Harbor 的 instruction 和运行环境，启动一次 Quillith Exec。
3. 将进程输出、退出状态和必要运行信息交还 Harbor。

Quillith 的具体制品交付方式，以及模型配置和凭据如何从 Harbor 映射到 Quillith，当前交互设计尚未规定，需要在执行设计前单独确认；兼容层不得自行引入新的 CLI 协议。

## 首版边界

- 不改变 Quillith Interactive 行为。
- 不由兼容层解析或执行 Quillith Slash Command。
- 不增加多轮会话、resume、handoff 或并发任务复用。
- 不要求首版生成 Harbor ATIF trajectory；先完成可安装、可执行、可记录日志、可正确返回结果的基本兼容。

## 验收结果

Harbor 能选择该兼容层运行一项评测，Quillith 收到完整任务并在评测工作目录中执行；整个过程无需人工输入，日志可查看，成功与失败状态准确，任务产物可被 Harbor 验证。
