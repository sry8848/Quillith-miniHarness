# Quillith Harbor 官方 multi-step 验证记录

## 验证目标

使用 Harbor 官方 `multi-step` task 验证 Quillith 在 `--resume-trajectory` 模式下能否连续完成三个 step，并确认三个 step 复用同一个 Quillith JVM Session。

## 用例来源

- 仓库：`https://github.com/harbor-framework/harbor-cookbook.git`
- 固定提交：`e093c9a860b988d9d74901010ddddb9c7f124f92`
- 官方目录：`harbor_cookbook/recipes/multi-step`
- 本地目录：`D:\learn_claudecode\harbor-lab\hello-multi-step-full`

最终按固定提交的 Git blob 原始字节重新导入并逐文件校验：文件清单完全一致；除 `environment/Dockerfile` 外，其余 15 个文件与上游 blob 完全一致。

`environment/Dockerfile` 仅在官方依赖 `bash`、`coreutils` 的基础上增加：

- `git`
- `openjdk-21-jre-headless`

没有修改官方 instruction、tests、solution、workdir、timeout 或 verifier 配置。

## 运行条件

- Harbor：`0.21.0`
- Docker Server：`29.2.1`
- 模型：`anthropic/qwen3.5-flash`
- `DASHSCOPE_API_KEY`：已配置
- `GITHUB_PERSONAL_ACCESS_TOKEN`：已配置
- Quillith Base URL：使用代码中的默认百炼 Anthropic-compatible 地址

首次运行命令：

```powershell
.\quillith-harbor-run.ps1 `
    --path "D:\learn_claudecode\harbor-lab\hello-multi-step-full" `
    --model "anthropic/qwen3.5-flash" `
    --resume-trajectory
