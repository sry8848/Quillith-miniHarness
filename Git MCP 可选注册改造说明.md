# manual-agent：Git MCP 可选注册改造说明

## 1. 改造目标

本次只解决一个启动边界问题：

> 当前运行环境没有 Git，或者当前工作目录不属于 Git 仓库时，manual-agent 不能因为 Git 相关能力初始化失败而启动失败。

目标行为是：

- 能发现有效 Git 仓库根目录时，保持当前行为，正常注册本地 Git MCP。
- 发现不到 Git 或 Git 仓库时，把 Git MCP 当作不可用的可选能力。
- Git 不可用时，不创建 GitMcpClient，不执行 Git MCP 的 initialize、tools/list，也不把 Git 工具注册到 ToolRegistry。
- GitHub MCP、普通文件工具、任务工具、Agent 主循环继续按原流程启动。
- 本次不新增、不修改测试；本文只描述后续 Java 代码改法。

这里的“Git 路径”按当前代码实际含义指 Git 仓库根目录 gitRoot，不是 git.exe 的安装路径。GitRepositoryResolver 通过 PATH 中的 git 命令发现仓库根目录，GitMcpClient 再把这个仓库根目录传给官方 Git MCP Server 的 repository 参数。

## 2. 当前代码为什么会启动失败

### 2.1 当前启动链路

ManualAgentApplication 的实际流程是：

~~~text
cwd
  -> GitRepositoryResolver.findRoot(cwd)
  -> RuntimeContext(cwd, gitRoot)
  -> new GitMcpClient(gitRoot)
  -> registerMcpTools(..., "git")
  -> registerMcpTools(..., "github")
~~~

当前对应代码位置：

- Git 仓库发现：ManualAgentApplication.main 中约第 166 行。
- Git MCP 构造和注册：ManualAgentApplication.main 中约第 340-355 行。
- Git MCP 关闭：启动异常处理约第 364-366 行、会话 finally 约第 814 行。
- 通用 MCP 注册方法：ManualAgentApplication.registerMcpTools。

### 2.2 Git 发现失败的两种现状

GitRepositoryResolver.findRoot 当前有以下行为：

1. 环境中没有 git 命令时，ProcessBuilder.start() 抛 IOException。
2. 当前目录不是 Git 仓库时，Git 退出码不为 0，解析器主动抛 IOException。
3. Git 返回成功但没有仓库路径时，解析器主动抛 IOException。

这个异常从 main 继续向外抛出，所以程序在完成 Agent 装配之前就结束了。

### 2.3 即使路径为空，当前 Git MCP 代码也不能接收空值

GitMcpClient 构造器明确要求 repository 非空：

~~~java
Objects.requireNonNull(
        repository,
        "MCP Git 仓库不能为空"
);
~~~

因此不能用一个空路径继续构造 GitMcpClient，也不能让通用的 registerMcpTools 方法接收空客户端。正确做法是在调用 GitMcpClient 构造器之前就判断 Git 能力是否可用。

## 3. 推荐方案：在启动装配层把 Git 解析失败转换为“跳过 Git MCP”

### 3.1 方案原则

保留 GitRepositoryResolver 现在的严格行为，不修改它的返回类型：

- GitRepositoryResolver 仍然负责“尝试发现 Git 仓库”。
- 发现失败仍然可以通过 IOException 表达。
- ManualAgentApplication 负责决定 Git MCP 是否装配。
- 对 manual-agent 来说，Git 是可选能力，因此只在 Git 发现这一个边界捕获 IOException，并把 gitRoot 保持为 null。

这样做的好处：

- 改动范围最小，只影响启动装配层。
- 不把 Optional 或 null 语义扩散到 GitRepositoryResolver 的所有调用方。
- 现有解析器测试契约不需要改变：解析器仍然可以单独对非 Git 目录抛异常。
- GitMcpClient 的非空校验继续保留，可以防止未来其他调用方错误地传入空仓库。

