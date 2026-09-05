# Quillith Harbor 多轮对话执行文档

> 历史版本：原执行方案已收缩，当前版本见 [Quillith Harbor 最小多轮对话执行文档](./Quillith%20Harbor多轮对话执行_2026-09-04_22-16.md)。

## 1. 执行目标

依据以下文档实现，不重新设计方案：

- [需求文档](./Quillith%20Harbor多轮对话需求_2026-09-04_12-23.md)
- [技术评审](./Quillith%20Harbor多轮对话技术评审_2026-09-04_16-26.md)

目标关系固定为：

```text
一个启用 resume_trajectory 的 Harbor Trial
└── 一个 Quillith 进程
    └── 一个 AgentRuntime
        └── 一个 ManualAgent
            ├── submit(step 1 instruction)
            ├── submit(step 2 instruction)
            └── submit(step N instruction)
```

本次新增 Harbor 机器输入边界，不新增第二套 Agent、history、Tool Loop 或 Session 模型。具体 Multi-step 测评用例不在本执行范围内。

## 2. 固定决策

1. Java 与 Adapter 使用两个 POSIX named pipe 顺序通信。
2. instruction 以 UTF-8 编码后再 Base64，保证多行文本作为一个 Turn 原样传递。
3. 协议只包含 `TURN`、`CLOSE`、`OK`、`ERROR`、`CLOSED`。
4. 首个普通 `run()` 启动新会话；`_resume == True` 时复用原会话。
5. 未启用 resume 的下一次普通 `run()` 先正常关闭旧会话，再创建新会话。
6. resume 时会话丢失、进程退出或 step 切换 `environment.default_user`，均快速失败。
7. 不重试、不自动恢复、不降级为新的单轮进程。
8. 不修改 Harbor 框架源码，不增加第三方依赖。

## 3. 执行顺序

1. 修改 `ApplicationOptions`。
2. 新增 `HarborRunner`。
3. 修改 `ManualAgentApplication`。
4. 修改 `harbor_adapter.py`。
5. 增加 Java 与 Python 测试。
6. 运行完整 Java 测试、Python Adapter 测试和 JAR 构建。

## 4. 修改 ApplicationOptions

文件：

`demo/manual-agent/src/main/java/dev/learn/agent/manual/cli/ApplicationOptions.java`

### 4.1 增加输入类型

```java
public enum Mode {
    INTERACTIVE,
    EXEC,
    HARBOR
}
```

`HARBOR` 只是机器输入入口，不代表新的 Agent 会话模型。

### 4.2 解析 harbor 子命令

`parse()` 按以下顺序判断：

```java
if (args.length > 0 && "exec".equals(args[0])) {
    return parseExec(args);
}

if (args.length > 0 && "harbor".equals(args[0])) {
    return parseHarbor(args);
}

return parseInteractive(args);
```

新增 `parseHarbor()`：

```java
private static ApplicationOptions parseHarbor(
        String[] args
) {
    boolean memoryEnabled = true;

    // 1. harbor 之后只允许已有的 Memory 开关。
    for (int index = 1;
         index < args.length;
         index++) {
        memoryEnabled =
                parseMemoryArgument(
                        args[index]
                );
    }

    // instruction 来自 Harbor 通道，不从 argv 读取。
    return new ApplicationOptions(
            Mode.HARBOR,
            memoryEnabled,
            ""
    );
}
```

不要给 `ApplicationOptions` 增加 pipe、PID、ready 等字段。协议使用容器内固定路径。

## 5. 新增 HarborRunner

文件：

`demo/manual-agent/src/main/java/dev/learn/agent/manual/cli/HarborRunner.java`

类保持 `final`。不要新增通用 Channel 接口、抽象父类、RPC 框架或消息总线。

### 5.1 固定路径

```java
static final Path SESSION_DIRECTORY =
        Path.of("/tmp/quillith-harbor-session");
static final Path REQUEST_PIPE =
        SESSION_DIRECTORY.resolve("request.pipe");
static final Path RESPONSE_PIPE =
        SESSION_DIRECTORY.resolve("response.pipe");
static final Path READY_FILE =
        SESSION_DIRECTORY.resolve("ready");
```

`process.pid` 只由 Python Adapter 管理。

### 5.2 协议

请求行：

```text
TURN <base64-encoded-utf8-instruction>
CLOSE
```

响应行：

```text
OK
ERROR
CLOSED
```

一条 `TURN` 必须只触发一次 `ManualAgent.submit()`。`CLOSE` 不能进入 Agent history。未知类型、非法 Base64、管道 EOF 都直接失败。

