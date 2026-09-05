# Quillith Harbor 兼容层执行文档

## 1. 文档定位

本执行文档一次完成 Quillith Harbor 兼容层首版，不再拆分子执行文档。

执行依据：

```text
Quillith Harbor兼容层需求_2026-09-02_15-25.md
Quillith Harbor兼容层技术评审_2026-09-02_15-35.md
```

最终调用关系固定为：

```text
quillith-harbor-run.ps1
    │
    ├─ 宿主机执行一次 mvn package
    ├─ 确定 target/quillith.jar
    └─ harbor run
           │
           ├─ Trial A：上传同一 JAR → Quillith Exec
           ├─ Trial B：上传同一 JAR → Quillith Exec
           └─ Trial C：上传同一 JAR → Quillith Exec
```

Maven 构建只能存在于宿主机 launcher。Harbor Adapter 的 `install()` 只验证 Trial 环境并上传已经构建好的 JAR，禁止安装 Maven、下载 Quillith 源码或执行构建。

本轮不新增自动化测试文件。脚本和 Adapter 通过构建检查及一次 Harbor smoke run 验证。

## 2. 本轮明确不做

- 不修改 Quillith Interactive、Slash Command 或 Agent Turn 行为。
- 不新增模型 Provider。
- 不生成 ATIF trajectory。
- 不支持 Harbor Multi-step、resume 或 handoff。
- 不增加构建缓存、制品仓库、版本管理或新的配置框架。
- 不创建独立的 Quillith Harbor CLI。
- 不在 Trial 中安装 Maven、JDK 或 Quillith 源码。
- 不为本轮新增 JUnit、Python 或 PowerShell 测试。

## 3. 文件改动清单

新增：

```text
D:/learn_claudecode/learn-claude-code/demo/manual-agent/harbor_adapter.py
D:/learn_claudecode/learn-claude-code/demo/manual-agent/quillith-harbor-run.ps1
```

修改：

```text
D:/learn_claudecode/learn-claude-code/demo/manual-agent/pom.xml
D:/learn_claudecode/learn-claude-code/demo/manual-agent/src/main/java/dev/learn/agent/manual/AgentRuntime.java
D:/learn_claudecode/learn-claude-code/demo/manual-agent/src/main/java/dev/learn/agent/manual/tool/tools/BashTool.java
D:/learn_claudecode/harbor-lab/hello-world/environment/Dockerfile
```

`hello-world` 只作为项目当前选定的 Harbor Linux smoke 环境。本轮只为它补齐 Java Runtime 和 git，不删除原有 Codex 依赖。

不修改：

```text
ApplicationOptions.java
InteractiveRunner.java
ExecRunner.java
ManualAgent.java
AgentLoop.java
src/test/**
```

`AgentRuntime.java` 和 `ManualAgentApplication.java` 当前已有用户的 AgentHome 相关修改。执行时必须保留这些修改，不得回退或顺手整理。

## 4. 生成 self-contained JAR

修改：

```text
demo/manual-agent/pom.xml
```

### 4.1 固定构建产物名称

在 `<build>` 中增加：

```xml
<finalName>quillith</finalName>
```

使 launcher 不需要解析 Maven 版本号，固定读取：

```text
target/quillith.jar
```

### 4.2 增加 Maven Shade Plugin

在现有 `<plugins>` 末尾增加：

```xml
<!-- 生成 Harbor Trial 可直接 java -jar 启动的完整运行制品。 -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <!-- 不生成 dependency-reduced-pom，避免构建修改项目依赖描述。 -->
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer" />
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>dev.learn.agent.manual.ManualAgentApplication</mainClass>
                    </transformer>
                </transformers>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`ServicesResourceTransformer` 保留运行依赖中的 Java Service Provider 注册；`ManifestResourceTransformer` 只负责写入现有应用入口。不要再增加 assembly、Spring Boot 打包或自定义 classpath 脚本。

执行后 `mvn package` 必须产生可直接运行的：

```text
demo/manual-agent/target/quillith.jar
```

## 5. 把模型和 Bash 运行配置接入环境变量

修改：

```text
src/main/java/dev/learn/agent/manual/AgentRuntime.java
```

不新增 `RuntimeConfig`、Builder 或配置文件。直接在现有 `AgentRuntime.create(...)` 装配入口读取四个环境变量。

### 5.1 调整常量

将现有：

```java
BASE_URL
MODEL
```

改名为：

```java
DEFAULT_BASE_URL
DEFAULT_MODEL
```

保留当前默认值：

```java
private static final String DEFAULT_BASE_URL =
        "https://dashscope.aliyuncs.com/apps/anthropic";
