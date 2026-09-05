# Quillith Harbor 最小多轮对话执行文档

## 1. 执行目标

依据：

- [最小多轮需求](./Quillith%20Harbor多轮对话需求_2026-09-04_22-16.md)
- [最小多轮技术评审](./Quillith%20Harbor多轮对话技术评审_2026-09-04_22-16.md)

只实现正常链路：

```text
run
→ 启动一个 Quillith
→ 创建一个 AgentRuntime 和 ManualAgent
→ submit(instruction 1)
→ OK
→ Harbor verifier
→ resume
→ 原 ManualAgent.submit(instruction 2)
→ OK
→ Harbor verifier
```

不实现主动关闭、进程监控、恢复、重试或完整状态机。

## 2. 修改范围

修改：

- `src/main/java/dev/learn/agent/manual/cli/ApplicationOptions.java`
- `src/main/java/dev/learn/agent/manual/ManualAgentApplication.java`
- 新增 `src/main/java/dev/learn/agent/manual/cli/HarborRunner.java`
- `harbor_adapter.py`
- 对应 Java 和 Python 测试

不修改：

- `ManualAgent`
- `AgentLoop`
- `AgentRuntime`
- `InteractiveRunner`
- `ExecRunner`
- Tool、Hook、Memory、Todo、Task、后台调度器
- `pom.xml`
- Harbor 框架源码
- 测评任务与 Dockerfile

以下路径均相对于 `D:\learn_claudecode\learn-claude-code\demo\manual-agent`。

## 3. ApplicationOptions

### 3.1 增加 HARBOR

在 `Mode` 增加：

```java
HARBOR
```

该值只表示机器输入来源，不表示新的 Agent 会话模型。

### 3.2 解析 harbor 子命令

`parse()` 在 `exec` 判断后增加：

```java
if (args.length > 0
        && "harbor".equals(
        args[0]
)) {
    return parseHarbor(
            args
    );
}
```

新增 `parseHarbor()`：

```java
private static ApplicationOptions parseHarbor(
        String[] args
) {
    boolean memoryEnabled = true;

    // 1. Harbor 入口只复用已有 Memory 开关。
    for (int index = 1;
         index < args.length;
         index++) {
        memoryEnabled =
                parseMemoryArgument(
                        args[index]
                );
    }

    // instruction 来自 FIFO，不进入命令行参数。
    return new ApplicationOptions(
            Mode.HARBOR,
            memoryEnabled,
            ""
    );
}
```

不增加 pipe 路径配置。位置参数和未知参数沿用现有快速失败。

## 4. HarborRunner

### 4.1 新增文件

`src/main/java/dev/learn/agent/manual/cli/HarborRunner.java`

保持一个简单 `final` 类，不创建 Channel 接口或抽象父类。

### 4.2 固定路径和协议

```java
private static final Path REQUEST_PIPE =
        Path.of(
                "/tmp/quillith-harbor-session/request.pipe"
        );
private static final Path RESPONSE_PIPE =
        Path.of(
                "/tmp/quillith-harbor-session/response.pipe"
        );

private static final String TURN_PREFIX =
        "TURN ";
private static final String OK =
        "OK";
```

请求：

```text
TURN <base64>
```

响应：

```text
OK
```

Base64 解码结果使用 UTF-8 构造 instruction。

### 4.3 主循环

`HarborRunner.run()` 接收 `ManualAgent`，不接收或创建 Runtime：

```java
public void run(
        ManualAgent manualAgent
) throws IOException {
    Objects.requireNonNull(
            manualAgent,
            "ManualAgent 不能为空"
    );

    int turnNumber = 0;

    // 1. 一个进程持续等待 Harbor 的顺序 Turn。
    while (true) {
        String request =
                readRequest();
        String instruction =
                decodeInstruction(
                        request
                );
        turnNumber++;

        // PID 只用于验证三轮运行在同一个进程，不建立 PID 管理机制。
        long processId =
                ProcessHandle.current()
                        .pid();
        System.out.println(
                "[Harbor Turn "
                        + turnNumber
                        + " Start, pid="
                        + processId
                        + "]"
        );

        // 2. 一条 Harbor instruction 只直接提交一次。
        manualAgent.submit(
                instruction
        );

        // 3. submit 正常返回后才通知 Harbor 进入 verifier。
        System.out.println(
                "[Harbor Turn "
                        + turnNumber
                        + " Success, pid="
                        + processId
                        + "]"
        );
        writeSuccess();
    }
}
```

`HarborRunner` 不捕获 `ManualAgent.submit()` 的异常。异常沿进程边界抛出，Java 退出；第一版接受 Adapter 最终由 Harbor timeout 结束等待。

### 4.4 FIFO 读写

每轮重新打开 request FIFO，读一行后关闭：

```java
private static String readRequest()
        throws IOException {
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
}
```

必须重新打开，因为 Adapter 每轮写完都会关闭 writer。

