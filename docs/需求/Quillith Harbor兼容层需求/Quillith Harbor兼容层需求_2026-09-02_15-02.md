# Quillith Harbor 兼容层需求

## 需求描述

在 Quillith 项目内增加 Harbor 兼容层，使 Harbor 能将 Quillith 作为评测 Agent 运行。兼容层负责宿主机构建、Trial 环境准备、模型配置映射、单次任务启动、日志保存和退出状态传递，不在 Harbor 侧重新实现 Quillith 的 Agent、记忆或权限逻辑。

一次 Harbor Trial 对应一次 Quillith Exec 进程。Harbor 提供的完整 instruction 作为一条 Agent Prompt 传给：

```text
java -jar <quillith.jar> exec [options] <instruction>
```

Quillith 在 Trial 工作目录中执行任务，完成后退出，不进入 Interactive 输入循环。Exec 继续遵循既有行为：Memory 默认开启，Permission 默认使用 `BYPASS`。

## 构建与分发

- 每次执行一条 Harbor `run` 前，兼容层在宿主机根据当前 Quillith 源码执行一次 Maven 构建。
- 构建产物必须是配置了入口类并包含全部运行依赖的 executable/self-contained JAR，可直接通过 `java -jar` 启动。
- 同一 Job 下的所有并行 Trial 复用本次构建产物；Trial 容器内不得再次执行 Maven 构建。
- 宿主机构建失败时应在启动 Trial 前明确失败，不使用旧 JAR 继续评测。

这样既保证每次评测使用当前源码，也避免并行 Trial 重复编译影响调试效率和评测速度。

## Trial 运行行为

- 首版只要求兼容 Harbor Linux 容器，并保证容器中具备运行该 JAR 所需的 Java 环境。
- Bash executable 不得继续硬编码为 Windows 路径，也不得改成只适用于 Harbor 的固定 Linux 路径。Quillith 应支持配置或按平台解析；Harbor Linux 默认使用 `/bin/bash`，Windows 本机运行能力必须保留。
- instruction 必须完整、安全地传入 Quillith，空格、换行和 Shell 特殊字符不能因兼容层拼接命令而被改变或提前执行。
- Quillith 的 stdout 和 stderr 保存到 Harbor Agent 日志目录，并保持评测过程可观察。
- Quillith 非零退出、启动失败或配置错误必须如实反馈给 Harbor，不能吞错或伪装成功。
- Quillith 在 Trial 工作目录产生的文件修改直接保留，供 Harbor 验证器检查。

## 模型配置

首版只支持 Anthropic API-compatible 协议。Harbor 可显式提供模型名、API Key 和 Base URL，并映射给 Quillith；显式配置优先，未提供的配置使用 Quillith 现有默认配置。

如果 Harbor 指定 OpenAI、Gemini 或其他 Quillith 不支持的协议，兼容层必须在启动阶段明确快速失败，不在本需求中新增模型 Provider。

Quillith 当前对 `GITHUB_PERSONAL_ACCESS_TOKEN` 的要求保持不变。Harbor 未提供该变量时，启动阶段直接失败，不降级为跳过 GitHub MCP。

## 首版边界

- 不改变 Quillith Interactive 行为。
- 不由兼容层解析或执行 Slash Command。
- 不增加多轮会话、resume、handoff 或跨 Trial 进程复用。
- 不生成 Harbor ATIF trajectory，只保存原始运行日志。
- 不兼容 Windows Harbor 容器，但不能破坏 Quillith 在 Windows 本机运行。

## 调研与决策过程

当前 Quillith 已具备 `exec` 单次执行入口，但 Maven 只生成普通 JAR，模型配置和 Bash 路径仍存在本机固定值。Harbor 的每个 Trial 独立运行，如果在每个容器内编译，会造成重复开销；如果长期复用旧制品，又不能保证评测对应当前源码。因此确定采用“每条 Harbor run 前宿主机构建一次，同一 Job 的 Trial 共享产物”的方式。

模型侧沿用 Quillith 当前 Anthropic-compatible 技术边界，只增加 Harbor 配置映射，不借兼容层扩展新的 Provider。轨迹转换不影响首版完成任务和判分，因此暂不实现。

## 需求评审与价值预估

该兼容层让 Quillith 可以进入 Harbor 的统一评测流程，并在频繁修改源码时保证评测制品新鲜、并行 Trial 启动成本可控。首版范围集中在构建、启动和结果衔接，价值明确，且不要求改造 Agent 核心。

## 验收标准

- 每条 Harbor `run` 前只构建一次当前源码，所有 Trial 使用同一份 self-contained JAR。
- Harbor Linux Trial 能通过 `java -jar` 启动 Quillith Exec，且无需 Maven 和额外 classpath。
- Windows 本机运行不因 Bash 路径改造而失效。
- Harbor 的 Anthropic-compatible 显式模型配置生效，缺省项使用 Quillith 默认值。
- 不支持的模型协议和缺失的 GitHub Token 均在启动阶段明确失败。
- instruction、工作目录产物、日志和进程退出状态均能正确传递。
- Trial 全程不等待人工输入，首版不生成 ATIF trajectory。
