# Quillith 与 Codex Terminal-Bench 2 评测记录

## 1. 评测目的

在 Harbor 中对同一个 Terminal-Bench 2 sample 任务分别运行 Quillith 和 Codex，记录最终结果、Agent 执行轨迹及评测环境问题，为后续比较两个 Agent 的执行过程提供原始依据。

本次只各取一次最终有效 Trial，不足以形成统计结论；结果用于定位差异和设计后续重复实验。

## 2. 可比性边界

- 数据集：`terminal-bench-sample@2.0`
- 任务：`regex-log`
- 两次最终 Trial 的任务 checksum 均为 `10b968c362d51b20ece22267f40c40998923a88f495c64de2e0ad851282174e0`
- 两次运行使用相同的本地任务目录和已构建任务镜像。
- Quillith 使用 `anthropic/qwen3.5-flash`；Codex 使用 `openai/gpt-5.6-terra`。因此当前差异同时包含 Agent 实现和模型差异，不能全部归因于 Agent 编排能力。
- Quillith 运行时由 launcher 上传 JAR；Codex 运行时使用只读 bind mount 注入官方 Linux 原生二进制。该差异只影响 Agent 安装和启动方式，不改变任务 checksum。
- 总耗时受 verifier 下载速度影响较大。比较 Agent 效率时应优先看 `agent execution`，不应只比较 Trial 总耗时。

## 3. 最终结果

| 项目 | Quillith | Codex |
| --- | --- | --- |
| Job | `2026-09-02__21-25-37` | `2026-09-03__11-35-36` |
| Trial | `regex-log__SSpevtM` | `regex-log__2HvU6KD` |
| Agent 版本 | unknown | `0.153.0` |
| 模型 | `anthropic/qwen3.5-flash` | `openai/gpt-5.6-terra` |
| Job reward | `0.0` | `1.0` |
| 状态 | `AgentTimeoutError` | 正常完成 |
| Trial 总耗时 | 约 1005.5 秒 | 约 389.7 秒 |
| environment setup | 约 10.7 秒 | 约 12.7 秒 |
| agent setup | 约 3.5 秒 | 约 1.6 秒 |
| agent execution | 900.0 秒后超时 | 约 113.9 秒 |
| verifier | 约 88.4 秒 | 约 253.1 秒 |
| Token | 未提供 | input 135734、cache 113664、output 3586 |
| Harbor 报告成本 | 未提供 | `$0.1099048` |

Codex 的成本数字是 Harbor 生成的估算字段。此次实际认证使用当前登录账号缓存的 ChatGPT 登录状态，不能把该字段直接视为 API 实际扣费。

## 4. Agent 执行过程差异

### 4.1 Quillith

- 日志中记录到 14 次 `BeforeModelCall`。
- 共记录 47 次工具调用：`bash` 29 次、`edit_file` 3 次、`read_file` 2 次、`todo_write` 1 次、`write_file` 12 次。
- 中途出现一次 TLS `SSLHandshakeException`，Agent 自行恢复并继续执行。
- 900 秒时仍未正常结束，由 Harbor 判定 `AgentTimeoutError`。
- verifier 实际执行后发现多日期日志行选择错误：
  - 期望 `2024-11-01`，实际返回 `2023-12-31`；
  - 期望 `2018-06-06`，实际返回 `2018-05-05`。
- 结果表明最终正则匹配了 IPv4 后的第一个有效日期，而任务要求的是该行最后一个符合条件的日期。

### 4.2 Codex

- Agent 正常结束，`agent execution` 约 113.9 秒，verifier 唯一测试通过。
- `codex.txt` 中共有 9 个完成项：2 条 Agent 消息、2 次文件变更、5 次命令执行。
- 5 次命令中有 1 次失败：Agent 首先尝试用 `python3` 本地验证，但任务容器没有 Python。
- Agent 随后检查可用运行时，改用 Perl 验证正则，继续修改并完成任务，没有因缺少 Python 中止。
- 与 Quillith 相比，Codex 在更少的文件修改和命令轮次内完成了“写入—验证—修正—再验证”闭环。

