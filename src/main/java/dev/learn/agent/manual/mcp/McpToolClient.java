package dev.learn.agent.manual.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 定义 Agent 注册 MCP 工具时需要的最小客户端能力。
 *
 * <p>本地 Git MCP 和远程 GitHub MCP 使用不同传输方式，
 * 但它们对 Agent 暴露的生命周期和工具调用契约相同，
 * 因此只在这里共享这四个动作，不引入连接管理器。</p>
 */
public interface McpToolClient extends AutoCloseable {

    /**
     * 完成 MCP 初始化握手。
     *
     * @return MCP Server 返回的初始化结果
     */
    McpSchema.InitializeResult initialize();

    /**
     * 获取 MCP Server 当前暴露的工具定义。
     *
     * @return tools/list 返回的工具列表
     */
    List<McpSchema.Tool> listTools();

    /**
     * 调用一个已经发现的 MCP 工具。
     *
     * @param toolName  MCP Server 返回的原始工具名
     * @param arguments 符合工具 inputSchema 的参数
     * @return MCP Server 返回的工具结果
     */
    McpSchema.CallToolResult callTool(
            String toolName,
            Map<String, Object> arguments
    );

    /**
     * 关闭 MCP 客户端及其底层连接。
     */
    @Override
    void close();
}
