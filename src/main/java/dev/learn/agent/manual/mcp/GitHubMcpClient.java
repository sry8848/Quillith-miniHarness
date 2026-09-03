package dev.learn.agent.manual.mcp;

import dev.learn.agent.manual.tool.NonRetryableToolException;
import dev.learn.agent.manual.tool.RetryableToolException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 Streamable HTTP 连接官方托管的 GitHub MCP Server。
 *
 * <p>这个客户端不启动本地子进程，也不依赖 Docker；
 * 它只负责向官方远程端点发送 MCP 请求，工具适配由 {@link McpAgentTool} 负责。</p>
 */
public final class GitHubMcpClient implements McpToolClient {

    // 官方托管 GitHub MCP Server 的 HTTP 基础地址。
    private static final String REMOTE_SERVER_URL =
            "https://api.githubcopilot.com";

    // Streamable HTTP MCP 端点。
    private static final String REMOTE_SERVER_ENDPOINT =
            "/mcp/";

    // MCP 请求超时是单次协议调用的边界，不代表 GitHub API 的业务超时。
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(30);

    // 远程演示只开放仓库、Issue 和 Pull Request 工具集。
    private static final String REMOTE_TOOLSETS =
            "repos,issues,pull_requests";

    // 保存官方 SDK 的同步客户端，整个 Agent 会话共用一个 HTTP MCP 会话。
    private final McpSyncClient client;

    /**
     * 创建一个只读 GitHub MCP 客户端。
     *
     * @param githubToken GitHub 访问令牌，将作为 Bearer Token 发送给远程服务
     * @throws IllegalArgumentException 当令牌为空或只包含空白字符时抛出
     */
    public GitHubMcpClient(
            String githubToken
    ) {
        if (githubToken == null
                || githubToken.isBlank()) {
            throw new IllegalArgumentException(
                    "GitHub MCP 需要非空的 GitHub Token"
            );
        }

        // 使用 SDK 2.x 的 requestBuilder 配置所有 MCP 请求共享的鉴权和工具筛选头。
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport
                        .builder(
                                REMOTE_SERVER_URL
                        )
                        .endpoint(
                                REMOTE_SERVER_ENDPOINT
                        )
                        .requestBuilder(
                                HttpRequest.newBuilder()
                                        .header(
                                                "Authorization",
                                                "Bearer " + githubToken
                                        )
                                        .header(
                                                "X-MCP-Readonly",
                                                "true"
                                        )
                                        .header(
                                                "X-MCP-Toolsets",
                                                REMOTE_TOOLSETS
                                        )
                        )
                        .jsonMapper(
                                McpJsonDefaults.getMapper()
                        )
                        .build();

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
     * 完成远程 GitHub MCP 初始化握手。
     *
     * @return 服务端返回的初始化结果
     */
    @Override
    public McpSchema.InitializeResult initialize() {
        return client.initialize();
    }

    /**
     * 获取远程 GitHub MCP Server 暴露的只读工具。
     *
     * @return 服务端发现的工具列表
     */
    @Override
    public List<McpSchema.Tool> listTools() {
        return client.listTools()
                .tools();
    }

    /**
     * 调用一个已经通过 tools/list 发现的 GitHub MCP 工具。
     *
     * @param toolName  服务端返回的原始工具名
     * @param arguments 符合远程工具 inputSchema 的参数
     * @return 服务端返回的 MCP 工具结果
     */
    @Override
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

        // 模型侧的 mcp__github__ 前缀只属于宿主 Agent，远程服务只接收原始名称。
        McpSchema.CallToolRequest request =
                McpSchema.CallToolRequest
                        .builder(
                                toolName
                        )
                        .arguments(
                                arguments
                        )
                        .build();

        try {
            return client.callTool(
                    request
            );
        } catch (RuntimeException exception) {
            // GitHub MCP 只暴露只读工具；仅已识别的瞬时传输原因才允许重新发送请求。
            if (isRetryableTransportFailure(
                    exception
            )) {
                throw new RetryableToolException(
                        "GitHub MCP transport temporarily failed",
                        exception
                );
            }

            // SDK 对普通 MCP transport failure 不稳定暴露 HTTP 状态，不能靠错误文本猜测 429 或 5xx。
            throw new NonRetryableToolException(
                    "GitHub MCP call failed",
                    exception
            );
        }
    }

    /**
     * 判断异常因果链中是否包含已确认的瞬时网络失败。
     *
     * @param exception MCP 调用抛出的原始异常
     * @return 包含超时或连接失败根因时返回 true
     */
    static boolean isRetryableTransportFailure(
            RuntimeException exception
    ) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }

            Throwable cause =
                    current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }

        return false;
    }

    /**
     * 优雅关闭远程 MCP HTTP 会话。
     */
    @Override
    public void close() {
        client.closeGracefully();
    }
}