```

## 第一次运行结果

结果：**未进入 Trial，验证被 Harbor 读取 task 配置时的编码错误阻断。**

宿主机 Maven package 正常完成，Docker 已就绪。Harbor 在创建 Job 前调用 `Task.is_valid_dir()`，使用 Windows 默认 GBK 编码读取官方 UTF-8 `task.toml`，遇到文件中的 U+2014 EM DASH 后失败：

```text
UnicodeDecodeError: 'gbk' codec can't decode byte 0x94 in position 1064: illegal multibyte sequence
```

官方 `task.toml` 中共定位到 4 个 U+2014 字符，首个字符索引为 1062；其 UTF-8 字节序列中的 `0x94` 对应异常位置。

因此本次没有创建 Harbor Job 和 Trial，三个 step verifier 均未执行，也没有产生可用于比较 PID 的三轮 Agent 日志。当前结果不能判断 Quillith 多轮链路是否通过。

## 编码根因修复

根因是 Harbor 0.21.0 在任务运行链上调用 `Path.read_text()` 时没有指定编码，使读取结果依赖 Windows 系统默认 GBK，而 Harbor 官方任务文件实际使用 UTF-8。

在当前实际生效的 Harbor 安装中，将本次运行链上的任务配置和 instruction 读取明确改为 `read_text(encoding="utf-8")`：

- `harbor/models/task/task.py`：任务构造、目录校验、普通 instruction、step instruction、额外 instruction；
- `harbor/cli/jobs.py`：启动 Job 前收集任务配置中的环境变量声明。

修复后执行了语法编译与真实官方任务解析检查：

```text
valid= True
name= harbor/hello-multi-step-full
steps= ['scaffold', 'implement', 'document']
instruction_lengths= [266, 560, 459]
```

该修复位于 uv 安装的 Harbor 0.21.0 `site-packages`，重新安装或升级 Harbor 会覆盖它。

## 后续运行过程

### Docker 包仓库瞬时失败

Job：`jobs/2026-09-05__22-30-32`

编码修复后成功创建 Job，但 Docker 构建期间 Ubuntu 仓库下载 `libkrb5support0` 返回一次 `502 Bad Gateway`，环境构建以 exit code 100 失败。没有修改 Dockerfile、镜像源或 apt 参数，随后使用同一命令重新运行。

### Windows checkout 改写官方文件换行

Job：`jobs/2026-09-05__22-36-58`

该 Job 正常执行到第二轮，结果为：

- scaffold：`1.0`；
- implement：`0.0`；
- document：因 `min_reward` 未执行；
- 最终 reward：`0.5`。

Agent 生成的 `/app/greet.sh` 行为正确，Turn 1、Turn 2 的 PID 都是 `66`。失败根因是第一次导入从 Windows Git 工作树复制，Git `core.autocrlf` 将官方 `steps/implement/tests/expected.txt` 的 LF 改成 CRLF。Verifier 的 shell 命令替换保留了结尾 `\r`，导致字符串比较失败。

原先与同一个 Windows 工作树进行 SHA-256 比较，无法发现导入源自身已经发生换行转换。修正后从 `core.autocrlf=false` 的固定提交 checkout 重新复制，并用 `git hash-object --no-filters` 逐个对比提交 blob。最终 15 个非 Dockerfile 文件全部匹配，`expected.txt` 确认为单个 LF 结尾。

### 第三轮同步记忆超过官方超时

Job：`jobs/2026-09-05__22-46-20`

三个 verifier 均为 `1.0`，三轮 PID 都是 `66`，最终 reward 为 `1.0`。但 document step 记录了 `AgentTimeoutError: Agent execution timed out after 60.0 seconds`，因此该次运行不作为最终无异常验证结果。

日志表明第三轮主任务约 15 秒完成，随后 `AgentSession.submit()` 同步执行 `memoryRuntime.completeTurn()`。记忆提取完成后 Java 才写 FIFO 成功响应，整体超过官方 60 秒 Agent timeout。保持 Memory 开启是完整 Session 验证的一部分，因此没有关闭 Memory，也没有修改官方 task；最终通过 Harbor 参数将 Agent timeout 乘数设为 2。

## 最终验证结果

最终命令：

```powershell
.\quillith-harbor-run.ps1 `
    --path "D:\learn_claudecode\harbor-lab\hello-multi-step-full" `
    --model "anthropic/qwen3.5-flash" `
    --resume-trajectory `
    --agent-timeout-multiplier 2
```

- Job：`D:\learn_claudecode\demo\Quillith-miniHarness\jobs\2026-09-05__22-52-38`
- Job ID：`dbabaa54-8eb1-4080-a3a6-81bf8e23bd97`
- Trial：`hello-multi-step-full__ihNgGLd`
- 总运行时间：2 分 53 秒
- Harbor 异常数：0
- scaffold reward：`1.0`
- implement reward：`1.0`
- document reward：`1.0`
- 最终 mean reward：`1.0`

同一个累计 Agent 日志包含：

```text
Harbor Turn 1 Start, pid=67
Harbor Turn 1 Success, pid=67
Harbor Turn 2 Start, pid=67
Harbor Turn 2 Success, pid=67
Harbor Turn 3 Start, pid=67
Harbor Turn 3 Success, pid=67
```

三个 step 的 `exception_info` 均为空，三个 verifier 均实际执行并通过。由相同 PID、连续 Turn 编号以及同一累计日志可以确认三轮复用了同一个 Quillith JVM Session。

结论：**Quillith 当前工作区已经能够通过 Harbor 官方完整 multi-step task 的三轮续聊验证，Quillith 源代码不需要为该能力修改。**

## 第一次失败时未执行的处理

按照本次验证约束，没有：

- 修改官方 `task.toml` 的 Unicode 标点；
- 为 Harbor 进程设置 Python UTF-8 模式；
- 修改 Harbor 源码的文件读取编码；
- 修改 Quillith 代码、超时、重试或 verifier 配置；
- 在失败后切换模型或增加兜底。

这些绕过方式最终也没有采用；编码问题通过 Harbor 任务文本显式 UTF-8 读取修复。
