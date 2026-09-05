# AgentState 核心模型与 Session 装配执行

## 1. 文档定位

本执行文档只完成 `AgentState` 的核心模型、唯一创建位置和现有状态读取链路迁移，不同时改造记忆行为、审批规则、文件权限或内部目录布局。

依赖关系：

```text
本执行文档
    ├─> AgentState 动态记忆与审批状态迁移执行
    ├─> AgentState 多根目录与文件工具边界执行
    └─> AgentHome 内部资源目录迁移执行
```

最终目标是：一次 CLI 进程只创建一个 `AgentState`，父 Agent、SubAgent、System Prompt、Memory 和工具审批链路都引用它，不再通过 `RuntimeContext` 或构造参数保存同一状态的副本。

## 2. 已确认的装配输入

本轮采用以下已确认规则：

1. `agentHome` 来源于固定的用户目录配置；当前配置值保持现有启动目录，因此本轮读取到的实际值仍与之前相同。当前代码没有既有独立配置对象，应用层先把这个已配置值作为局部变量传入状态；不新增 CLI 参数或配置框架。
2. `allowedRoots` 初始值为只包含 `workspace` 的集合：`List.of(workspace)`。
3. `allowedRoots` 必须按集合实现，不能增加“只能有一个目录”、集合长度上限或拒绝未来追加目录的校验。第一版初始集合只有一个元素，不代表模型只能支持一个目录。

`workspace` 仍是启动时的 `cwd`。虽然当前 `agentHome` 配置值与 `workspace` 相同，但代码必须通过不同字段表达两个角色，后续只替换配置值即可改变物理目录。

## 3. 本轮明确不做

- 不新增 `SessionState`、`AgentSession`、Factory 或依赖注入框架。
- 不增加 `/permission` 命令。
- 不改变 Interactive 和 Exec 的现有 Memory 默认值。
- 不改变工具审批规则。
- 不实现 `allowedRoots` 的路径拒绝逻辑。
- 不迁移 `skills/`、`.tasks/`、`.task_outputs/` 或 `.memory/`。
- 不让 `AgentRuntime` 创建或拥有 `AgentState`。
- 不把 history、后台任务、进程、模型本轮结果放入 `AgentState`。

## 4. 先写测试

### 4.1 新增 `AgentStateTest`

新增：

```text
src/test/java/dev/learn/agent/manual/AgentStateTest.java
```

至少覆盖：

1. 构造后能读取 `memoryEnabled`、`approvalMode`、`agentHome`、`workspace`、`allowedRoots` 和可空的 `gitRoot`。

不要为了测试增加生产环境 getter 之外的测试接口。

### 4.2 调整 System Prompt 测试

修改所有直接创建 `RuntimeContext` 的测试，改为创建 `AgentState`。重点保留以下既有断言：

- Workspace Prompt 展示 `workspace`，不再展示名为 `cwd` 的字段。
- 未发现 Git 仓库时仍输出“未发现”。
- SESSION、TURN 和 MODEL_CALL 的刷新时机不变。

只改测试装配，不修改与本需求无关的既有行为断言。

### 4.3 增加装配级身份测试

在能够直接装配父子循环的现有测试夹具中，使用同一个 `AgentState` 创建父 Agent 和 SubAgent 所需组件，并通过后续“动态记忆与审批”文档的行为测试验证引用共享。

不要给 `AgentRuntime`、`AgentLoop` 或 `ManualAgent` 增加仅供测试比较对象身份的 getter。单纯断言 `assertSame` 不值得扩大生产接口；后续修改状态后父子行为同时变化才是有效证据。

## 5. 新增 `AgentState`

新增：

```text
src/main/java/dev/learn/agent/manual/AgentState.java
```

采用一个直接的 `final class`，不要使用 Builder、状态 Map、属性名字符串或事件总线。

字段固定为：

```java
private volatile boolean memoryEnabled;
private volatile ToolApprovalMode approvalMode;
private final Path agentHome;
private final Path workspace;
private final List<Path> allowedRoots;
private final Path gitRoot;
```

设计约束：

- `memoryEnabled` 和 `approvalMode` 是运行期可变状态，读写必须直接经过 `AgentState`。
- 两个可变字段使用 `volatile`，因为后台工具和 SubAgent 可能在其他线程读取，不能依赖主线程缓存可见性。
- 四个路径字段第一版只在 Session 启动时确定，没有 setter。
- `allowedRoots` 使用 `List.copyOf(...)` 保存顺序和不可变快照，不另建集合封装类型。
- `gitRoot` 延续当前可空语义，未发现仓库时为 `null`，本轮不引入 `Optional` 字段。
- 路径是否存在、是否为目录以及符号链接如何归一化由创建层和路径解析器负责；`AgentState` 不执行文件系统 IO。

公共方法只包含六个读取方法和两个修改方法：

```java
public boolean memoryEnabled()
public void setMemoryEnabled(boolean memoryEnabled)
public ToolApprovalMode approvalMode()
public void setApprovalMode(ToolApprovalMode approvalMode)
public Path agentHome()
public Path workspace()
public List<Path> allowedRoots()
public Path gitRoot()
```

每个方法按项目规范补充职责、输入和输出注释。不要增加通用 `get(String key)`、`update(...)`、监听器或状态版本号。

## 6. 删除 `RuntimeContext` 副本

删除：

```text
src/main/java/dev/learn/agent/manual/systemprompt/RuntimeContext.java
```

它的 `cwd` 和 `gitRoot` 与 `AgentState.workspace`、`AgentState.gitRoot` 表达同一状态。即使路径第一版不可变，继续保留两份对象也会让系统再次出现两个权威来源。

