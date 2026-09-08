# 调用 PowerShell 包装脚本时显式绑定下游参数数组

## 规则

PowerShell 脚本将 `--path`、`--model` 等参数继续传给下游 CLI 时，必须将这些参数显式绑定到包装脚本的剩余参数数组，不能依赖位置参数自动收集。

## 原因

`--name` 是下游 CLI 的参数形式，但 PowerShell 调用另一个 `.ps1` 文件时会先执行自己的参数绑定。如果包装脚本前面还有可选位置参数，PowerShell 可能把 `--path` 当成该位置参数的值，而不是交给 `ValueFromRemainingArguments`。

2026-09-08 的 Memory Eval 后台启动就因此把 `--path` 绑定成了 `OpenTelemetryAgentPath`，实际错误为：

```text
OpenTelemetry Java Agent 不存在：--path
```

Harbor 尚未创建 Job，状态脚本却只能显示 `STARTING`，进一步掩盖了启动前失败。

## 做法

1. 包装脚本内部调用另一个 PowerShell 脚本时，使用命名参数显式传入剩余参数数组：

   ```powershell
   & $launcherPath `
       -Agent "harbor_adapter:QuillithMemoryAgent" `
       -HarborArguments @(
           "--path", $taskPath,
           "--model", $model,
           "--job-name", $jobName
       )
   ```

2. 不要把下游的 `--xxx` 参数直接追加在仍有未绑定位置参数的 `.ps1` 调用后面。
3. 动态生成的 PowerShell 命令使用 splatting，不在可展开 here-string 中依赖反引号续行。反引号可能在构造字符串时被消费，使下一行变成独立命令。
4. 创建后台进程前使用 `[scriptblock]::Create($workerCommand)` 解析最终命令，并同步检查 Docker 等启动前置条件。
5. 后台启动时持久化进程 ID。若进程已退出且 Job 目录不存在，状态必须报告 `LAUNCH_ERROR`，不能一直报告 `STARTING`。
6. 在运行昂贵实验前，用不会调用真实模型的短命令验证包装脚本收到的参数边界。

## 验证

- `OpenTelemetryAgentPath` 保持调用方显式值或脚本默认值，不会变成 `--path`。
- Harbor 收到的 `--path`、`--model`、`--job-name` 和 `--jobs-dir` 与启动脚本输入一致。
- 动态生成的 worker 命令能够被 PowerShell 解析，`-Agent` 不会成为独立命令。
- Docker 未启动时，前台脚本直接报错且不创建后台进程身份。
- 后台进程退出且没有 Job 目录时，`show-job.ps1` 返回 `LAUNCH_ERROR`。
- 参数绑定验证不需要等待正式 Agent 执行或消耗模型调用。
