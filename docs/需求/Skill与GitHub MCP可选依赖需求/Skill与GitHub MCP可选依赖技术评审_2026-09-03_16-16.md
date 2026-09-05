# Skill 与 GitHub MCP 可选依赖技术评审

## 一、评审目标

本评审对应需求文档：

- [Skill与GitHub MCP可选依赖需求_2026-09-03_15-52.md](D:/learn_claudecode/docs/需求/Skill与GitHub%20MCP可选依赖需求/Skill与GitHub%20MCP可选依赖需求_2026-09-03_15-52.md)

目标是在不增加通用框架的前提下，使 Skill 和 GitHub MCP 成为彼此独立的可选能力：缺失或初始化异常时记录日志并停用对应能力，Agent 的必需部分继续启动。

本次只评审技术方案，不进入具体代码执行。

## 二、当前实现与问题

### 2.1 Skill 当前是启动必需依赖

`AgentRuntime.create()` 当前无条件使用 `agentState.agentHome()/skills` 创建 `SkillRegistry`。

`SkillRegistry` 构造时立即对根目录调用 `toRealPath()`，因此目录不存在、不可访问或路径错误都会抛出异常。这个异常直接离开 `AgentRuntime.create()`，导致整个 Agent 启动失败。

创建成功后，同一个 `SkillRegistry` 同时被以下两处使用：

- 创建并注册 `LoadSkillTool`；
- 作为父 Agent 的 `SystemPromptProvider`，向模型提供 Skill 目录。

因此 Skill 降级不能只跳过其中一处。否则会出现模型看得到 Skill 提示但没有加载工具，或者注册了加载工具但模型没有 Skill 目录的状态。

### 2.2 GitHub MCP 当前是启动必需依赖

`AgentRuntime.create()` 当前在装配其他组件前读取 `GITHUB_PERSONAL_ACCESS_TOKEN`，未配置时直接抛出 `IllegalStateException`。

Token 存在时，启动流程无条件创建 `GitHubMcpClient`，并执行：

```text
创建 Client
  -> MCP initialize
  -> tools/list
  -> 包装 McpAgentTool
  -> 注册到父 Agent ToolRegistry
```

任何运行时异常都会进入当前共享的 MCP 异常处理，然后再次向外抛出。该异常处理同时负责 Git MCP 和 GitHub MCP，无法表达“Git 保持原行为，GitHub 单独降级”。

### 2.3 生命周期当前假定 GitHub MCP 一定存在

`AgentRuntime` 的 `githubMcpClient` 字段和 `close()` 都按客户端必然创建处理。允许 GitHub MCP 缺失后，字段需要能够表达“本次 Runtime 没有创建该资源”，关闭时也只能关闭实际创建成功的客户端。

### 2.4 已有日志能力可以直接复用

项目已经依赖 SLF4J，并通过 `simplelogger.properties` 配置输出。`ManualAgentApplication`、`AgentLoop`、`ToolRegistry` 等现有代码也已经使用 `Logger`。

因此本需求直接在 `AgentRuntime` 使用 SLF4J 的 `WARN` 和 `ERROR`，不新增日志依赖、日志门面或错误上报系统。

## 三、概念模型

Skill 和 GitHub MCP 分别只有两种装配结果：

```text
可用
  -> 注册对应工具和提示能力

不可用
  -> 记录日志
  -> 不注册对应能力
  -> 继续装配 Agent 必需部分
```

两项能力互不依赖：

```text
Skill 装配结果 -------+--> 父 Agent Skill 工具与提示
                     |
基础 Agent 装配 ------+--> Agent 正常启动
                     |
GitHub MCP 装配结果 --+--> 父 Agent GitHub 工具
```

不新增统一的 `OptionalCapability`、状态机或生命周期管理器。现有 `AgentRuntime.create()` 就是依赖装配边界，由它分别作出是否注册的决定。

## 四、推荐方案

### 4.1 Skill：局部尝试创建，失败后条件装配

在 `AgentRuntime.create()` 的现有 Skill 创建位置完成以下职责：