### 5.3 Runner 主循环

`HarborRunner` 内部持有一个现有 `ExecRunner`。它不创建 Runtime，也不保存 history。

接近以下结构实现：

```java
public int run(
        ManualAgent manualAgent
) throws IOException {
    Objects.requireNonNull(
            manualAgent,
            "ManualAgent 不能为空"
    );

    // 1. Runtime 已创建完成后才通知 Adapter。
    Files.createFile(
            READY_FILE
    );

    int turnNumber = 0;
    while (true) {
        String request =
                readRequest();

        // 2. CLOSE 结束循环，不提交给模型。
        if ("CLOSE".equals(
                request
        )) {
            writeResponse(
                    "CLOSED"
            );
            return 0;
        }

        try {
            // 3. 一条请求还原为一条完整 instruction。
            String instruction =
                    decodeTurn(
                            request
                    );
            turnNumber++;
            System.out.println(
                    "[Harbor Turn "
                            + turnNumber
                            + " Start]"
            );

            // 4. 所有 Turn 复用同一个 ManualAgent。
            int exitCode =
                    execRunner.run(
                            manualAgent,
                            instruction
                    );
            if (exitCode != 0) {
                System.err.println(
                        "[Harbor Turn "
                                + turnNumber
                                + " Failure]"
                );
                writeResponse(
                        "ERROR"
                );
                return exitCode;
            }

            // 5. submit 完整返回后才允许 Harbor 开始 verifier。
            System.out.println(
                    "[Harbor Turn "
                            + turnNumber
                            + " Success]"
            );
            writeResponse(
                    "OK"
            );
        } catch (IOException | RuntimeException exception) {
            signalErrorWithoutReplacing(
                    exception
            );
            throw exception;
        }
    }
}
```

所有新方法都必须写职责、输入、输出或异常的规范注释；方法内部按项目要求添加有序步骤注释，并把不能从代码直接看出的 why 单独写成注释。

### 5.4 每轮重新打开 named pipe

新增：

```java
private static String readRequest()
        throws IOException
private static void writeResponse(
        String response
) throws IOException
static String decodeTurn(
        String request
)
private static void signalErrorWithoutReplacing(
        Exception originalException
)
```

`readRequest()` 每轮重新打开请求 pipe，读取一行后关闭：

```java
try (BufferedReader reader =
             Files.newBufferedReader(
                     REQUEST_PIPE,
                     StandardCharsets.UTF_8
             )) {
    String request =
            reader.readLine();
    if (request == null) {
        throw new IOException(
                "Harbor 请求管道未收到完整请求"
        );
    }
    return request;
}
```

不能在整个 Session 中永久保留一个 `BufferedReader`，因为每轮 Adapter writer 都会关闭。

`writeResponse()` 每轮重新打开响应 pipe：

```java
try (BufferedWriter writer =
             Files.newBufferedWriter(
                     RESPONSE_PIPE,
                     StandardCharsets.UTF_8,
                     StandardOpenOption.WRITE
             )) {
    writer.write(
            response
    );
    writer.newLine();
}
```

`decodeTurn()`：

```java
if (!request.startsWith(
        "TURN "
)) {
    throw new IllegalArgumentException(
            "未知 Harbor 请求类型"
    );
}

byte[] instructionBytes =
        Base64.getDecoder().decode(
                request.substring(
                        "TURN ".length()
                )
        );
return new String(
        instructionBytes,
        StandardCharsets.UTF_8
);
```

不要在异常信息中包含完整请求或 Base64 正文。

异常已发生但尚未响应时，尝试写 `ERROR`。响应失败作为 suppressed exception 附加到原异常，然后继续抛出原异常：

```java
try {
    writeResponse(
            "ERROR"
    );
} catch (IOException responseException) {
    originalException.addSuppressed(
            responseException
    );
}
```

不要在 `HarborRunner` 再打印完整堆栈。原异常继续到 Java 进程最终边界，由未捕获异常输出保留一次堆栈。

## 6. 修改 ManualAgentApplication

文件：

`demo/manual-agent/src/main/java/dev/learn/agent/manual/ManualAgentApplication.java`

### 6.1 审批模式

保留现有判断：

```java
options.mode() == ApplicationOptions.Mode.INTERACTIVE
        ? ToolApprovalMode.ASK
        : ToolApprovalMode.BYPASS
```

因此人工 Interactive 使用 ASK，Exec 和 Harbor 使用 BYPASS。只更新附近已经不准确的“两种模式”注释。