注意：异常捕获只能包住 GitRepositoryResolver.findRoot(cwd)，不能包住整个 main，也不能包住 GitHub MCP 注册。否则会把真正的工作区错误、GitHub Token 错误或远程 MCP 错误错误地伪装成“没有 Git”。

### 3.2 目标行为表

| 运行环境 | gitRoot | GitMcpClient | Git MCP 注册 | Agent 启动 |
| --- | --- | --- | --- | --- |
| 有 Git 且当前目录属于仓库 | 有效路径 | 创建 | 正常注册 | 继续 |
| 有 Git 但当前目录不是仓库 | null | 不创建 | 跳过 | 继续 |
| 没有 Git 命令 | null | 不创建 | 跳过 | 继续 |
| Git 已发现，但 Git MCP 初始化本身失败 | 有效路径 | 已尝试创建 | 保持现有异常行为 | 按现有逻辑失败 |

最后一行故意保持现状：本次只修复“没有 Git 时不应启动失败”，不把 uvx 缺失、MCP Server 启动失败、协议握手失败等其他配置错误静默吞掉。

## 4. 具体代码改动

### 4.1 修改 ManualAgentApplication 的 Git 仓库发现代码

文件：

~~~text
demo/manual-agent/src/main/java/dev/learn/agent/manual/ManualAgentApplication.java
~~~

### 删除当前代码

删除 main 中当前这一段：

~~~java
// Git 负责从当前工作目录向上发现仓库根目录，文件工作区本身不随之扩大。
Path gitRoot =
        GitRepositoryResolver.findRoot(
                cwd
        );
~~~

### 替换为以下代码

~~~java
// Git 是可选能力；发现失败时保留 null，后面不装配 Git MCP。
Path gitRoot = null;

try {
    // Git 负责从当前工作目录向上发现仓库根目录，文件工作区本身不随之扩大。
    gitRoot =
            GitRepositoryResolver.findRoot(
                    cwd
            );
} catch (IOException exception) {
    // 当前环境没有可用 Git 仓库时，只跳过依赖 Git 的 MCP，不阻断 Agent 启动。
    System.out.println(
            "[Git MCP] 未发现可用的 Git 仓库，跳过 Git MCP 注册："
                    + exception.getMessage()
    );///这里改成log更好吧，级别是debug。
}
~~~

### 这段修改的关键约束

- try 只包住 GitRepositoryResolver.findRoot(cwd)。
- cwd 本身仍然由 Path.toRealPath() 严格解析；工作目录不可访问仍应正常失败，因为所有文件工具都依赖它。
- 不在 catch 中重新设置 cwd，不把当前工作目录伪装成 Git 仓库根目录。
- 不打印异常堆栈；“没有 Git”是允许的运行状态，输出一条可定位的启动提示即可。
- 不继续使用异常中可能包含的错误路径构造 GitMcpClient。

### 4.2 修改 Git MCP 客户端的创建和注册顺序

文件仍然是：

~~~text
demo/manual-agent/src/main/java/dev/learn/agent/manual/ManualAgentApplication.java
~~~

### 删除当前代码

删除当前无条件创建和注册 Git MCP 的代码：

~~~java
GitMcpClient gitMcpClient =
        new GitMcpClient(
                gitRoot
        );

GitHubMcpClient githubMcpClient =
        new GitHubMcpClient(
                githubToken
        );

try {
    registerMcpTools(
            toolRegistry,
            gitMcpClient,
            "git"
    );

    registerMcpTools(
            toolRegistry,
            githubMcpClient,
            "github"
    );
} catch (RuntimeException exception) {
    // 启动阶段尚未进入主循环，无法依赖下面的会话 finally，因此这里立即关闭两个 MCP 连接。
    githubMcpClient.close();
    gitMcpClient.close();
    throw exception;
}
~~~

### 替换为以下代码