解码：

```java
static String decodeInstruction(
        String request
) {
    if (!request.startsWith(
            TURN_PREFIX
    )) {
        throw new IllegalArgumentException(
                "未知 Harbor 请求类型"
        );
    }

    byte[] instructionBytes =
            Base64.getDecoder()
                    .decode(
                            request.substring(
                                    TURN_PREFIX.length()
                            )
                    );
    return new String(
            instructionBytes,
            StandardCharsets.UTF_8
    );
}
```

不要把 request 或 Base64 正文放进错误信息和日志。

写完成信号：

```java
private static void writeSuccess()
        throws IOException {
    try (BufferedWriter writer =
                 Files.newBufferedWriter(
                         RESPONSE_PIPE,
                         StandardCharsets.UTF_8,
                         StandardOpenOption.WRITE
                 )) {
        writer.write(
                OK
        );
        writer.newLine();
    }
}
```

所有方法按项目规范写方法注释、有序步骤注释和单独的 why 注释。

## 5. ManualAgentApplication

### 5.1 保持权限语义

保留：

```java
options.mode() == ApplicationOptions.Mode.INTERACTIVE
        ? ToolApprovalMode.ASK
        : ToolApprovalMode.BYPASS
```

所以 Harbor 自动使用 BYPASS。只更新附近“两种模式”之类不再准确的注释。

### 5.2 增加 HARBOR 分支

switch 增加：

```java
case HARBOR -> {
    runHarbor(
            agentState
    );
    yield 0;
}
```

新增：

```java
private static void runHarbor(
        AgentState agentState
) throws IOException {
    // 1. 一个 Harbor Trial 进程只创建一次 Runtime。
    try (AgentRuntime runtime =
                 AgentRuntime.create(
                         agentState,
                         null
                 )) {
        // 所有 Turn 复用 Runtime 中同一个 ManualAgent。
        new HarborRunner().run(
                runtime.manualAgent()
        );
    }
}
```

不要调用 `runInteractive()`：人工入口按行读取并使用 ASK。不要调用 `ExecRunner`：Harbor 正常链路直接调用 `ManualAgent.submit()` 更明确，命令行 Exec 仍保持原实现。

## 6. harbor_adapter.py

### 6.1 最小字段

增加标准库：

```python
import base64
```

修改：

```python
SUPPORTS_RESUME = True
```

增加：

```python
_SESSION_DIRECTORY = "/tmp/quillith-harbor-session"
_REQUEST_PIPE = f"{_SESSION_DIRECTORY}/request.pipe"
_RESPONSE_PIPE = f"{_SESSION_DIRECTORY}/response.pipe"
```

`__init__()` 只增加：

```python
self._session_started = False
```

不增加 PID、READY、CLOSE、BROKEN、user 或退出状态字段。

### 6.2 安装检查

在现有 Java、Git、Bash 检查中增加：

```bash
command -v mkfifo >/dev/null
command -v nohup >/dev/null
```

必要性：FIFO 负责跨 `environment.exec()` 传递 Turn；`nohup` 保证 Java 在首次启动命令返回后继续存在。

缺少时直接失败，不安装、不 fallback。

### 6.3 run 状态机

`run()` 只保留一个布尔分支：

```python
if not self._session_started:
    if self._resume:
        raise RuntimeError(
            "无法恢复 Quillith Session："
            "当前 Trial 尚未启动会话"
        )

    await self._start_session(
        environment
    )
    self._session_started = True
elif not self._resume:
    raise RuntimeError(
        "Quillith 最小多轮版本不支持"
        "同一 Trial 中的第二次非 resume run"
    )

await self._submit_turn(
    environment,
    instruction,
)
```

含义：

- 第一次普通 `run()` 启动；
- 后续 `resume()` 复用；
- 首次就是 resume 或第二次普通 run 都失败；
- 不检查进程，不恢复，不关闭。

### 6.4 `_start_session()`

把当前模型连接环境映射保留在启动方法中：

```python
runtime_env = {
    "QUILLITH_BASH_EXECUTABLE": self._bash_executable,
    "GITHUB_PERSONAL_ACCESS_TOKEN": self._github_token,
}
```

继续按现有规则补充 model、API Key、Base URL。

继续使用现有 `pwd` 逻辑取得 workdir；失败时不回退。

启动命令执行：

```bash
set -e
mkdir /tmp/quillith-harbor-session
mkfifo /tmp/quillith-harbor-session/request.pipe
mkfifo /tmp/quillith-harbor-session/response.pipe

nohup java -jar /installed-agent/quillith.jar harbor \
    </dev/null \
    >>/logs/agent/quillith.stdout.log \
    2>>/logs/agent/quillith.stderr.log &
```

使用：

```python
await self.exec_as_agent(
    environment,
    command=start_command,
    cwd=workdir,
    env=runtime_env,
)
```

不保存 PID，不等待 READY。

