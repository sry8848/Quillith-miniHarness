[CmdletBinding()]
param(
    [string] $Agent = "harbor_adapter:QuillithHarborAgent",

    [string] $OpenTelemetryAgentPath = "C:\tools\opentelemetry-javaagent.jar",

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $HarborArguments
)

$ErrorActionPreference = "Stop"

# 1. 每次 launcher 调用只在宿主机执行一次 Maven package，不重复运行既有测试套件。
$projectDirectory = $PSScriptRoot
$pomPath = Join-Path $projectDirectory "pom.xml"
& mvn -q -f $pomPath -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Quillith Maven build 失败，Harbor Job 未启动。"
}

# 2. 只接受本次构建约定的固定 JAR，不搜索或回退到旧制品。
$jarPath = Join-Path $projectDirectory "target\quillith.jar"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "本次构建未生成 Quillith JAR：$jarPath"
}
$jarPath = (Resolve-Path -LiteralPath $jarPath).Path

# 3. 使用宿主机已经验证的 OpenTelemetry Java Agent，不在 Trial 内下载。
if (-not (Test-Path -LiteralPath $OpenTelemetryAgentPath -PathType Leaf)) {
    throw "OpenTelemetry Java Agent 不存在：$OpenTelemetryAgentPath"
}
$OpenTelemetryAgentPath =
        (Resolve-Path -LiteralPath $OpenTelemetryAgentPath).Path

# 4. 只为当前 Harbor 子进程暴露项目内 Adapter import path。
$previousPythonPath = $env:PYTHONPATH
$pathSeparator = [System.IO.Path]::PathSeparator
if ([string]::IsNullOrWhiteSpace($previousPythonPath)) {
    $env:PYTHONPATH = $projectDirectory
} else {
    $env:PYTHONPATH =
            "$projectDirectory$pathSeparator$previousPythonPath"
}

# 5. 注入调用方选择的 Adapter 和两个运行 JAR，其余参数原样转交 harbor run。
$harborExitCode = 1
try {
    & harbor run `
        --agent $Agent `
        --agent-kwarg "jar_path=$jarPath" `
        --agent-kwarg "otel_agent_path=$OpenTelemetryAgentPath" `
        @HarborArguments
    $harborExitCode = $LASTEXITCODE
} finally {
    $env:PYTHONPATH = $previousPythonPath
}

exit $harborExitCode