### 6.2 增加 switch 分支

```java
case HARBOR ->
        runHarbor(
                agentState
        );
```

新增：

```java
private static int runHarbor(
        AgentState agentState
) throws IOException {
    // 1. 一个 Harbor 进程只创建一份 Runtime。
    try (AgentRuntime runtime =
                 AgentRuntime.create(
                         agentState,
                         null
                 )) {
        // 所有 Turn 都提交给 Runtime 中同一个 ManualAgent。
        return new HarborRunner().run(
                runtime.manualAgent()
        );
    }
}
```

`runHarbor()` 不创建 Scanner，不调用人工 `runInteractive()`，不写人工 transcript。原因是人工入口按行读取且使用 ASK；复用的是同一 Runtime/ManualAgent 会话语义。

不要修改：

- `ManualAgent.java`
- `AgentLoop.java`
- `AgentRuntime.java`
- `InteractiveRunner.java`
- `ExecRunner.java`

## 7. 修改 Harbor Adapter

文件：

`demo/manual-agent/harbor_adapter.py`

### 7.1 import、能力和路径

增加：

```python
import base64
```

修改：

```python
SUPPORTS_RESUME = True
```

增加固定路径：

```python
_SESSION_DIRECTORY = "/tmp/quillith-harbor-session"
_REQUEST_PIPE = f"{_SESSION_DIRECTORY}/request.pipe"
_RESPONSE_PIPE = f"{_SESSION_DIRECTORY}/response.pipe"
_READY_FILE = f"{_SESSION_DIRECTORY}/ready"
_PID_FILE = f"{_SESSION_DIRECTORY}/process.pid"
```

在 `__init__()` 末尾增加：

```python
self._session_started = False
self._session_user: str | int | None = None
```

必须保留 `_session_started`，因为 `None` 可能是合法的 Harbor 默认用户。

### 7.2 安装依赖检查

在已有 Java、Git、Bash 检查中增加：

```bash
command -v mkfifo >/dev/null
command -v nohup >/dev/null
command -v sleep >/dev/null
```

用 `&&` 连接。缺少命令直接失败，不安装依赖，不切换协议。

### 7.3 run 状态机

删除当前每轮执行：

```text
java -jar /installed-agent/quillith.jar exec ...
```

新 `run()` 顺序固定为：

```python
current_user = environment.default_user

if not self._resume:
    if self._session_started:
        await self._close_session(
            environment
        )

    await self._reset_session_directory(
        environment
    )
    workdir = await self._resolve_workdir(
        environment
    )
    await self._start_session(
        environment,
        workdir,
        self._build_runtime_env(),
    )
    self._session_started = True
    self._session_user = current_user
else:
    if not self._session_started:
        raise RuntimeError(
            "无法恢复 Quillith Session：当前 Trial 没有活动会话"
        )
    if current_user != self._session_user:
        raise RuntimeError(
            "无法恢复 Quillith Session："
            "后续 step 的 agent.user 与首轮不同"
        )
    await self._assert_session_alive(
        environment
    )

await self._submit_turn(
    environment,
    instruction,
)
```

`context` 继续不写额外指标。

### 7.4 提取现有逻辑

增加 `_build_runtime_env()`，把当前 `run()` 内模型、Base URL、API Key、GitHub Token、Bash executable 的映射原样移入。删除 `QUILLITH_INSTRUCTION`；instruction 改由 Turn 通道传递。

增加 `_resolve_workdir()`，原样保留当前 `pwd`、return code 和空值检查，不回退 `/app` 或 `/`。

### 7.5 重置固定 Session 目录

`_reset_session_directory()` 以 root 只处理：

```text
/tmp/quillith-harbor-session
```

Shell：

```bash
set -e

if [ -e /tmp/quillith-harbor-session ]; then
    rm -rf -- /tmp/quillith-harbor-session
fi
```

这是 Trial 容器中的兼容层专属临时目录，只能包含两个 pipe、ready 和 PID。禁止删除 `/tmp`、workspace、`/logs/agent` 或使用通配符。

### 7.6 启动 Session

`_start_session(environment, workdir, runtime_env)` 通过 `exec_as_agent()` 执行，设置 `cwd=workdir` 和 `env=runtime_env`。

Shell步骤：