1. Skill 根目录不存在时，按正常零技能状态处理，不记录异常日志，也不创建 `SkillRegistry`。
2. 根目录存在时，尝试按现有严格规则创建 `SkillRegistry`。
3. 扫描、路径解析或 Skill 文件解析抛出异常时，记录包含原始异常堆栈的 `ERROR`，将本次 Skill 能力视为不可用。
4. 只有注册表创建成功且确实包含 Skill 时，才向父工具表注册 `LoadSkillTool`，并向父 System Prompt 加入 Skill Provider。
5. 空 Skill 目录作为正常零技能状态处理，不记录异常日志，也不注册 Skill 工具和提示。

为了判断成功创建的注册表是否为空，允许 `SkillRegistry` 增加一个只读查询。这个查询只暴露已有 `skills` 集合的状态，不改变扫描行为，也不引入新的状态来源。

失败时不创建空的 `SkillRegistry` 占位对象。空对象会要求新增特殊构造路径，还会让后续代码无法区分“正常加载了零个 Skill”和“加载过程中发生异常”，不符合最小实现目标。

Skill 解析目前先写入构造器局部 Map，全部完成后才赋值给字段。构造失败时不会向外暴露半成品，因此在 `AgentRuntime` 捕获异常后直接跳过整个 Skill 能力即可，不需要回滚。

### 4.2 GitHub MCP：Token 条件判断与独立异常边界

GitHub MCP 装配从本地 Git MCP 的异常处理范围中拆开，但仍保留在 `AgentRuntime.create()` 的现有 MCP 装配区域。

处理规则如下：

- Token 未配置或为空白：记录 `WARN`，不创建客户端。
- Token 存在：尝试创建客户端并完成 MCP 初始化、工具发现和工具包装。
- 上述过程抛出运行时异常：记录包含原始异常堆栈的 `ERROR`，关闭已经创建的 GitHub MCP 客户端，然后继续启动。
- 客户端关闭本身再次失败：记录第二条 `ERROR`，仍然继续启动。
- 成功完成初始化和注册：把客户端保存到 `AgentRuntime`，由正常 `close()` 负责关闭。

Git MCP 继续保持当前行为：没有 `gitRoot` 时不创建；存在 `gitRoot` 但 Git MCP 初始化失败时仍按原有逻辑失败。本需求不借机统一两个 MCP 的降级政策。

### 4.3 MCP 工具注册先准备、后提交

当前 `registerMcpTools()` 已经在注册前完成 `initialize` 和 `tools/list`，但 `McpAgentTool` 是在逐个注册时创建的。如果某个远程工具 Schema 转换失败，前面的 GitHub 工具可能已经进入 `ToolRegistry`，随后客户端又被关闭，形成指向已关闭客户端的半注册工具。

最小调整是在现有 `registerMcpTools()` 内把流程分成两段：

```text
阶段一：initialize、tools/list、创建并校验全部 McpAgentTool
阶段二：把已经完整准备好的工具注册到 ToolRegistry
```

GitHub 工具带有固定的 `mcp__github__` 命名空间。准备阶段同时检查本批工具名称不重复；完成检查后，注册阶段只执行本地确定性写入。这样不需要给 `ToolRegistry` 增加删除、事务或回滚能力。

这项调整复用现有方法，不新增 MCP 装配层。它也保持 Git MCP 的对外行为不变。

### 4.4 System Prompt 使用条件列表

父 System Prompt 当前通过固定 `List.of(...)` 加入 `SkillRegistry`。Skill 允许缺失后，改为在装配阶段先加入现有必需 Provider，再在 Skill 可用时追加 Skill Provider。

这里使用普通的局部列表和一次条件判断即可，不新增空 Provider、组合 Provider 或动态插件注册器。

SubAgent 当前既没有 `LoadSkillTool`，也没有 Skill Provider，本需求不改变其行为。

### 4.5 Runtime 关闭逻辑

`githubMcpClient` 与当前 `gitMcpClient` 一样允许为空，表示本次 Runtime 没有获得该资源。