private static final String DEFAULT_MODEL =
        "qwen3.5-flash";
private static final String DEFAULT_WINDOWS_BASH_EXECUTABLE =
        "C:\\Windows\\System32\\bash.exe";
private static final String DEFAULT_LINUX_BASH_EXECUTABLE =
        "/bin/bash";
```

### 5.2 在 `create(...)` 中解析配置

删除当前直接创建 Windows Bash `Path` 和只读取 `DASHSCOPE_API_KEY` 的代码，按以下优先级在原位置直接解析：

```text
model:
QUILLITH_MODEL
→ DEFAULT_MODEL

apiKey:
QUILLITH_API_KEY
→ DASHSCOPE_API_KEY
→ 缺失时保持启动失败

baseUrl:
QUILLITH_BASE_URL
→ DEFAULT_BASE_URL

bashExecutable:
QUILLITH_BASH_EXECUTABLE
→ Windows：DEFAULT_WINDOWS_BASH_EXECUTABLE
→ 非 Windows：DEFAULT_LINUX_BASH_EXECUTABLE
```

代码保持直接，不创建通用配置解析器。结构使用以下伪代码：

```java
// 1. Harbor 显式模型配置优先，未提供时保持 Quillith 当前默认模型。
String configuredModel =
        System.getenv("QUILLITH_MODEL");
String model =
        configuredModel == null || configuredModel.isBlank()
                ? DEFAULT_MODEL
                : configuredModel;

// 2. Harbor 显式 API Key 优先，未提供时保持现有百炼环境变量。
String apiKey =
        System.getenv("QUILLITH_API_KEY");
if (apiKey == null || apiKey.isBlank()) {
    apiKey = System.getenv("DASHSCOPE_API_KEY");
}
if (apiKey == null || apiKey.isBlank()) {
    throw new IllegalStateException(
            "缺少环境变量 QUILLITH_API_KEY 或 DASHSCOPE_API_KEY"
    );
}

// 3. Harbor 显式 Base URL 优先，未提供时保持当前 Anthropic-compatible 地址。
String configuredBaseUrl =
        System.getenv("QUILLITH_BASE_URL");
String baseUrl =
        configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? DEFAULT_BASE_URL
                : configuredBaseUrl;

// 4. Bash 可以由运行环境显式指定，否则按当前平台选择默认 executable。
String configuredBashExecutable =
        System.getenv("QUILLITH_BASH_EXECUTABLE");
