# Quillith Harbor 评测 SOP

## 1. 目的与使用范围

本 SOP 说明在 Windows 宿主机上使用 Quillith 执行 Harbor 评测的推荐方式。

日常评测只暴露一个业务入口：

```text
D:\learn_claudecode\harbor-lab\memory-eval\scripts\start-job.ps1
```

`start-job.ps1` 会自动完成 Task 快速验证，并通过 Quillith Harbor launcher 启动评测。使用者不需要分别调用验证脚本、Maven 或 `harbor run`。

底层通用 launcher 位于：

```text
D:\learn_claudecode\demo\Quillith-miniHarness\quillith-harbor-run.ps1
```

它是 Memory Eval 的内部依赖，也可以用于非 Memory 的普通 Harbor Task，但不是 Memory Eval 的日常入口。

Memory Task 的适配、目录结构和结果分析细节以以下文档为准：

```text
D:\learn_claudecode\harbor-lab\memory-eval\SOP.md
```

## 2. Memory Eval 推荐流程

### 2.1 启动正式评测

在 `memory-eval` 目录执行：

```powershell
Set-Location "D:\learn_claudecode\harbor-lab\memory-eval"

.\scripts\start-job.ps1 `
    -Task longmemeval-edced276 `
    -Model anthropic/qwen3.5-flash
```

`-Task` 可以是 `tasks` 目录下的任务名，也可以是完整 Task 目录。

脚本会依次完成：

1. 检查 Task 是否存在；
2. 检查 Docker 和模型环境变量；
3. 自动运行 `validate-task.ps1`；
4. 构建当前 Quillith JAR；
5. 使用 `QuillithMemoryAgent` 启动后台 Harbor Job；
6. 返回 `JobName`、Job 路径、日志路径和状态查询命令。

启动成功后即结束当前操作，不同步等待正式评测完成，也不主动轮询。

### 2.2 查看状态

用户后续要求查看时执行：

```powershell
.\scripts\show-job.ps1 `
    -JobName <start-job 返回的 JobName>
```

状态含义：

- `STARTING`：后台进程已启动，Harbor 尚未创建 Job；
- `LAUNCH_ERROR`：启动进程已退出，但 Harbor 未创建 Job；
- `RUNNING`：Job 正在运行；
- `COMPLETED`：执行链已完成，需要继续检查答案和评分证据；
- `ERROR`：执行链出现异常，不能把页面上的零分直接解释为能力得分。

### 2.3 分析结果

Job 完成后，用户可以直接让 AI 分析 Job 目录：

```text
分析这个 Memory Eval：
D:\learn_claudecode\harbor-lab\memory-eval\jobs\<job-name>
```

分析时至少检查：

1. Job 与 Trial 的 `result.json`；
2. `exception.txt`；
3. `agent/final_answer.json`；
4. `verifier/judge-result.json` 与 `reward.txt`；
5. `artifacts/manifest.json`；
6. `artifacts/memory/`；
7. Agent 日志和 Trace 证据。

结论必须区分执行错误、未评分、能力零分和评测通过，不得只根据 launcher 退出码或汇总分数判断。

## 3. 非日常操作

### 3.1 单独执行便宜验证

适配或修改 Task 后，可以只运行不调用正式模型的快速验证：

```powershell
.\scripts\validate-task.ps1 `
    -Task longmemeval-edced276
```

正式启动时 `start-job.ps1` 会再次自动执行该验证，因此不要求使用者在每次评测前手动运行。

### 3.2 验证 Trial 安装环境

新的基础镜像或 Agent 安装方式首次接入时执行：

```powershell
.\scripts\validate-task.ps1 `
    -Task longmemeval-edced276 `
    -InstallOnly
```

`-InstallOnly` 只验证 Docker 环境和 Agent 安装，不执行题目、不运行 Verifier，也不调用正式评测模型。

### 3.3 导入评测数据

数据导入属于一次性题目准备，不属于每次正式评测流程。LongMemEval 和 ConvoMem 分别使用现有 importer：

```text
scripts\import-longmemeval.ps1
scripts\import-convomem.ps1
```

导入后先完成便宜验证，并由用户决定是否启动正式评测。

### 3.4 直接使用通用 launcher

只有运行非 Memory 的普通 Harbor Task，或排查 Memory Eval 包装层时，才直接调用：

```powershell
& "D:\learn_claudecode\demo\Quillith-miniHarness\quillith-harbor-run.ps1" `
    --path <task-path> `
    --model "anthropic/qwen3.5-flash" `
    --jobs-dir <jobs-path> `
    --yes
```

不得为每个 Trial 分别执行 Maven，也不得在 Trial 容器内下载源码或构建 Quillith。launcher 会在宿主机完成一次构建，并把本次 JAR 和 OpenTelemetry Java Agent 交给 Harbor Adapter。

## 4. 任务步数边界

通用 `QuillithHarborAgent` 支持同一个 Harbor Trial 内的 Multi-step 续步，不再禁止所有 Multi-step Task。

`QuillithMemoryAgent` 的行为不同：一个 Memory Case 在一次 Harbor Agent 阶段内自行完成历史 Session 回放、Memory 空闲等待和最终问题回答，不使用 Harbor 的 Multi-step resume。两者是不同 Agent 的运行边界，不是两套需要使用者分别操作的评测流程。

## 5. Trial 数量与并发

Harbor 的两个参数含义不同：

- `--n-attempts <数量>`：每个任务生成多少个 Trial；
- `--n-concurrent <数量>`：最多同时执行多少个 Trial。

```text
总 Trial 数 = 任务数量 × n-attempts
```

Memory Eval 默认使用单题、单 Trial、`n-concurrent=1`。需要批量或重复评测时，应先确认模型服务和宿主机承载能力，再明确调整策略；不要把提高并发误认为增加 Trial 数量。

## 6. 环境与凭据

正式运行依赖：

- Maven；
- Harbor；
- Docker daemon；
- Java 21 Trial 环境；
- `C:\tools\opentelemetry-javaagent.jar`；
- 可用的模型 API Key。

模型凭据从以下宿主机环境变量中解析：

```text
QUILLITH_API_KEY
ANTHROPIC_API_KEY
DASHSCOPE_API_KEY
```

Base URL 优先使用 `QUILLITH_BASE_URL`，其次使用 `ANTHROPIC_BASE_URL`。不得在命令、日志、文档或回复中输出 API Key、Token 的实际值。

## 7. 失败处理

按执行层级定位失败：

1. `start-job.ps1` 同步报错：先处理 Task 校验、Docker、环境变量或 Maven 构建问题；
2. 状态为 `LAUNCH_ERROR`：查看返回的 launcher stdout 和 stderr；
3. 状态为 `ERROR`：查看 Trial `exception.txt`、`result.json` 和 Agent 日志；
4. Agent 完成但 Judge 失败：保留原始评分和失败原因，不改写为成功；
5. Memory、最终答案、Judge 或 Trace 证据缺失：先补齐或修复证据链，不重复运行碰运气；
6. 网络导致后台 Memory 任务暂时失败：保留 pending task，由既有唤醒和有限重试机制继续处理，不丢弃整个任务队列。

同一异常传播链避免重复记录。能够在当前层恢复时才捕获并重试；无法恢复时保留完整异常并在执行边界报告失败。

## 8. 最短操作清单

日常执行只需记住：

```text
启动：scripts\start-job.ps1
查看：scripts\show-job.ps1
分析：把完成后的 Job 路径交给 AI
```

`validate-task.ps1`、importer 和通用 launcher 都是准备或排障工具，不属于每次正式评测必须手动执行的步骤。
