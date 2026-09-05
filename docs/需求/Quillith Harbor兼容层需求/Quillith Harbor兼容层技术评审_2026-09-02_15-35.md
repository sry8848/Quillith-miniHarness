# Quillith Harbor 兼容层技术评审

## 1. 技术目标

首版 Harbor 兼容层的目标是建立 **Harbor Trial → Quillith Exec** 的最小适配关系，而不是在 Harbor 侧重新实现 Quillith 的 Agent Runtime。

核心约束为：

- 一次 Harbor Trial 对应一个独立的 Quillith Exec 进程；
- Harbor instruction 原样作为一次 Agent Prompt 输入；
- 每次 Harbor Job 开始前只构建一次 Quillith；
- 同一 Job 的所有 Trial 使用同一个构建产物；
- Memory、Permission、模型调用、GitHub MCP 等能力继续由 Quillith 自身负责。

因此兼容层只负责 **构建衔接、进程启动、配置映射、日志和退出状态传递**。

---

## 2. 概念逻辑模型

整体划分为三层：
```text
Host Launcher
    │
    │ build once + 确定 JAR
    ▼
Harbor Job
    │
    ├── Trial A → Harbor Adapter → Quillith Exec
    ├── Trial B → Harbor Adapter → Quillith Exec
    └── Trial C → Harbor Adapter → Quillith Exec
                         │
                         ▼
                 Trial 工作目录
```

### Host Launcher

负责 Job 级别生命周期：
```text
Quillith Source
    ↓
Maven Build
    ↓
确定本次 self-contained JAR
    ↓
启动 harbor run
```

**不变量：一个 Harbor Job 内只存在一个确定的 Quillith 制品。**

Trial 数量变化不能影响 Maven 构建次数，因此构建不能进入 Adapter 的 Trial 生命周期。需求已经明确禁止每个 Trial 单独编译。

### Harbor Adapter

Adapter 是 Harbor 与 Quillith Exec 之间的协议转换层，只负责：
```text
Harbor Trial Context
    ↓
工作目录 / instruction / 模型配置 / 凭据
    ↓
启动 java -jar ... exec
    ↓
stdout / stderr / exit code / 工作目录修改
    ↓
Harbor
```

Adapter 不承担 Agent 逻辑，不解释 instruction，也不维护跨 Trial 状态。

### Quillith Exec

Exec 仍然是实际执行主体。

兼容层不改变 Exec 内部的 Agent 循环、Memory、Permission 或 Tool 行为。这样 Harbor 接入不会形成第二套 Runtime，避免后续 Quillith 自身能力和 Harbor 模式产生行为分叉。

---

## 3. 关键技术设计

### 3.1 构建产物设计

Maven 构建需要生成可以直接执行的 self-contained JAR：
```text
java -jar <quillith.jar> exec ...
```

JAR 必须包含运行依赖并配置正确入口类。

Launcher 构建完成后确定**本次运行唯一的 JAR 路径**，再交给 Harbor Adapter 使用。

如果构建失败，则整个 Harbor Job 失败，不允许退回上一次构建产物，避免“源码版本”和“实际评测版本”不一致。

### 3.2 instruction 传递

instruction 在逻辑上是一个完整参数，而不是 Shell 命令文本。

因此 Adapter 应直接通过进程参数接口传给 Java 进程，而不是拼接 Shell command string。

需要保证：
```text
空格
换行
引号
$
&
|
;
等 Shell 特殊字符
```

不会在到达 Quillith 前被 Shell 解释。

需求要求 Adapter 不增加、删除或改写 instruction。

### 3.3 工作目录

Quillith Exec 的工作目录直接设置为当前 Harbor Trial 工作目录。

Quillith 对文件系统产生的修改直接保留在该目录中，不增加额外文件同步层，由 Harbor verifier 直接检查。

### 3.4 Bash 跨平台处理

当前 Bash 路径不能继续依赖 Windows 本机硬编码。

这里应将“Bash executable”抽象为运行环境配置：
```text
BashExecutable
```

解析优先级可以保持为：
```text
显式配置
    ↓
当前平台默认值
```

Harbor Linux 环境默认 `/bin/bash`，Windows 继续解析现有可用 Bash，从而避免为了 Harbor 接入破坏 Windows 本地执行能力。

### 3.5 模型配置