`AgentRuntime.close()` 只在 GitHub MCP 客户端存在时关闭它。这里直接使用条件判断，不新增通用资源集合或关闭管理器。

本需求只要求可选能力初始化和降级过程不阻塞启动，不扩大为整个 `AgentRuntime.close()` 的异常治理改造。

## 五、异常与日志边界

### 5.1 日志级别

| 场景 | 级别 | 启动行为 |
| --- | --- | --- |
| Skill 根目录不存在 | 不记录 | 按正常零技能状态继续启动 |
| Skill 根目录为空 | 不记录 | 按正常零技能状态继续启动 |
| GitHub Token 未配置或为空白 | `WARN` | 跳过 GitHub MCP，继续启动 |
| Skill 路径、读取、格式或未知运行时异常 | `ERROR` | 记录异常堆栈，跳过 Skill，继续启动 |
| GitHub MCP 创建、认证、连接、初始化、发现或包装异常 | `ERROR` | 记录异常堆栈，跳过 GitHub MCP，继续启动 |
| 降级清理 GitHub MCP 时关闭失败 | `ERROR` | 记录异常堆栈，继续启动 |

`WARN` 用于需要提示用户的预期能力缺失状态，例如 GitHub Token 未配置。Skill 根目录不存在或为空都是正常业务状态，不产生日志。`ERROR` 使用 SLF4J 的异常参数记录原始异常对象，保留完整堆栈，不只拼接 `exception.getMessage()`。

### 5.2 捕获范围

未知异常兜底只放在两个可选能力边界内：

- Skill 根目录检查、注册表创建和空状态判断；
- GitHub MCP 客户端创建、协议初始化、工具发现与工具包装。

捕获 Java `Exception` 范围内的未知异常，包括未预期的 `RuntimeException`。不捕获 `Error`；`OutOfMemoryError`、`LinkageError` 等 JVM 级问题下继续运行并不安全，也不属于可选能力配置或外部服务故障。

不在整个 `AgentRuntime.create()` 或 `ManualAgentApplication.main()` 外围增加兜底。工作区、模型客户端、基础工具或其他必需能力失败时，仍然正常向外抛出。

## 六、代码影响范围

### 必须修改

- `AgentRuntime.java`
  - 增加现有 SLF4J 风格的 Logger。
  - 将 Skill 与 GitHub MCP 改为独立的条件装配。
  - 条件注册 Skill 工具和 Skill System Prompt Provider。
  - 允许 GitHub MCP 客户端不存在，并调整关闭判断。
  - 使 MCP 工具在全部准备完成后再注册。

- `SkillRegistry.java`
  - 增加只读的空状态查询，供装配层决定是否注册能力。

### 不修改

- `GitHubMcpClient.java`：继续保持 Token 非空和协议调用的严格契约，是否降级由装配层决定。
- `LoadSkillTool.java`：继续要求有效的 `SkillRegistry`，不接收空值或失效注册表。
- `ToolRegistry.java`：不增加删除、事务、回滚或可选注册机制。
- `GitMcpClient.java` 与 Git 仓库发现逻辑：保持现状。
- `AgentLoop`、SubAgent、权限、任务、记忆和文件工具：与本需求无关。
- 日志配置与依赖：复用现有 SLF4J 配置。

## 七、方案对比与性价比

### 方案 A：在 `AgentRuntime` 局部条件装配（推荐）

优点：

- 修改集中在真实启动装配点。
- 不改变 Skill 和 GitHub 客户端各自的严格内部契约。
- 不新增框架，状态和生命周期条件清楚。
- 两项能力可以独立成功或失败。

代价：

- `AgentRuntime` 增加少量条件判断。
- Skill Provider 列表由固定列表改为条件构建。

该代价直接来自当前需求，复杂度最低。

### 方案 B：让 `SkillRegistry` 和 `GitHubMcpClient` 内部自行吞错

不采用。客户端或注册表无法决定宿主是否应注册工具和提示，内部吞错还会把严格契约变成隐式状态，诊断困难。

### 方案 C：建设统一可选能力框架

不采用。当前只有两个明确的装配点，没有重复到值得引入接口、状态机、生命周期容器或配置系统。

