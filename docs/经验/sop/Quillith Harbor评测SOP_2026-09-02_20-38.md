# Quillith Harbor 评测 SOP

## 1. 使用范围

本 SOP 供 AI 在 Windows 宿主机上调用 Quillith Harbor launcher，评测 Harbor 单步 Linux 任务。

固定入口：

```text
D:\learn_claudecode\learn-claude-code\demo\manual-agent\quillith-harbor-run.ps1
```

AI 不得绕过 launcher 直接为每个 Trial 执行 Maven，也不得在 Trial 容器内安装 Maven、构建 Quillith 或下载 Quillith 源码。

## 2. 收集运行参数

开始前确认以下参数：

- Harbor 任务目录或数据集；
- 模型名；未指定时使用 `anthropic/qwen3.5-flash`；
- Job 结果目录；未指定时使用 `D:\learn_claudecode\harbor-lab\run-results`；
- 并行 Trial 数；未指定时由 Harbor 使用默认值。

首版不得运行 Harbor Multi-step 任务。发现任务为 Multi-step 时停止，并向用户说明当前版本不支持。

## 3. 检查宿主机

在 PowerShell 中执行：

```powershell
Get-Command mvn -ErrorAction Stop
Get-Command harbor -ErrorAction Stop
Get-Command docker -ErrorAction Stop

mvn -version
harbor --help
docker info
```

任一命令失败时停止，不启动评测。只报告缺失或不可用的工具，不自动安装或升级工具。

## 4. 检查宿主机环境变量

只检查变量是否存在，不输出变量值：

```powershell
if (-not (Test-Path Env:GITHUB_PERSONAL_ACCESS_TOKEN)) {
    throw "缺少 GITHUB_PERSONAL_ACCESS_TOKEN"
}

$hasModelApiKey =
    (Test-Path Env:QUILLITH_API_KEY) -or
    (Test-Path Env:ANTHROPIC_API_KEY) -or
    (Test-Path Env:DASHSCOPE_API_KEY)

if (-not $hasModelApiKey) {
    throw "缺少 QUILLITH_API_KEY、ANTHROPIC_API_KEY 或 DASHSCOPE_API_KEY"
}
```

Base URL 可以来自 `QUILLITH_BASE_URL` 或 `ANTHROPIC_BASE_URL`。两者都未配置时使用 Quillith 默认 Base URL。

不得在日志、回复或命令回显中输出 Token、API Key 的实际值。凭据由 launcher 启动的 Harbor 进程从宿主机环境变量读取，无需添加包含凭据的 `--agent-env` 参数。

## 5. 首次验证新的 Trial 环境

一个新的任务环境首次接入 Quillith 时，先运行安装验证：

```powershell
$launcher = "D:\learn_claudecode\learn-claude-code\demo\manual-agent\quillith-harbor-run.ps1"
$taskPath = "D:\learn_claudecode\harbor-lab\hello-world"
$jobsDir = "D:\learn_claudecode\harbor-lab\run-results-install-check"

& $launcher `
    --path $taskPath `
    --install-only `
    --agent-env "QUILLITH_BASH_EXECUTABLE=/bin/bash" `
    --jobs-dir $jobsDir `
    --yes

if ($LASTEXITCODE -ne 0) {
    throw "Quillith Harbor 安装验证失败"
}
```

安装验证必须确认 Trial 环境已有：

- Java 21 Runtime；
- `/bin/bash`；
- git；
- 能够读取上传到 `/installed-agent/quillith.jar` 的 JAR。

验证失败时读取该 Job 下的 `job.log`、Trial `trial.log` 和 Trial `result.json`。不要通过修改 Adapter 在 Trial 内安装缺失依赖；应修改或选择符合要求的 Trial 基础镜像，并在获得用户同意后更新对应文档。

已经验证过且基础镜像未变化的任务可以跳过本节。

## 6. 执行评测

### 6.1 单个任务运行一次

执行：

```powershell
$launcher = "D:\learn_claudecode\learn-claude-code\demo\manual-agent\quillith-harbor-run.ps1"
$taskPath = "D:\learn_claudecode\harbor-lab\hello-world"
$jobsDir = "D:\learn_claudecode\harbor-lab\run-results"

& $launcher `
    --path $taskPath `
    --model "anthropic/qwen3.5-flash" `
    --agent-env "QUILLITH_BASH_EXECUTABLE=/bin/bash" `
    --jobs-dir $jobsDir `
    --yes

if ($LASTEXITCODE -ne 0) {
    throw "Quillith Harbor 评测失败"
}
```

使用其他 Anthropic-compatible 模型时，只替换 `--model` 值，并保持 `anthropic/<模型名>` 格式。不得使用 `openai/`、`gemini/` 等协议前缀。

### 6.2 区分 Trial 数量和并发数

Harbor 使用两个不同参数：

- `--n-attempts <数量>`：每个任务运行多少次，用于生成多个 Trial；
- `--n-concurrent <数量>`：最多同时运行多少个 Trial，只控制并发上限。

只添加 `--n-concurrent 4` 不会生成 4 个 Trial。一个任务默认只运行一次，因此仍然只有一个 Trial。