按调用链修改：

```text
SystemPromptProvider.load(RuntimeContext)
    -> SystemPromptProvider.load(AgentState)

SystemPromptManager.refreshFrom(..., RuntimeContext)
    -> SystemPromptManager.refreshFrom(..., AgentState)

AgentLoop.runtimeContext
    -> AgentLoop.agentState

ManualAgent.runtimeContext
    -> ManualAgent.agentState
```

`WorkspaceSystemPromptProvider` 改为读取：

```text
agentState.workspace()
agentState.gitRoot()
```

只替换状态来源，不改变 Prompt 文案、Provider 顺序和刷新 Scope。

## 7. 把唯一创建位置移到应用组装层

修改：

```text
src/main/java/dev/learn/agent/manual/ManualAgentApplication.java
src/main/java/dev/learn/agent/manual/AgentRuntime.java
```

### 7.1 `ManualAgentApplication`

应用入口按以下顺序创建 Session 状态：

```text
1. 解析 ApplicationOptions
2. 取得启动 cwd 的真实路径，作为 workspace
3. 以 workspace 为起点发现 gitRoot；失败时保持当前“允许为空”行为
4. 从固定用户目录配置取得 agentHome；当前配置值为 workspace，保持既有路径行为
5. 创建 `List.of(workspace)` 作为 allowedRoots 初始集合，不添加单目录限制
6. Interactive 选择 ASK，Exec 选择 BYPASS
7. 创建唯一 AgentState
8. 把同一个 AgentState 交给对应 Runner/AgentRuntime 装配链
```

不要在 `runInteractive()` 和 `runExec()` 内各创建一份状态。模式分支发生在状态创建之后，两个分支在一次运行中只会消费同一对象。

Git root 发现失败时保持现有 debug 日志和无 Git MCP 行为，不把“未发现仓库”升级成启动失败。

### 7.2 `AgentRuntime`

把创建签名从分散参数：

```java
create(Path cwd, boolean memoryEnabled, ToolApprovalMode approvalMode, Scanner scanner)
```

改为：

```java
create(AgentState agentState, Scanner scanner)
```

删除 Runtime 内的 Git root 发现，以及从 `cwd`、`memoryEnabled`、`approvalMode` 派生状态的代码。装配时所有组件都接收同一个 `agentState`，或在后续路径迁移完成前临时读取其不可变路径值。

`AgentRuntime` 不新增 `agentState` 字段和 getter。它可以把引用注入所创建的资源，但不能成为状态所有者。

## 8. 按依赖顺序修改现有构造器

按以下顺序处理，避免同时出现两套状态接口：

1. 新增 `AgentState` 和单元测试。
2. 修改 `SystemPromptProvider`、各 Provider 和 `SystemPromptManager` 参数类型。
3. 修改 `AgentLoop` 构造器和字段名。
4. 修改 `ManualAgent` 构造器和字段名。
5. 修改全部测试夹具中的 `RuntimeContext` 创建。
6. 修改 `AgentRuntime.create(...)`。
7. 最后修改 `ManualAgentApplication`，建立唯一状态并删除旧参数。
8. 删除 `RuntimeContext.java` 及全部 import。

每完成一个步骤都先编译，再继续下一步；不要保留重载构造器兼容旧模型，否则旧状态来源会继续存在。

## 9. 文件改动清单

新增：

```text
src/main/java/dev/learn/agent/manual/AgentState.java
src/test/java/dev/learn/agent/manual/AgentStateTest.java
```

删除：

```text
src/main/java/dev/learn/agent/manual/systemprompt/RuntimeContext.java
```

修改：

```text
src/main/java/dev/learn/agent/manual/ManualAgentApplication.java
src/main/java/dev/learn/agent/manual/AgentRuntime.java
src/main/java/dev/learn/agent/manual/ManualAgent.java
src/main/java/dev/learn/agent/manual/AgentLoop.java
src/main/java/dev/learn/agent/manual/systemprompt/SystemPromptProvider.java
src/main/java/dev/learn/agent/manual/systemprompt/SystemPromptManager.java
src/main/java/dev/learn/agent/manual/systemprompt/*SystemPromptProvider.java
所有直接构造 RuntimeContext、AgentLoop、ManualAgent 或 AgentRuntime 的测试
```

## 10. 验证

执行：

```powershell
mvn -q test
git diff --check
git status --short
```

再用 `rg` 确认：

```powershell
rg -n "RuntimeContext|\bcwd\(\)|boolean memoryEnabled,|ToolApprovalMode approvalMode," src/main src/test
```

最终不应再有 `RuntimeContext`，也不应再由 `AgentRuntime.create()` 接收分散的 Session 状态参数。

## 11. 验收标准

- [ ] 一次应用启动只显式创建一个 `AgentState`。
- [ ] `AgentRuntime` 不创建、不复制、不暴露 `AgentState`。
- [ ] 父 Agent 和 SubAgent 的装配链获得同一个状态引用。
- [ ] `RuntimeContext` 已删除。
- [ ] System Prompt 直接读取 `AgentState.workspace()` 和 `gitRoot()`。
- [ ] `memoryEnabled`、`approvalMode` 只有 `AgentState` 保存可变值。
- [ ] 路径字段第一版不可修改。
- [ ] 未把 history、后台资源或 AgentLoop 本轮状态放入 `AgentState`。
- [ ] 未为兼容旧接口保留第二套构造器。
- [ ] 既有行为测试和新增状态测试全部通过。