### 方案 D：全局捕获启动异常

不采用。它会把模型、工作区和基础工具等必需能力的错误也伪装成可降级故障，扩大需求范围并隐藏真实问题。

## 八、边界条件

- Skill 根目录不存在：作为正常零技能状态，不记录异常日志，也不是 Agent 启动失败。
- Skill 根目录存在但不可访问：记录错误和堆栈，停用全部 Skill。
- 单个 Skill 非法：为保持注册表一致性，停用全部 Skill，不保留部分成功结果。
- Skill 目录为空：作为正常零技能状态，不记录异常日志，不注册 `load_skill`，也不加入 Skill System Prompt。
- GitHub Token 缺失：不构造 `GitHubMcpClient`。
- GitHub Token 被拒绝或远端不可达：关闭已创建客户端，不注册 GitHub 工具。
- GitHub MCP 返回非法工具定义：在注册前完成包装校验，失败后不留下半注册工具。
- GitHub MCP 降级不影响已经成功注册的本地 Git MCP。
- 两项能力都不可用：父 Agent 仍保留基础工具、任务工具、记忆和 SubAgent 能力。
- JVM `Error`：不兜底，避免在进程状态不可信时继续运行。

## 九、验证策略

验证以关键行为为限，不为了测试引入新的工厂、依赖注入接口或环境变量封装层。

### 自动验证

- 为 `SkillRegistry` 的空状态查询补充最小单元测试。
- 若调整 MCP 工具准备顺序，补充“后续工具 Schema 非法时不提交前序工具”的测试；测试使用现有 `McpToolClient` 接口的简单假实现，不连接真实 GitHub。
- 运行现有 Maven 测试，确认本地 Git MCP、AgentState、工具注册和其他行为无回归。

### 启动验证

使用可控环境完成以下最小启动检查：

1. Skill 目录和 GitHub Token 都存在且有效。
2. Skill 目录不存在，GitHub Token 存在。
3. Skill 目录存在，GitHub Token 未配置。
4. Skill 目录和 GitHub Token 都不存在。
5. Skill 内容非法，确认记录 `ERROR` 堆栈后继续启动。
6. GitHub MCP 初始化失败，确认记录 `ERROR` 堆栈、关闭客户端且继续启动。

不把真实 GitHub 服务稳定性作为自动测试前提。

## 十、决策过程记录

### 决策一：错误配置是否继续阻塞启动

最初存在两个可选方向：

- 只把“未安装、未配置”视为可选缺失，配置存在但错误时仍终止启动；
- Skill 和 GitHub MCP 范围内的全部异常都降级，不让增强能力影响 Agent 基础使用。

第一次需求确认曾倾向前一种做法，因为它能更直接地暴露错误配置。后续需求进一步明确：“这些异常似乎都不影响启动，不用阻塞启动”，并要求未知异常也要“兜住用户”。因此最终选择后一种做法。

为了避免降级等同于吞错，最终规则是：

- 需要用户关注的预期缺失记录 `WARN`；Skill 根目录不存在或为空都属于正常零技能状态，不记录日志；
- 配置、认证、连接、解析和未知运行时异常记录带完整堆栈的 `ERROR`；
- 两类情况都只停用对应可选能力，Agent 继续启动。

### 决策二：在哪一层处理降级

评审过三个位置：

1. 在 `SkillRegistry` 和 `GitHubMcpClient` 内部处理；
2. 在 `AgentRuntime.create()` 的各自装配位置处理；
3. 在应用入口统一捕获所有启动异常。

客户端和注册表内部只负责严格创建资源，不掌握宿主的工具与 System Prompt 注册状态。在内部吞错会产生“对象已创建但实际不可用”的隐式状态。应用入口全局捕获则无法区分可选能力和必需能力。

最终选择 `AgentRuntime.create()`，因为它同时掌握资源创建、工具注册、System Prompt 装配和生命周期归属，能够完整地跳过一项能力而不改变其他组件契约。

### 决策三：如何表达“能力未创建”

评审过三种表示方式：