## 5. Codex 冷启动与认证问题记录

| 时间 / Job | 尝试 | 结果 |
| --- | --- | --- |
| `2026-09-02__22-41-43` | 容器内在线安装 Codex，默认 360 秒 setup timeout | `apt-get` 下载阶段超时，Agent 未开始执行 |
| `2026-09-02__22-48-44` | setup timeout 提高到 1080 秒 | 仍在 `apt-get` 下载阶段超时，Agent 未开始执行 |
| 无 Job | 临时切换阿里云镜像 | HTTPS 因基础镜像缺 CA 证书失败；HTTP 下载过慢且 universe 索引连接失败，放弃该路径 |
| 无 Job | 隔夜恢复后再次运行 | Docker daemon 未运行；启动 Docker Desktop 后恢复 |
| `2026-09-03__11-30-28` | 挂载本地 Codex 二进制，但未强制使用登录缓存 | Harbor 优先选择主机上遗留的无效 `OPENAI_API_KEY`，Codex 重试后返回 401 |
| `2026-09-03__11-35-36` | 只读挂载官方 Codex 二进制，并设置 `CODEX_FORCE_AUTH_JSON=1` | 使用当前账号的 ChatGPT 登录缓存，任务通过 |

最终使用的 Codex Linux 运行时来自官方 npm 包 `@openai/codex@0.153.0-linux-x64`，本地压缩包约 129.2 MB。二进制目录以只读方式挂载到任务容器的 `/usr/local/bin`，从而避开每次 Trial 在线安装 Node.js、npm 和 Codex 的冷启动成本。

`CODEX_FORCE_AUTH_JSON=1` 是 Harbor Codex Agent 的认证选择开关，表示使用默认的本地 Codex 登录缓存；它不是凭据内容。记录中不保存 `auth.json` 或 API key 的内容。

## 6. 记录链路问题

- Codex 成功 Trial 下载后的 `result.json` 和 `agent/trajectory.json` 含未加引号的 `[REDACTED]` 占位符，不能作为合法 JSON 解析。
- `agent/codex.txt` 也有少数 JSONL 行因相同脱敏替换而失效，但仍可按原始事件文本核对完成项。
- 因此本记录的通过状态以 Harbor Job 汇总、`verifier/reward.txt` 和 `verifier/test-stdout.txt` 交叉确认；执行项数量按 `codex.txt` 原始事件行统计。
- 后续若需要自动化聚合，应先修复或绕开 Harbor 对下载产物的非 JSON 安全脱敏，不能直接依赖当前 ATIF 文件。

## 7. 原始材料

### Quillith

- Job：`D:\learn_claudecode\harbor-lab\run-results\2026-09-02__21-25-37`
- Trial：`D:\learn_claudecode\harbor-lab\run-results\2026-09-02__21-25-37\regex-log__SSpevtM`
- Agent 日志：`agent/quillith.stdout.log`、`agent/quillith.stderr.log`
- verifier：`verifier/test-stdout.txt`、`verifier/reward.txt`

### Codex

- Job：`D:\learn_claudecode\harbor-lab\run-results-codex-terminal-bench2\2026-09-03__11-35-36`
- Trial：`D:\learn_claudecode\harbor-lab\run-results-codex-terminal-bench2\2026-09-03__11-35-36\regex-log__2HvU6KD`
- Agent 日志：`agent/codex.txt`
- ATIF 轨迹：`agent/trajectory.json`，当前因脱敏占位符不是合法 JSON
- verifier：`verifier/test-stdout.txt`、`verifier/reward.txt`

## 8. 当前结论

在本次单任务、单次有效 Trial 中，Codex 通过，Quillith 超时且产物未通过 verifier。Codex 的 Agent 执行时间约为 Quillith 超时上限的 12.7%，并表现出在本地验证工具缺失后切换方案的能力。

该结果只能说明本次组合的表现。要比较两个 Agent 本身，后续至少应统一或分层控制模型、每个任务重复运行，并使用多个不同类型的 Terminal-Bench 2 任务统计成功率、Agent 执行时间、工具调用量和错误恢复情况。