```bash
set -e

mkdir /tmp/quillith-harbor-session
mkfifo /tmp/quillith-harbor-session/request.pipe
mkfifo /tmp/quillith-harbor-session/response.pipe

nohup java -jar /installed-agent/quillith.jar harbor \
    >> /logs/agent/quillith.stdout.log \
    2>> /logs/agent/quillith.stderr.log \
    < /dev/null &

quillith_pid=$!
printf '%s\n' "$quillith_pid" \
    > /tmp/quillith-harbor-session/process.pid

while [ ! -f /tmp/quillith-harbor-session/ready ]; do
    if ! kill -0 "$quillith_pid" 2>/dev/null; then
        if wait "$quillith_pid"; then
            process_exit_code=0
        else
            process_exit_code=$?
        fi
        echo "Quillith Harbor Session 启动失败，exitCode=$process_exit_code" >&2
        exit 1
    fi
    sleep 0.1
done
```

后台 Java 的 stdin、stdout、stderr 必须全部脱离本轮 Docker exec；否则启动命令可能一直不返回。

启动、提交和关闭脚本必须自行启用 `set -e`。Harbor 的 `_exec()` 只统一启用 `pipefail`，不能依赖它在普通命令失败后自动终止脚本。

Java 只能在 Runtime 创建完成后生成 ready。等待 ready 使用 Harbor 外层 step timeout，不新增重试或第二个 timeout。

### 7.7 检查 Session

`_assert_session_alive()` 通过一个 `exec_as_agent()` 检查：

```bash
test -p /tmp/quillith-harbor-session/request.pipe &&
test -p /tmp/quillith-harbor-session/response.pipe &&
test -f /tmp/quillith-harbor-session/ready &&
test -f /tmp/quillith-harbor-session/process.pid &&
kill -0 "$(cat /tmp/quillith-harbor-session/process.pid)"
```

失败直接向上抛，不重建会话。

### 7.8 提交 Turn

`_submit_turn()` 先在 Python 中编码：

```python
encoded_instruction = base64.b64encode(
    instruction.encode("utf-8")
).decode("ascii")
request = f"TURN {encoded_instruction}"
```

把 request 放入 `QUILLITH_HARBOR_REQUEST` 环境变量，不拼入 shell command，不输出到日志。

Shell：

```bash
set -e

printf '%s\n' "$QUILLITH_HARBOR_REQUEST" \
    > /tmp/quillith-harbor-session/request.pipe
IFS= read -r quillith_status \
    < /tmp/quillith-harbor-session/response.pipe

case "$quillith_status" in
    OK)
        exit 0
        ;;
    ERROR)
        echo "Quillith Harbor Turn 执行失败" >&2
        exit 1
        ;;
    *)
        echo "Quillith Harbor 返回未知状态：$quillith_status" >&2
        exit 1
        ;;
esac
```

收到 `ERROR`、未知状态或命令失败都交给 `exec_as_agent()` 原有错误处理，不 catch 后继续，不重试。

### 7.9 正常关闭旧 Session

`_close_session()` 只在后续普通 `run()` 需要独立会话时调用。

因为外层已切换到新 step 用户，关闭命令必须在旧用户上下文执行：

```python
with environment.with_default_user(
    self._session_user
):
    await self.exec_as_agent(
        environment,
        command=close_command,
    )
```

关闭协议：

```bash
set -e

printf '%s\n' 'CLOSE' \
    > /tmp/quillith-harbor-session/request.pipe
IFS= read -r quillith_status \
    < /tmp/quillith-harbor-session/response.pipe

if [ "$quillith_status" != "CLOSED" ]; then
    echo "Quillith Harbor Session 关闭状态非法：$quillith_status" >&2
    exit 1
fi

quillith_pid="$(cat /tmp/quillith-harbor-session/process.pid)"
while kill -0 "$quillith_pid" 2>/dev/null; do
    sleep 0.1
done
```

必须等待进程退出，因为 `HarborRunner` 返回后 `ManualAgentApplication` 才会通过 try-with-resources 关闭 `AgentRuntime`。

成功后再执行：

```python
self._session_started = False
self._session_user = None
```

关闭失败时不强杀、不删除通道、不创建新 Session。

### 7.10 Trial 结束

不要在 `populate_context_post_run()` 猜测最后一个 step。最后一轮返回后 Java 继续等待，Harbor 完成 verifier 和归档后销毁 Trial 环境，进程随容器结束。

## 8. Java 测试

### 8.1 修改 ApplicationOptionsTest

文件：

`demo/manual-agent/src/test/java/dev/learn/agent/manual/cli/ApplicationOptionsTest.java`

新增：