Path bashExecutable;
if (configuredBashExecutable != null) {
    if (configuredBashExecutable.isBlank()) {
        throw new IllegalStateException(
                "环境变量 QUILLITH_BASH_EXECUTABLE 不能为空"
        );
    }
    bashExecutable =
            Path.of(configuredBashExecutable);
} else {
    boolean windows =
            System.getProperty("os.name")
                    .startsWith("Windows");
    bashExecutable =
            Path.of(
                    windows
                            ? DEFAULT_WINDOWS_BASH_EXECUTABLE
                            : DEFAULT_LINUX_BASH_EXECUTABLE
            );
}
```

显式 Bash 配置为空属于非法配置，直接失败；不能静默退回平台默认值。

### 5.3 替换现有固定配置消费者

在 `AgentRuntime.create(...)` 内，将所有传入原 `MODEL` 的位置改为使用局部变量 `model`，包括：

```text
MemoryRuntime
ContextManager
parent AgentLoop
subagent AgentLoop
其他当前接收 MODEL 的装配对象
```

创建 `AnthropicClient` 时改为：

```java
AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        ...
```

`GITHUB_PERSONAL_ACCESS_TOKEN` 的读取、校验和失败行为保持原样。

## 6. 调整 BashTool 的平台语义

修改：

```text
src/main/java/dev/learn/agent/manual/tool/tools/BashTool.java
```

不改变 ProcessBuilder、超时、后台任务或输出治理逻辑，只修改已经不再准确的 Windows 专用语义：

- 类注释由“使用 Git Bash”改为“使用配置的 Bash executable”。
- 构造参数注释由“Git Bash 的 bash.exe 路径”改为“当前运行环境的 Bash executable 路径”。
- 路径不存在时的错误由“Git Bash 不存在”改为“Bash executable 不存在”。

现有 `Files.isRegularFile(...)` 启动校验继续保留。Linux `/bin/bash` 和 Windows `bash.exe` 都经过同一检查，不新增 Harbor 分支。

## 7. 新增 Harbor Adapter

新增：

```text
demo/manual-agent/harbor_adapter.py
```

Adapter 直接继承 Harbor 当前提供的 `BaseInstalledAgent`。只创建一个类，不再拆配置模块、命令构造器或结果对象。

### 7.1 类声明与构造

实现骨架：

```python
import shlex
from pathlib import Path
from typing import override

from harbor.agents.installed.base import BaseInstalledAgent
from harbor.agents.model_connection import ModelConnectionSpec
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext


class QuillithHarborAgent(BaseInstalledAgent):
    """在 Harbor Trial 中运行宿主机预构建的 Quillith JAR。"""

    SUPPORTS_ATIF = False
    SUPPORTS_RESUME = False
    MODEL_CONNECTION = ModelConnectionSpec(
        api_key_envs=(
            "QUILLITH_API_KEY",
            "ANTHROPIC_API_KEY",
            "DASHSCOPE_API_KEY",
        ),
        base_url_envs=(
            "QUILLITH_BASE_URL",
            "ANTHROPIC_BASE_URL",
        ),
    )

    _REMOTE_JAR_PATH = "/installed-agent/quillith.jar"

    def __init__(
        self,
        logs_dir: Path,
        jar_path: str,
        *args,
        **kwargs,
    ):
        """保存本次 Harbor Job 已经构建完成的唯一 JAR。"""
        super().__init__(logs_dir, *args, **kwargs)

        # 1. JAR 必须在创建 Trial 前已经由宿主机 launcher 构建完成。
        self._jar_path = Path(jar_path).expanduser().resolve()
        if not self._jar_path.is_file():
            raise FileNotFoundError(
                f"Quillith JAR 不存在：{self._jar_path}"
            )

        # 2. 带 provider 前缀时只接受 Anthropic-compatible 协议。
        self._quillith_model: str | None = None
        if self.model_name:
            if "/" in self.model_name:
                provider, model = self.model_name.split("/", 1)
                if provider != "anthropic":
                    raise ValueError(
                        f"Quillith 不支持模型协议：{provider}"
                    )
                if not model:
                    raise ValueError("Harbor model 缺少模型名称")
                self._quillith_model = model
            else:
                self._quillith_model = self.model_name

        # 3. GitHub Token 保持 Quillith 当前必需语义，并在 Trial 启动前失败。
        self._github_token = self._get_env(
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        )
        if not self._github_token:
            raise ValueError(
                "缺少 GITHUB_PERSONAL_ACCESS_TOKEN"
            )

        # 4. Harbor Linux 默认使用 /bin/bash，显式空值属于配置错误。
        configured_bash = self._get_env(
            "QUILLITH_BASH_EXECUTABLE"
        )
        if configured_bash is not None and not configured_bash.strip():
            raise ValueError(
                "QUILLITH_BASH_EXECUTABLE 不能为空"
            )
        self._bash_executable = configured_bash or "/bin/bash"