- 新增空 `SkillRegistry` 或空 GitHub MCP Client；
- 引入统一的 `OptionalCapability<T>` 或状态对象；
- 在装配层使用可空局部变量和字段，并在使用点显式判断。

空对象需要额外构造路径，还可能把初始化失败伪装成正常的空能力。统一状态对象只服务两个局部装配点，增加的类型和生命周期规则高于收益。

最终采用可空装配结果。项目中的 `gitMcpClient` 已使用同样方式表达“本次没有创建资源”，Skill 和 GitHub MCP 只各有少量使用点，直接条件判断更容易理解。

### 决策四：Skill 目录缺失或为空时是否仍注册 `load_skill`

目录缺失时创建空注册表，或者目录为空时继续保留 `load_skill`，都可以减少后续装配分支，但模型会看到一个永远没有可加载内容的工具和 `(no skills available)` 提示。这虽然不会启动失败，却仍让 Agent 表现出对无效 Skill 能力的依赖。

最终决定目录缺失和空目录都是合法、正常的零技能状态，不记录 `WARN` 或 `ERROR`，并同时跳过 `LoadSkillTool` 和 Skill System Prompt Provider。目录缺失时不创建注册表；目录为空时通过 `SkillRegistry` 的只读空状态查询决定不装配能力，不增加空对象或新的能力接口。

### 决策五：未知异常捕获到什么范围

只捕获已知的 `IOException` 和参数异常，代码更严格，但无法满足未知异常不影响启动的要求。捕获 `Throwable` 覆盖最广，却会尝试从内存耗尽、类链接失败等 JVM 级故障中继续运行，状态可能已经不可信。

最终在 Skill 和 GitHub MCP 各自的初始化边界捕获 `Exception`，覆盖已知异常和未知 `RuntimeException`，但不捕获 `Error`。这个边界满足“兜住用户”，同时保留 JVM 严重故障的正常失败语义。

### 决策六：如何避免 GitHub 工具半注册

直接在现有 `registerMcpTools()` 外层捕获异常改动最少，但当前方法在循环中一边创建 `McpAgentTool` 一边注册。如果后续工具 Schema 非法，前面已经注册的工具仍指向随后被关闭的客户端。

评审过给 `ToolRegistry` 增加事务、删除或回滚能力，但这些通用能力只服务当前一次初始化，明显超出需求。

最终复用现有 `registerMcpTools()`，仅把内部顺序调整为“完整发现与包装，再统一注册”，并在提交前验证本批名称。远端、协议和 Schema 异常因此发生在工具表修改之前，不需要通用回滚机制。

### 决策七：是否为了自动测试引入依赖注入

`AgentRuntime.create()` 直接读取环境变量并直接构造远程 Client，完整自动化所有启动组合需要增加配置封装、Client 工厂或依赖注入入口。

这些结构主要服务测试，而不是当前运行需求。最终不为本需求新增此类抽象：自动测试覆盖已有纯内存边界和 MCP 准备顺序，环境变量及真实远端组合使用启动检查验证。

### 决策八：最终修改范围

综合以上决策，最小修改范围收敛为：

- `AgentRuntime`：两个独立装配边界、条件注册、日志和资源判空；
- `SkillRegistry`：一个只读空状态查询；
- 与 MCP 准备顺序直接相关的最小测试，以及 Skill 空状态测试。

不修改 GitHub Client 内部契约、ToolRegistry 通用能力、本地 Git MCP 政策和其他 Agent 模块。

## 十一、评审结论

采用方案 A：在 `AgentRuntime` 现有装配流程中分别处理 Skill 和 GitHub MCP，通过可空的局部装配结果、条件注册和条件关闭实现降级；复用 SLF4J 记录 `WARN` 与带堆栈的 `ERROR`。

方案只需要修改 `AgentRuntime` 和为 `SkillRegistry` 增加一个只读查询。MCP 注册沿用现有方法，仅调整为先完整准备再提交，避免失败时留下半注册 GitHub 工具。

该方案没有新增通用抽象、配置项、重试机制或全局异常兜底，是满足当前需求的最小实现。