~~~java
// Git MCP 只有在发现有效 Git 仓库后才创建；null 表示本次会话没有该可选能力。
GitMcpClient gitMcpClient = null;

GitHubMcpClient githubMcpClient =
        new GitHubMcpClient(
                githubToken
        );

try {
    // 没有 Git 仓库时完整跳过 Git MCP 的创建、握手、工具发现和注册。
    if (gitRoot != null) {
        gitMcpClient =
                new GitMcpClient(
                        gitRoot
                );

        registerMcpTools(
                toolRegistry,
                gitMcpClient,
                "git"
        );
    }

    // GitHub MCP 与本地 Git 无关，继续按原流程注册。
    registerMcpTools(
            toolRegistry,
            githubMcpClient,
            "github"
    );
} catch (RuntimeException exception) {
    // 启动阶段尚未进入主循环，失败时立即关闭已经成功创建的 MCP 连接。
    githubMcpClient.close();
    if (gitMcpClient != null) {
        gitMcpClient.close();
    }
    throw exception;
}
~~~

### 为什么判断必须放在 GitMcpClient 构造器之前

下面几种写法都不正确：

~~~java
// 错误：空路径仍然会进入 GitMcpClient 构造器。
new GitMcpClient(gitRoot);
~~~

~~~java
// 错误：用 cwd 作为 fallback 会把普通工作目录伪装成 Git 仓库。
new GitMcpClient(
        gitRoot == null ? cwd : gitRoot
);
~~~

~~~java
// 错误：先初始化 Git MCP，再在 registerMcpTools 内部判断，
// 此时 MCP 子进程可能已经启动并且已经产生错误。
registerMcpTools(
        toolRegistry,
        gitMcpClient,
        "git"
);
~~~

只有在 gitRoot != null 时才执行 new GitMcpClient 和 registerMcpTools，才能保证 Git MCP 在无 Git 环境中完全不参与启动流程。

### 4.3 修改 finally 中的 Git MCP 清理

当前 finally 中有无条件关闭：

~~~java
githubMcpClient.close();
gitMcpClient.close();
~~~

替换为：

~~~java
githubMcpClient.close();
if (gitMcpClient != null) {
    gitMcpClient.close();
}
~~~

原因是无 Git 环境下 gitMcpClient 从未创建，清理阶段也不能无条件调用 close。

异常 catch 和 finally 都需要这个 null 判断：

- catch 负责 GitHub 注册失败时清理已经创建的客户端。
- finally 负责正常会话或主循环异常退出时清理已经创建的客户端。

不要为了避免两处判断而新增一个只调用一次的 close 包装方法；这里直接表达生命周期条件即可。

### 4.4 修改 RuntimeContext 的契约注释和 System Prompt 展示

这不是 Git MCP 注册的核心判断，但必须同步处理，否则没有 Git 时模型会看到：

~~~text
Git 仓库根目录：null
~~~

文件：

~~~text
demo/manual-agent/src/main/java/dev/learn/agent/manual/systemprompt/RuntimeContext.java
demo/manual-agent/src/main/java/dev/learn/agent/manual/systemprompt/WorkspaceSystemPromptProvider.java
~~~

### RuntimeContext 只更新参数说明

将 gitRoot 的 Javadoc 改为明确允许为空：

~~~java
/**
 * System Prompt Provider 读取的当前程序真实状态。
 *
 * @param cwd 当前 Agent 工作目录，文件工具以此作为路径边界
 * @param gitRoot 当前工作目录所属的 Git 仓库根目录；未发现 Git 时为 null
 */
public record RuntimeContext(
        Path cwd,
        Path gitRoot
) {}
~~~

不需要把 RuntimeContext 改成 Optional<Path>，也不需要新增校验构造器；本次采用最小改动，只让这个已有字段表达“Git 能力缺失”。

### WorkspaceSystemPromptProvider 替换 Git 路径拼接

当前代码：

