# Quillith Harbor 兼容层需求

## 需求描述

在 Quillith 项目内增加 Harbor 兼容能力，使 Harbor 能将 Quillith 作为评测 Agent 运行。首版只连接宿主机构建、Harbor Trial 和 Quillith Exec，不在兼容层中重新实现 Agent、记忆或权限逻辑。

一次 Harbor Trial 对应一次 Quillith Exec 进程。Harbor 提供的 instruction 作为一条 Agent Prompt 传给：

```text
java -jar <quillith.jar> exec [options] <instruction>
```

Quillith 在 Trial 工作目录中完成任务后退出，不进入 Interactive 输入循环。Exec 继续遵循既有行为：Memory 默认开启，Permission 默认使用 `BYPASS`。

## 构建生命周期

每次 Harbor `run` 必须采用以下固定顺序：

```text
Quillith 源码
      ↓
宿主机执行一次 Maven build
      ↓
得到本次运行确定的 self-contained JAR
      ↓
Harbor 启动多个 Trial
      ↓
本次 Job 的所有 Trial 使用同一个 JAR
```

明确禁止：

```text
Trial A → mvn package
Trial B → mvn package
Trial C → mvn package
```

Maven 构建必须发生在所有 Trial 启动之前。Harbor Adapter 的 `install()` 或其他 Trial 生命周期方法不得下载 Quillith 源码、安装 Maven 或执行 Maven 构建。

构建产物必须包含运行依赖并配置入口类，使 Trial 可直接通过 `java -jar` 启动。宿主机构建失败时，本次 Harbor 运行直接失败，不得继续使用旧 JAR。

## 宿主机启动层

由于普通 `harbor run` 不保证提供整个 Job 开始前只执行一次 Maven 的入口，首版需要一个很薄的宿主机 launcher。它只负责：

```text
1. 执行 Maven build
2. 找到本次生成的 JAR
3. 将 JAR 路径提供给 Harbor Adapter
4. 调用 harbor run，并传递原有 Harbor 参数
```

它本质上只是 `build + harbor run`。首版不增加通用配置系统、构建缓存、制品仓库、版本管理、插件生命周期或独立的 Quillith Harbor CLI。具体实现可以是项目内几十行的 PowerShell 启动脚本，例如 `quillith-harbor-run.ps1`。

## Trial 运行行为

- 首版只保证在项目选定的 Harbor Linux Trial 环境中运行，不承诺兼容任意 Linux 发行版或 CPU 架构。
- 该环境预先提供兼容的 Java Runtime、bash 和 git；Trial 不下载 JDK、Maven 或 Quillith 源码。
- Bash executable 不得继续硬编码为 Windows 路径，也不得改成只适用于 Harbor 的固定 Linux 路径。Quillith 应支持配置或按平台解析；选定的 Harbor Linux 环境默认使用 `/bin/bash`，Windows 本机运行能力必须保留。
- Adapter 不增加、删除或改写收到的 instruction，只进行启动进程所必需的安全参数传递。空格、换行和 Shell 特殊字符必须原样到达 Quillith，不能被提前解释或执行。
- stdout 和 stderr 保存到 Harbor Agent 日志目录，并保持评测过程可观察。
- Quillith 非零退出、启动失败或配置错误必须如实反馈给 Harbor，不能吞错或伪装成功。
- Quillith 对 Trial 工作目录的修改直接保留，供 Harbor 验证器检查。

## 模型与凭据

首版只支持 Anthropic API-compatible 协议。Harbor 可显式提供模型名、API Key 和 Base URL，并映射给 Quillith；显式配置优先，未提供的配置使用 Quillith 现有默认配置。

Harbor 指定 OpenAI、Gemini 或其他 Quillith 不支持的协议时，必须在启动阶段明确快速失败，不在兼容层中新增模型 Provider。

Quillith 当前对 `GITHUB_PERSONAL_ACCESS_TOKEN` 的要求保持不变。Harbor 未提供该变量时，启动阶段直接失败，不降级为跳过 GitHub MCP。

## 首版边界

- 不改变 Quillith Interactive 行为。
- 不由兼容层解析或执行 Slash Command。
- 不生成 Harbor ATIF trajectory，只保存原始运行日志。
- 不支持 resume、handoff 或跨 Trial 进程复用。
- 不承诺支持 Harbor Multi-step 任务协议；首版只处理一次 Trial instruction 对应一次 Exec 进程的运行方式。这不限制 Quillith 在该 Exec 内部正常执行多轮模型与工具循环。
- 不兼容 Windows Harbor 容器，但不能破坏 Quillith 在 Windows 本机运行。

## 调研与决策过程

当前 Quillith 已具备 Exec 单次执行入口，但尚未生成 self-contained JAR，Bash 路径也仍带有本机平台假设。Harbor 的 Trial 相互独立，把 Maven 构建放入 `install()` 会让并行 Trial 重复编译，因此确定由宿主机 launcher 在 Job 启动前只构建一次，并把同一制品交给所有 Trial。

模型侧继续使用 Quillith 当前 Anthropic-compatible 边界；轨迹转换、Multi-step 和通用制品管理都不是首版完成运行与判分的必要条件，因此不实现。

## 需求评审与价值预估

该方案保证每次评测对应当前 Quillith 源码，同时避免并行 Trial 重复下载和编译。宿主机 launcher 与 Harbor Adapter 都保持很薄，能够满足频繁调试和评测速度需求，而不引入新的平台层。

## 验收标准

- 每次 Harbor `run` 前仅在宿主机执行一次 Maven build。
- 所有 Trial 复用本次确定的同一个 executable/self-contained JAR。
- Trial 内没有 Maven、Quillith 源码下载或编译步骤。
- 项目选定的 Harbor Linux 环境可直接运行 JAR，并具备 Java Runtime、bash 和 git。
- Windows 本机运行不因 Bash 路径改造而失效。
- Adapter 原样传递 instruction，并正确传递工作目录产物、日志和退出状态。
- Harbor 的 Anthropic-compatible 显式模型配置生效，缺省项使用 Quillith 默认值。
- 不支持的模型协议和缺失的 GitHub Token 均在启动阶段明确失败。
- 首版不生成 ATIF trajectory，也不承诺 Harbor Multi-step 支持。