兼容层只做 Harbor 配置到 Quillith 现有 Anthropic-compatible 配置的映射：
```text
Harbor model      → Quillith model
Harbor API Key    → Quillith API Key
Harbor Base URL   → Quillith Base URL
```

显式 Harbor 配置优先；没有提供的字段继续使用 Quillith 当前默认值。

如果 Harbor 请求 OpenAI、Gemini 等当前不支持的协议，则启动阶段直接失败，不在 Adapter 内增加新的 Provider。

---

## 4. 边界条件

### 支持范围

首版只保证：

- 项目选定的 Harbor Linux Trial 环境；
- 环境已有 Java Runtime、bash、git；
- 一次 instruction 对应一次 Exec；
- Anthropic API-compatible 模型；
- 原始 stdout / stderr 日志；
- Trial 工作目录直接作为 Quillith 工作目录。

### 快速失败场景

以下情况应在能够确定错误时尽早失败：

- Host Maven build 失败；
- 找不到或无法执行本次生成的 JAR；
- Java Runtime 不可用；
- Bash 配置非法；
- 请求了不支持的模型协议；
- 缺少 `GITHUB_PERSONAL_ACCESS_TOKEN`；
- Quillith 进程无法启动。

Quillith 正常启动后产生的非零 exit code 应原样反馈 Harbor，不应转换成成功。

### 明确不处理

首版不处理：

- Harbor Multi-step 协议；
- resume / handoff；
- 跨 Trial Agent 状态；
- ATIF trajectory；
- Slash Command 转换；
- Trial 内 Maven/JDK/源码安装；
- Windows Harbor 容器；
- 通用构建缓存、制品仓库或 Harbor 专用 CLI。

这些能力均不属于当前“让 Harbor 可以运行并判分 Quillith”这一最小闭环。

---

## 5. 代码实现方案

建议保持三个清晰的代码责任边界。

### Quillith Host Launcher

项目内提供轻量 PowerShell launcher，例如：
```text
quillith-harbor-run.ps1
```

责任只有：
```text
build
→ 确定 JAR
→ 将 JAR 信息传入 Harbor
→ 执行 harbor run
```

不发展为新的 CLI 或通用构建系统。

### Harbor Adapter

新增 Harbor 所要求的 Agent Adapter，实现 Trial 生命周期到 Quillith Exec 进程的映射。

内部建议保持三个逻辑模块：
```text
配置解析
    ↓
Exec 启动参数构造
    ↓
进程执行与结果回传
```

Adapter 本身保持无状态，因此多个 Trial 可以安全并行。

### Quillith Runtime 小范围改造

Quillith 内部只需要解决 Harbor 暴露出的通用运行问题，例如：

- 生成可直接执行的 self-contained JAR；
- Bash executable 从硬编码改为可配置/按平台解析。

这些修改应该作为 Quillith 本身的运行能力，而不是写成 Harbor 专用分支。

---

## 6. 性价比评估

### 方案 A：薄 Launcher + 薄 Adapter

本方案选择：
```text
Host Launcher
+
Harbor Adapter
+
少量 Quillith 通用运行能力改造
```

优点：

- 最大程度复用已有 Exec；
- Harbor 和 Quillith 职责清晰；
- 一个 Job 只构建一次；
- Trial 可以并行；
- 不产生第二套 Agent Runtime；
- 对 Windows 本地开发影响较小。

代价主要是增加一个宿主机启动入口，以及维护少量 Harbor Adapter 代码。

### 方案 B：在每个 Trial install 阶段构建

实现简单，但 Trial 并行时会重复下载、编译，并且构建次数随 Trial 数量增长。

同时无法保证 Job 内所有 Trial 使用同一个确定制品，因此不采用。

### 方案 C：建设完整 Harbor 平台层

包括制品缓存、版本管理、Provider 转换、trajectory、Multi-step 等。

扩展性更强，但远超首版运行和判分所需能力，会显著增加实现和维护成本。

因此当前阶段不采用。

## 7. 评审结论

首版采用 **“Host 一次构建 + Trial 薄 Adapter + Quillith Exec 原生执行”** 的方案。

核心设计原则是：

> Harbor 只负责提供 Trial，兼容层只负责连接生命周期，Quillith 继续负责 Agent 本身。

该方案与当前需求目标一致，同时将新增平台复杂度限制在最低范围。需求本身也已将 launcher 定义为简单的 `build + harbor run`，避免演化成新的平台层。