```

Harbor `--model` 约定为：

```text
anthropic/<model-name>  → 去掉 anthropic/ 后传给 Quillith
<model-name>            → 作为 Anthropic-compatible 模型名直接传递
其他 provider/<model>   → 创建 Agent 时立即失败
未提供 --model          → 不传 QUILLITH_MODEL，由 Quillith 使用默认值
```

### 7.2 Agent 名称和安装

实现：

```python
    @staticmethod
    @override
    def name() -> str:
        """返回 Harbor 结果中使用的 Agent 名称。"""
        return "quillith"

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        """验证 Trial 运行环境并上传宿主机已经构建的 JAR。"""
        # 1. Trial 只验证预装运行依赖，不在这里安装 JDK、Maven 或源码。
        quoted_bash = shlex.quote(self._bash_executable)
        await self.exec_as_agent(
            environment,
            command=(
                "command -v java >/dev/null && "
                "command -v git >/dev/null && "
                f"test -x {quoted_bash}"
            ),
        )

        # 2. 每个 Trial 上传同一个宿主 JAR；上传不是重新构建。
        await environment.upload_file(
            self._jar_path,
            self._REMOTE_JAR_PATH,
        )

        # 3. 立即确认默认 Agent 用户可以读取 JAR，且 Java Runtime 可用。
        await self.exec_as_agent(
            environment,
            command=(
                f"test -r {shlex.quote(self._REMOTE_JAR_PATH)} && "
                "java -version"
            ),
        )
```

不要调用 `ensure_system_dependencies(...)`，因为需求明确 Trial 环境已经包含 Java Runtime、bash 和 git；缺失时应失败，而不是在每个 Trial 动态安装。

### 7.3 instruction、配置和进程结果传递

Harbor 当前 `BaseEnvironment.exec(...)` 接口接收 Shell command 字符串，没有独立 argv 参数。因此不能把 instruction 拼接或 quote 后插入 command。使用独立环境变量保存 instruction，再由固定命令中的双引号参数展开：

```python
    @override
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        """在当前 Trial 工作目录中执行一次 Quillith Exec。"""
        # 1. 只映射 Harbor 显式模型连接；缺失字段留给 Quillith 默认值。
        connection = self.model_connection
        run_env = {
            "QUILLITH_INSTRUCTION": instruction,
            "QUILLITH_BASH_EXECUTABLE": self._bash_executable,
            "GITHUB_PERSONAL_ACCESS_TOKEN": self._github_token,
        }
        if self._quillith_model:
            run_env["QUILLITH_MODEL"] = self._quillith_model
        if connection.api_key:
            run_env["QUILLITH_API_KEY"] = connection.api_key
        if connection.configured_base_url:
            run_env["QUILLITH_BASE_URL"] = (
                connection.configured_base_url
            )

        # 2. 使用容器当前工作目录，不假定所有任务都固定为 /app。
        workdir_result = await environment.exec("pwd")
        workdir = (workdir_result.stdout or "").strip()
        if workdir_result.return_code != 0 or not workdir:
            raise RuntimeError("无法确定 Harbor Trial 工作目录")

        # 3. instruction 只作为一个双引号包围的 argv 值展开，不进入命令文本。
        await self.exec_as_agent(
            environment,
            command=(
                "java -jar /installed-agent/quillith.jar "
                'exec "$QUILLITH_INSTRUCTION" '
                "> >(tee /logs/agent/quillith.stdout.log) "
                "2> >(tee /logs/agent/quillith.stderr.log >&2)"
            ),
            cwd=workdir,
            env=run_env,
        )

    @override
    def populate_context_post_run(
        self,
        context: AgentContext,
    ) -> None:
        """首版不生成 trajectory 或额外 AgentContext 指标。"""
        pass