AI 应先计算：

```text
总 Trial 数 = 任务数量 × n-attempts
```

然后根据宿主机和模型服务承载能力设置 `--n-concurrent`。并发数可以小于总 Trial 数，Harbor 会分批运行。

### 6.3 同一个任务并行评测多次

以下命令将同一个任务运行 4 次，并允许 4 个 Trial 同时执行：

```powershell
$launcher = "D:\learn_claudecode\learn-claude-code\demo\manual-agent\quillith-harbor-run.ps1"
$taskPath = "D:\learn_claudecode\harbor-lab\hello-world"
$jobsDir = "D:\learn_claudecode\harbor-lab\run-results"

& $launcher `
    --path $taskPath `
    --model "anthropic/qwen3.5-flash" `
    --n-attempts 4 `
    --n-concurrent 4 `
    --agent-env "QUILLITH_BASH_EXECUTABLE=/bin/bash" `
    --jobs-dir $jobsDir `
    --yes

if ($LASTEXITCODE -ne 0) {
    throw "Quillith Harbor 并行评测失败"
}
```

这次 launcher 只调用一次，Maven 只构建一次，4 个 Trial 全部使用本次构建生成的同一个 JAR。

### 6.4 多个任务并行评测

当本地数据集目录包含多个 Harbor 任务时，将数据集目录传给 `--path`。以下命令运行数据集中的全部任务，最多同时运行 4 个 Trial：

```powershell
$launcher = "D:\learn_claudecode\learn-claude-code\demo\manual-agent\quillith-harbor-run.ps1"
$datasetPath = "D:\path\to\harbor-dataset"
$jobsDir = "D:\learn_claudecode\harbor-lab\run-results"

& $launcher `
    --path $datasetPath `
    --model "anthropic/qwen3.5-flash" `
    --n-concurrent 4 `
    --agent-env "QUILLITH_BASH_EXECUTABLE=/bin/bash" `
    --jobs-dir $jobsDir `
    --yes

if ($LASTEXITCODE -ne 0) {
    throw "Quillith Harbor 数据集评测失败"
}
```

例如数据集有 10 个任务且没有设置 `--n-attempts`，总 Trial 数为 10，并发上限为 4。Harbor 会自动分批运行。

使用 Harbor Registry 数据集时，将 `--path $datasetPath` 替换为对应的 `--dataset <名称@版本>` 或 `--task <任务名>` 参数。

### 6.5 多个任务各运行多次

需要重复评测数据集中的每个任务时，同时设置两个参数：

```powershell
--n-attempts 3 `
--n-concurrent 4
```

例如数据集有 10 个任务，则总 Trial 数为 `10 × 3 = 30`，但任何时刻最多运行 4 个 Trial。

### 6.6 多 Trial 操作限制

不要为每个任务或 Trial 分别调用 launcher。一个 Job 的全部任务和重复次数必须放进同一次 launcher 调用，确保本 Job 只执行一次 Maven 构建，所有 Trial 使用同一个 JAR。

## 7. 检查评测结果

找到本次最新 Job：

```powershell
$latestJob = Get-ChildItem -LiteralPath $jobsDir -Directory |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $latestJob) {
    throw "未找到 Harbor Job 结果目录"
}

Get-Content -LiteralPath (Join-Path $latestJob.FullName "result.json")
Get-ChildItem -LiteralPath $latestJob.FullName -Recurse -Filter "trial.log" |
    ForEach-Object {
        Write-Host $_.FullName
        Get-Content -LiteralPath $_.FullName
    }
```

AI 必须确认：

- `n_errored_trials` 为 `0`；
- `n_completed_trials` 等于计划运行的 Trial 数；
- 每个 Trial 的 `exception_info` 为空；
- verifier 已执行且存在判分结果；
- Quillith stdout/stderr 日志已下载到 Trial 的 Agent 日志目录。

最后向用户报告 Job 目录、完成数量、错误数量、评分结果和失败 Trial 名称。不得只根据 launcher 的退出码判断评测成功。

## 8. 失败处理

按以下顺序定位：

1. launcher 提示 Maven build 失败：读取宿主机 Maven 输出，停止 Harbor Job；不得回退到旧 JAR。
2. Agent setup 失败：检查 Trial 是否具备 Java 21、`/bin/bash`、git，以及 JAR 是否成功上传。
3. 提示缺少 GitHub Token 或模型 API Key：检查对应宿主机环境变量是否存在，不输出变量值。
4. 提示模型协议不支持：将模型改为 `anthropic/<模型名>`，不要在 Adapter 内新增 Provider。
5. 模型请求鉴权或连接失败：检查 API Key 和 Base URL 对应关系；不得把凭据写进仓库文件。
6. Quillith 返回非零状态：读取 Trial `trial.log`、Quillith stdout/stderr 和 `result.json`，保留原始错误。
7. verifier 失败但 Agent 正常结束：报告 verifier 评分和失败断言，不把 Trial 改写为成功。

修复后重新运行 launcher。每次重新运行都是一个新的 Harbor Job，并重新基于当前 Quillith 源码构建一次 JAR。