~~~java
return Optional.of(
        "当前工作目录："
                + runtimeContext.cwd()
                + "\nGit 仓库根目录："
                + runtimeContext.gitRoot()
);
~~~

替换为：

~~~java
return Optional.of(
        "当前工作目录："
                + runtimeContext.cwd()
                + "\nGit 仓库根目录："
                + (runtimeContext.gitRoot() == null
                ? "未发现"
                : runtimeContext.gitRoot().toString())
);
~~~

这样模型能明确知道：

- 文件工作区仍然是当前 cwd。
- 当前没有 Git 仓库根目录。
- Git MCP 没有注册，不应尝试调用 mcp__git__ 前缀工具。

## 5. 明确不修改的代码

### GitRepositoryResolver.java

本次不修改解析器的返回类型和异常行为。它继续负责严格发现 Git 仓库；非 Git 目录继续可以抛 IOException，由启动装配层决定是否把这个失败视为可选能力缺失。

### GitMcpClient.java

保留 Objects.requireNonNull(repository)。这个校验仍然有价值：只要代码遵守“先判断 gitRoot，再创建客户端”的契约，GitMcpClient 永远不会收到空路径。

不要在 GitMcpClient 内部捕获启动异常，也不要让 GitMcpClient 自己决定是否向 ToolRegistry 注册。客户端只负责 MCP 协议，注册决策属于 ManualAgentApplication 的装配流程。

### registerMcpTools(...)

不修改通用 MCP 注册方法。它的输入契约仍然是一个已经存在且可初始化的 McpToolClient；Git 的条件判断只放在 Git MCP 的调用点，避免影响 GitHub MCP。

### GitHubMcpClient.java

不修改。即使本地没有 Git，远程 GitHub MCP 仍然是独立能力，继续使用 githubToken 完成原有初始化和注册。

### ToolRegistry、PermissionHook 以及 AgentLoop

不修改。没有注册 Git MCP 工具时，这些组件自然不会看到 mcp__git__ 工具；已有权限判断和工具执行流程不需要增加特殊分支。

### 测试代码

本次不新增、不修改测试代码。由于 GitRepositoryResolver 的严格行为不变，现有解析器测试契约不需要随本次改造迁移。

## 6. 后续 AI 的执行顺序

后续真正执行代码修改时，按以下顺序处理：

1. 在 ManualAgentApplication.main 的 GitRepositoryResolver.findRoot 调用处增加局部 try/catch，并让 gitRoot 初始为 null。
2. 把 GitMcpClient 的声明移到 try 外、初始为 null。
3. 仅在 gitRoot != null 时创建 GitMcpClient，并调用 Git MCP 的 registerMcpTools。
4. 保持 GitHubMcpClient 的创建和注册路径不变。
5. 在启动异常 catch 和 finally 中都为 gitMcpClient 增加 null 判断。
6. 修改 RuntimeContext 的参数说明和 WorkspaceSystemPromptProvider 的空路径展示。
7. 不改 GitRepositoryResolver、GitMcpClient、GitHubMcpClient、registerMcpTools、ToolRegistry、PermissionHook、AgentLoop 和测试代码。

## 7. 最终验收条件

代码完成后，行为必须满足：

- 没有 Git 命令时，启动日志提示跳过 Git MCP，程序不会因为 Git 发现异常退出。
- 当前目录不是 Git 仓库时，启动日志提示跳过 Git MCP，程序不会因为解析器主动抛 IOException 退出。
- 无 Git 时，不出现 GitMcpClient 构造、Git MCP initialize、Git MCP tools/list 和 Git MCP 工具注册。
- 无 Git 时，GitHub MCP 仍按原逻辑初始化和注册。
- 有 Git 且能发现仓库时，Git MCP 的行为与改造前一致。
- 无 Git 时，finally 不会对不存在的 GitMcpClient 调用 close。
- GitMcpClient 仍然拒绝空 repository；空值应在宿主装配层被拦截，而不是由客户端吞掉。