```

双引号中的变量展开不会再次把变量内容解释成 Shell 语法，因此空格、换行、引号、`$`、`&`、`|`、`;` 均作为同一个 instruction 参数进入 Java。不要调用 `render_instruction(...)`，也不要增加 prompt template。

`exec_as_agent(...)` 已通过 Harbor 的 `set -o pipefail` 保留 Java 非零退出。不要捕获并转换该异常；让 Harbor 得到真实失败。

日志固定保存为：

```text
/logs/agent/quillith.stdout.log
/logs/agent/quillith.stderr.log
```

## 8. 新增薄宿主机 Launcher

新增：

```text
demo/manual-agent/quillith-harbor-run.ps1
```

脚本只做一次构建和一次 `harbor run`。完整结构按以下代码实现：

```powershell
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $HarborArguments
)

$ErrorActionPreference = "Stop"

# 1. 每次 launcher 调用只在宿主机执行一次 Maven package。
$projectDirectory = $PSScriptRoot
$pomPath = Join-Path $projectDirectory "pom.xml"
& mvn -q -f $pomPath package
if ($LASTEXITCODE -ne 0) {
    throw "Quillith Maven build 失败，Harbor Job 未启动。"
}

# 2. 只接受本次构建约定的固定 JAR，不搜索或回退到旧制品。
$jarPath = Join-Path $projectDirectory "target\quillith.jar"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "本次构建未生成 Quillith JAR：$jarPath"
}
$jarPath = (Resolve-Path -LiteralPath $jarPath).Path

# 3. 只为当前 harbor 子进程暴露项目内 Adapter import path。
$previousPythonPath = $env:PYTHONPATH
$pathSeparator = [System.IO.Path]::PathSeparator
if ([string]::IsNullOrWhiteSpace($previousPythonPath)) {
    $env:PYTHONPATH = $projectDirectory
} else {
    $env:PYTHONPATH =
            "$projectDirectory$pathSeparator$previousPythonPath"
}