为什么不需要 READY：紧接着的 `_submit_turn()` 打开 request FIFO 写入时会自然阻塞，直到 Java 完成 Runtime 初始化并打开 FIFO 读取。这个 FIFO 握手已经满足正常启动同步。

已知限制：Java 如果在打开 FIFO 前退出，Adapter 会等到 Harbor 外层 timeout。第一版接受，不增加进程监控。

首次 Trial 容器应当没有该固定目录。`mkdir` 或 `mkfifo` 遇到已有路径时直接失败，不预先递归删除，不增加旧状态清理。

### 6.5 `_submit_turn()`

编码：

```python
encoded_instruction = base64.b64encode(
    instruction.encode(
        "utf-8"
    )
).decode(
    "ascii"
)
request = f"TURN {encoded_instruction}"
```

通过环境变量传入一次 Shell 命令：

```bash
set -e
printf '%s\n' "$QUILLITH_HARBOR_REQUEST" \
    >/tmp/quillith-harbor-session/request.pipe
IFS= read -r quillith_status \
    </tmp/quillith-harbor-session/response.pipe

if [ "$quillith_status" != "OK" ]; then
    echo "Quillith Harbor 返回未知状态" >&2
    exit 1
fi
```

调用：

```python
await self.exec_as_agent(
    environment,
    command=submit_command,
    env={
        "QUILLITH_HARBOR_REQUEST": request,
    },
)
```

不得把 instruction 或 Base64 拼入 command，也不得打印它们。

Java 写 `OK` 后该命令返回，Harbor 随后自然执行 verifier。

### 6.6 Trial 结束

不实现 close。最后一个 step 后 Java 继续阻塞等待 request，直到 Harbor 销毁 Trial 容器。

不修改 `populate_context_post_run()`。

## 7. 测试

### 7.1 ApplicationOptionsTest

新增：

- `harbor` 解析为 `Mode.HARBOR`；
- Harbor Memory 默认开启；
- `harbor --memory=off` 可关闭；
- Harbor 位置参数快速失败。

### 7.2 HarborRunnerTest

新增：

`src/test/java/dev/learn/agent/manual/cli/HarborRunnerTest.java`

只测试不依赖 Linux FIFO的解码逻辑：

- 多行中文和英文 Base64 解码后与原文完全相同；
- 非 `TURN ` 请求失败；
- 非法 Base64 失败。

不要为测试增加生产 Channel 抽象。

现有 `ManualAgentTest.keepsConversationHistoryAcrossSubmissions()` 继续证明同一 `ManualAgent` 第二轮包含第一轮 history。

### 7.3 Adapter 测试

新增：

`src/test/python/test_harbor_adapter.py`

使用标准库 `unittest` 和 AsyncMock，不新增 pytest。覆盖：

- 第一次普通 run 只启动一次并提交一次；
- 后续 resume 不再次启动，只提交；
- 尚未启动时 resume 失败；
- 第二次普通 run 失败，不隐式续聊；
- 多行 Unicode instruction 编码后可还原。

不测试 PID、READY、CLOSE、错误恢复、user 切换或 watchdog。

## 8. 验证

Java：

```powershell
mvn -f demo/manual-agent/pom.xml test
```

Python：

```powershell
python -m unittest discover `
    -s demo/manual-agent/src/test/python `
    -p "test_*.py"
```

构建：

```powershell
mvn -q -f demo/manual-agent/pom.xml -DskipTests package
```

真实三轮验证使用已存在的 Harbor 用例，另立用例需求，不在本执行中修改任务 Dockerfile。

端到端检查：

1. 三轮日志 PID 相同；
2. Java 代码路径只创建一次 Runtime；
3. 每轮只出现一次 Turn Start/Success；
4. 每轮 Success 后 Harbor verifier 能运行；
5. 现有 ManualAgent history 测试通过；
6. 单轮 Exec 与人工 Interactive 回归测试通过。

## 9. 后续增强，不执行

- ERROR 响应；
- PID、READY、exited marker；
- 存活检查；
- 主动 CLOSE；
- 非 resume Multi-step；
- step user 切换；
- timeout、watchdog 和取消；
- 崩溃恢复、重试和 fallback；
- Runtime 序列化；
- 复杂日志和通用 IPC 抽象；
- verifier 隔离修复。

如后续要加入其中任何一项，必须先说明真实问题、缺少它造成的具体失败、最小解决范围和新增状态，不得以“以后可能需要”为理由直接加入。

## 10. 完成标准

- 代码只实现 `start → TURN → submit → OK → verifier → resume TURN`；
- 同一 resume Trial 复用同一个进程、Runtime 和 ManualAgent；
- instruction 多行格式保持；
- 未加入后续增强列表中的能力；
- 未修改 Agent Core、Harbor 框架或测评任务；
- Java 和 Python 测试通过；
- 单轮 Exec 与人工 Interactive 原行为不变。