1. `parsesHarborMode()`：`{"harbor"}` 得到 `Mode.HARBOR`、Memory 开启、instruction 为空；
2. `harborSupportsExistingMemoryOption()`：`{"harbor", "--memory=off"}` 关闭 Memory；
3. `harborRejectsInstructionArgument()`：`{"harbor", "unexpected"}` 抛出 `IllegalArgumentException`。

保留所有现有测试。

### 8.2 新增 HarborRunnerTest

文件：

`demo/manual-agent/src/test/java/dev/learn/agent/manual/cli/HarborRunnerTest.java`

覆盖纯协议逻辑：

1. 中文、英文、空格和多行 instruction 经 Base64 后由 `decodeTurn()` 原样恢复；
2. 非 `TURN ` 请求抛出 `IllegalArgumentException`；
3. 非法 Base64 抛出 `IllegalArgumentException`。

不要为了 Windows 测试模拟 FIFO 而增加生产 Channel 抽象。现有 `ManualAgentTest.keepsConversationHistoryAcrossSubmissions()` 已覆盖同一个 `ManualAgent` 连续两次 submit 的 history 延续，不重复造模型 Fixture。

## 9. Python Adapter 测试

新增：

`demo/manual-agent/src/test/python/test_harbor_adapter.py`

使用标准库 `unittest`、`unittest.mock` 和 `IsolatedAsyncioTestCase`，不新增 pytest。

通过临时空 JAR 构造 Adapter，用 AsyncMock 替换远端命令或私有生命周期方法。覆盖：

1. 第一次普通 `run()` 重置目录、启动 Session、提交一次 instruction 并保存 user；
2. `_resume=True` 时检查并复用 Session，不重新启动；
3. 第二次普通 `run()` 先关闭旧 Session，再启动新 Session；
4. 无活动 Session 时 resume 快速失败；
5. resume step 的 user 不同，提交前快速失败；
6. 多行 Unicode instruction 编码后是单行 `TURN`，解码与原文一致；
7. `ERROR` 和未知响应向上抛，不重试；
8. 关闭时使用旧 Session user，收到 `CLOSED` 后才清理本地状态。

不启动真实 Docker、Java 或模型，不断言 Harbor 自己的私有实现。

## 10. 不允许顺带修改

不修改：

- `ManualAgent.java`
- `AgentLoop.java`
- `AgentRuntime.java`
- `InteractiveRunner.java`
- `ExecRunner.java`
- Tool、Hook、Memory、Todo、Task 和后台调度器
- `pom.xml`
- `quillith-harbor-run.ps1`
- Harbor 安装目录源码
- 任何测评用例

若执行中发现必须修改以上内容，停止并说明执行文档中的哪项事实不成立。

## 11. 验证命令

Java 全量测试：

```powershell
mvn -f demo/manual-agent/pom.xml test
```

Python Adapter 测试：

```powershell
python -m unittest discover `
    -s demo/manual-agent/src/test/python `
    -p "test_*.py"
```

JAR 构建：

```powershell
mvn -q -f demo/manual-agent/pom.xml -DskipTests package
```

确认生成：

```text
demo/manual-agent/target/quillith.jar
```

本次不运行真实 Multi-step Harbor 用例；用例、环境 Dockerfile 和逐轮 verifier 留给后续需求。

## 12. 完成标准

- `harbor` 子命令可解析，使用 BYPASS；
- Java 入口只创建一次 Runtime；
- 一条协议请求只调用一次 `submit()`；
- 多行 Unicode instruction 原样传递；
- 首轮 `run()` 启动，后续 `resume()` 复用；
- 未启用 resume 的连续 `run()` 不复用；
- Session 丢失和 user 变化快速失败；
- Java 异常能发送 `ERROR` 并保留原始异常；
- 日志只有 Turn 编号和状态，不含 instruction 或凭据；
- Java、Python 测试全部通过；
- JAR 构建成功；
- 没有新增依赖、测评用例或无关修改。

## 13. 最终自检

1. 是否误建了第二套 Agent 或 history；
2. 是否直接复用了 `ManualAgent.submit()` 和 `ExecRunner.run()`；
3. 是否为了测试增加无业务价值的抽象；
4. 是否记录了 instruction、Base64 或敏感环境变量；
5. 是否在 resume 失败后偷偷新建 Session；
6. 是否加入了重试、fallback 或崩溃恢复；
7. 是否只删除固定的 `/tmp/quillith-harbor-session`；
8. 是否保留人工 Interactive 和 Exec 的原行为；
9. 是否修改了需求外代码或 Harbor 源码；
10. 是否还有明显更短且同样正确的实现。