# 4. 注入固定 Adapter 和本次唯一 JAR，其余参数原样转交 harbor run。
$harborExitCode = 1
try {
    & harbor run `
        --agent "harbor_adapter:QuillithHarborAgent" `
        --agent-kwarg "jar_path=$jarPath" `
        @HarborArguments
    $harborExitCode = $LASTEXITCODE
} finally {
    $env:PYTHONPATH = $previousPythonPath
}

exit $harborExitCode
```

约束：

- 不增加缓存命中判断；每次脚本调用都执行一次 Maven package。
- Maven 返回非零时不调用 Harbor。
- 不扫描 `target` 中的其他 JAR，也不使用上次构建产物兜底。
- 不解析或重写 Harbor 参数，只在前面注入固定 Agent 和 `jar_path`。
- 不把模型名、API Key、Base URL 或 GitHub Token 写进脚本；继续由 Harbor 的 `--model` 和 `--agent-env` 提供。

## 9. 补齐选定的 Harbor Linux smoke 环境

修改：

```text
D:/learn_claudecode/harbor-lab/hello-world/environment/Dockerfile
```

保留现有 Alpine、Codex 和调试命令，只在 `apk add` 中增加：

```text
git
openjdk21-jre
```

修改后的安装段为：

```dockerfile
# 同时准备 Codex 调试依赖和 Quillith 所需的 Java 21 Runtime、bash、git。
RUN apk add --no-cache bash curl git openjdk21-jre nodejs npm ripgrep \
    && npm install --global @openai/codex
```

不得增加 Maven、完整 JDK 或 Quillith 源码。该 Dockerfile 只是当前项目选定的 smoke 环境，不把 Adapter 宣称为兼容任意 Linux 镜像。

## 10. 执行顺序

按依赖顺序执行：

1. 修改 `pom.xml`，生成固定名称的 self-contained JAR。
2. 修改 `AgentRuntime`，接入模型、API Key、Base URL 和 Bash 环境变量。
3. 修改 `BashTool` 的平台专用注释和错误文案。
4. 新增 `harbor_adapter.py`，完成 JAR 上传、配置映射、Exec 启动、日志和退出状态传递。
5. 新增 `quillith-harbor-run.ps1`，建立宿主机 build-once 生命周期。
6. 给 `hello-world` smoke 环境增加 Java Runtime 和 git。
7. 执行构建与 Harbor smoke 验证。

不要先写 Adapter 再让它临时负责构建；JAR 和运行配置必须先成为稳定输入。

## 11. 验证方式

本轮不新增测试代码，只执行以下检查。

### 11.1 JAR 构建检查

在 `demo/manual-agent` 执行：

```powershell
mvn -q package
Get-Item .\target\quillith.jar
jar tf .\target\quillith.jar
```

确认：

- `target/quillith.jar` 存在；
- JAR 包含 `ManualAgentApplication.class` 和运行依赖；
- Manifest 的 Main-Class 为 `dev.learn.agent.manual.ManualAgentApplication`。

### 11.2 Harbor smoke run

从任意目录调用 launcher，Harbor 参数继续按原方式传入。示例：

```powershell
& 'D:\learn_claudecode\learn-claude-code\demo\manual-agent\quillith-harbor-run.ps1' `
    --path 'D:\learn_claudecode\harbor-lab\hello-world' `
    --model 'anthropic/qwen3.5-flash' `
    --agent-env "ANTHROPIC_API_KEY=$env:DASHSCOPE_API_KEY" `
    --agent-env 'ANTHROPIC_BASE_URL=https://dashscope.aliyuncs.com/apps/anthropic' `
    --agent-env "GITHUB_PERSONAL_ACCESS_TOKEN=$env:GITHUB_PERSONAL_ACCESS_TOKEN" `
    --n-concurrent 2
```

确认：

```text
1. launcher 输出中 Maven 只运行一次。
2. 两个并行 Trial 都进入同一个 Quillith Adapter。
3. Trial setup 只上传 JAR，没有 Maven/JDK/源码安装。
4. Quillith 在 Trial 工作目录创建 hello.txt，verifier 通过。
5. 每个 Trial 均保存 quillith.stdout.log 和 quillith.stderr.log。
6. Quillith 非零退出时 Harbor Trial 失败。
```

### 11.3 边界 smoke

只做手工命令检查，不增加测试脚本：

- 使用 `--model openai/...` 时，Agent 创建阶段明确报告不支持的协议。
- 不传 `GITHUB_PERSONAL_ACCESS_TOKEN` 时，Agent 创建阶段明确失败。
- 显式传入非法 `QUILLITH_BASH_EXECUTABLE` 时，Trial setup 明确失败。
- Windows 本机不设置 `QUILLITH_BASH_EXECUTABLE` 时，仍使用当前 `C:\Windows\System32\bash.exe`。

最后执行：

```powershell
git -C D:\learn_claudecode\learn-claude-code diff --check
git -C D:\learn_claudecode\learn-claude-code status --short
```

`harbor-lab` 不属于同一个 Git 工作树时，单独检查其 Dockerfile diff。

## 12. 验收标准

- [ ] launcher 每次调用只执行一次宿主机 Maven package。
- [ ] Maven 失败时不会启动 Harbor，也不会回退旧 JAR。
- [ ] `target/quillith.jar` 可以直接通过 `java -jar` 启动。
- [ ] 同一 Job 的所有 Trial 上传并运行同一路径指向的构建产物。
- [ ] Adapter `install()` 不安装 Maven、JDK 或 Quillith 源码。
- [ ] Trial 环境只需 Java Runtime、bash 和 git。
- [ ] Adapter 不解释、增删或改写 instruction。
- [ ] Quillith 工作目录就是当前 Trial 工作目录。
- [ ] Harbor 显式 model、API Key、Base URL 正确映射，缺失字段使用 Quillith 默认值。
- [ ] 非 Anthropic-compatible provider 在启动阶段快速失败。
- [ ] 缺少 GitHub Token 保持启动失败。
- [ ] Bash executable 支持显式配置，并保留 Windows 本机默认路径。
- [ ] stdout、stderr 和非零退出状态均正确反馈 Harbor。
- [ ] 未新增自动化测试、ATIF、Multi-step、缓存或平台层代码。
