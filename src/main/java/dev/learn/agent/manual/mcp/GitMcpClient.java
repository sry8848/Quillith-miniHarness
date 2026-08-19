package dev.learn.agent.manual.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 stdio 连接 MCP 官方参考 Git Server。
 *
 * <p>这个类只负责 MCP 协议边界：启动子进程、完成初始化、发现工具、调用工具和关闭连接。
 * AgentTool 适配逻辑由 {@link McpAgentTool} 负责，避免把 MCP SDK 类型扩散到 Agent 主循环。</p>
 */
public final class GitMcpClient implements AutoCloseable {

    // 官方参考 Git Server 的 Python 命令入口，由 uvx 负责准备运行环境。
    private static final String UVX_COMMAND =
            "uvx";

    // 官方发布包名称；它对应 modelcontextprotocol/servers 仓库中的 src/git。
    private static final String GIT_SERVER_PACKAGE =
            "mcp-server-git";

    // MCP 请求超时是单次协议调用的边界，不代表 Git 命令本身的业务超时。
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(30);

    // 保存官方 SDK 的同步客户端，整个 Agent 会话共用一个 stdio 子进程。
    private final McpSyncClient client;

    /**
     * 创建一个连接当前工作区的 Git MCP Client。
     *
     * <p>构造对象不会立即启动子进程；真正的进程启动发生在首次调用 {@link #initialize()} 时，
     * 这样生命周期仍然由 initialize/close 两个明确动作控制。</p>
     *
     * @param workspace 允许 Git MCP Server 操作的工作区
     */
    public GitMcpClient(
            Path workspace
    ) {
        Objects.requireNonNull(
                workspace,
                "MCP 工作区不能为空"
        );

        // 把主机侧工作区固定传给 server，避免模型选择任意 MCP Server 或任意根目录。
        Path repository =
                workspace.toAbsolutePath()
                        .normalize();

        ServerParameters parameters =
                ServerParameters.builder(
                                UVX_COMMAND
                        )
                        .args(
                                GIT_SERVER_PACKAGE,
                                "--repository",
                                repository.toString()
                        )
                        .build();

        // 使用官方 SDK 的 stdio transport，协议消息通过子进程 stdin/stdout 传输。
        StdioClientTransport transport =
                new StdioClientTransport(
                        parameters,
                        McpJsonDefaults.getMapper()
                );

        // 同步客户端与当前手写 Agent 的阻塞式工具执行模型一致。
        this.client =
                McpClient.sync(
                                transport
                        )
                        .requestTimeout(
                                REQUEST_TIMEOUT
                        )
                        .initializationTimeout(
                                REQUEST_TIMEOUT
                        )
                        .build();
    }

    /**
     * 启动 Git MCP 子进程并完成 MCP 初始化握手。
     *
     * @return 服务端返回的初始化结果
     */
    public McpSchema.InitializeResult initialize() {
        return client.initialize();
    }

    /**
     * 获取当前 Git MCP Server 暴露的工具定义。
     *
     * @return 服务端发现的工具列表
     */
    public List<McpSchema.Tool> listTools() {
        return client.listTools()
                .tools();
    }

    /**
     * 调用一个已经通过 tools/list 发现的 Git MCP 工具。
     *
     * @param toolName 服务端原始工具名，例如 {@code git_status}
     * @param arguments 符合远程工具 inputSchema 的参数
     * @return 服务端返回的 MCP 工具结果
     */
    public McpSchema.CallToolResult callTool(
            String toolName,
            Map<String, Object> arguments
    ) {
        Objects.requireNonNull(
                toolName,
                "MCP 工具名称不能为空"
        );
        Objects.requireNonNull(
                arguments,
                "MCP 工具参数不能为空"
        );

        // 使用远程原始名称调用，模型侧的 mcp__git__ 前缀只属于本地主机命名空间。
        McpSchema.CallToolRequest request =
                McpSchema.CallToolRequest
                        .builder(
                                toolName
                        )
                        .arguments(
                                arguments
                        )
                        .build();

        return client.callTool(
                request
        );
    }

    /**
     * 优雅关闭 MCP Client 及其 stdio 子进程。
     */
    @Override
    public void close() {
        client.closeGracefully();
    }
}
