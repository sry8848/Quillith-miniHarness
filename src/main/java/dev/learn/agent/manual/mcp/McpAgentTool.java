package dev.learn.agent.manual.mcp;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.NonRetryableToolException;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把 MCP Server 发现的一个远程工具包装成手写 Agent 可以注册的 {@link AgentTool}。
 *
 * <p>模型看到的是带 MCP 命名空间的工具名，实际调用仍使用 MCP Server 返回的原始工具名。
 * 这个包装对象负责 Anthropic 工具定义、JSON 参数转换和 MCP 结果转换。</p>
 */
public final class McpAgentTool implements AgentTool {

    // 复用 Jackson 把模型输入 JsonNode 转为 MCP SDK 需要的普通 Map。
    private static final ObjectMapper ARGUMENT_MAPPER =
            new ObjectMapper();

    // 保存同一个 MCP 客户端，所有属于该 Server 的工具共享一个 MCP 会话。
    private final McpToolClient client;

    // 保存模型侧的命名空间前缀，用于区分本地 Git 和远程 GitHub 工具。
    private final String toolNamePrefix;

    // 保存服务端原始名称，调用 MCP 时不能使用模型侧前缀名称。
    private final String remoteToolName;

    // 保存发送给 Anthropic 的本地工具定义。
    private final Tool definition;

    /**
     * 创建一个 MCP AgentTool 包装对象。
     *
     * @param client 已配置 MCP Server 的客户端
     * @param namespace 模型侧使用的 Server 命名空间，例如 git 或 github
     * @param remoteTool tools/list 返回的远程工具定义
     */
    public McpAgentTool(
            McpToolClient client,
            String namespace,
            McpSchema.Tool remoteTool
    ) {
        this.client =
                Objects.requireNonNull(
                        client,
                        "MCP 客户端不能为空"
                );

        this.toolNamePrefix =
                "mcp__"
                        + Objects.requireNonNull(
                                namespace,
                                "MCP 命名空间不能为空"
                        )
                        + "__";

        Objects.requireNonNull(
                remoteTool,
                "MCP 工具定义不能为空"
        );

        this.remoteToolName =
                remoteTool.name();

        // 在构造阶段完成 Schema 转换，后续模型请求只读取不可变的工具定义。
        this.definition =
                createDefinition(
                        remoteTool
                );
    }

    /**
     * 返回带 MCP Server 命名空间的模型工具定义。
     *
     * @return Anthropic 工具定义
     */
    @Override
    public Tool definition() {
        return definition;
    }

    /**
     * 把模型输入转成 MCP tools/call 请求，并把远程结果转回 Agent 结果。
     *
     * @param input 模型生成的 JSON 对象
     * @return MCP 工具成功或失败结果
     */
    @Override
    public ToolExecutionResult execute(
            JsonNode input
    ) {
        Objects.requireNonNull(
                input,
                "MCP 工具输入不能为空"
        );

        // 模型输入已经在 ToolCall 边界校验为 JSON 对象，这里只做类型映射。
        Map<String, Object> arguments =
                ARGUMENT_MAPPER.convertValue(
                        input,
                        new TypeReference<>() {
                        }
                );

        // 使用远程原始名称调用 MCP，隔离模型侧命名空间。
        McpSchema.CallToolResult result =
                client.callTool(
                        remoteToolName,
                        arguments
                );

        String content =
                renderResult(
                        result
                );

        // MCP isError 只提供远端文本，不能从文本可靠判断临时性，因此保守地停止重试。
        if (Boolean.TRUE.equals(
                result.isError()
        )) {
            throw new NonRetryableToolException(
                    content
            );
        }

        return ToolExecutionResult.success(
                content
        );
    }

    /**
     * 把 MCP 工具定义转换成 Anthropic 工具定义。
     */
    private Tool createDefinition(
            McpSchema.Tool remoteTool
    ) {
        Map<String, Object> inputSchema =
                remoteTool.inputSchema();

        Object schemaType =
                inputSchema.get(
                        "type"
                );

        // 当前 AgentTool 契约要求对象参数，Git Server 的工具 schema 也全部是 object。
        if (schemaType != null
                && !"object".equals(schemaType)) {
            throw new IllegalArgumentException(
                    "MCP tool inputSchema.type 必须是 object: "
                            + remoteTool.name()
            );
        }

        String exposedName =
                toolNamePrefix
                        + remoteTool.name();

        String description =
                remoteTool.description() == null
                        ? ""
                        : remoteTool.description();

        // 只为 GitHub 仓库搜索补充模型可见的调用边界，不改变远端 Schema 和模型参数。
        if ("mcp__github__search_repositories".equals(exposedName)) {
            description +=
                    "\n\n使用 GitHub 高级搜索语法限定 query，避免无组织、用户、主题或语言范围的宽泛搜索。"
                            + "\nminimal_output 默认返回精简仓库信息；只有任务明确需要完整 Repository 对象时才设置为 false。"
                            + "\n首次搜索优先使用较小的 perPage；候选不足时再翻页或增加数量。"
                            + "\n查询特定仓库详情时，优先调用使用 owner 和 repo 定位的详情工具。";
        }

        return ToolDefinitionFactory.create(
                exposedName,
                description,
                readProperties(
                        remoteTool
                ),
                readRequired(
                        remoteTool
                )
        );
    }

    /**
     * 读取 MCP JSON Schema 的 properties，并保留嵌套对象、数组等 JsonValue 内容。
     */
    private static Map<String, JsonValue> readProperties(
            McpSchema.Tool remoteTool
    ) {
        Object rawProperties =
                remoteTool.inputSchema()
                        .get(
                                "properties"
                        );

        if (rawProperties == null) {
            return Map.of();
        }

        if (!(rawProperties instanceof Map<?, ?> properties)) {
            throw new IllegalArgumentException(
                    "MCP tool inputSchema.properties 必须是 object: "
                            + remoteTool.name()
            );
        }

        Map<String, JsonValue> result =
                new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String propertyName)) {
                throw new IllegalArgumentException(
                        "MCP tool property name 必须是字符串: "
                                + remoteTool.name()
                );
            }

            result.put(
                    propertyName,
                    JsonValue.from(
                            entry.getValue()
                    )
            );
        }

        return result;
    }

    /**
     * 读取 MCP JSON Schema 的 required 字段。
     */
    private static List<String> readRequired(
            McpSchema.Tool remoteTool
    ) {
        Object rawRequired =
                remoteTool.inputSchema()
                        .get(
                                "required"
                        );

        if (rawRequired == null) {
            return List.of();
        }

        if (!(rawRequired instanceof List<?> requiredValues)) {
            throw new IllegalArgumentException(
                    "MCP tool inputSchema.required 必须是 array: "
                            + remoteTool.name()
            );
        }

        List<String> result =
                new ArrayList<>();

        for (Object requiredValue : requiredValues) {
            if (!(requiredValue instanceof String requiredName)) {
                throw new IllegalArgumentException(
                        "MCP tool required 字段必须是字符串: "
                                + remoteTool.name()
                );
            }

            result.add(
                    requiredName
            );
        }

        return List.copyOf(
                result
        );
    }

    /**
     * 把 MCP 返回的内容块拼成当前 Agent 使用的文本结果。
     */
    private static String renderResult(
            McpSchema.CallToolResult result
    ) {
        String content =
                result.content()
                        .stream()
                        .map(
                                McpAgentTool::renderContent
                        )
                        .collect(
                                Collectors.joining(
                                        System.lineSeparator()
                                )
                        );

        if (!content.isBlank()) {
            return content;
        }

        return Boolean.TRUE.equals(
                result.isError()
        )
                ? "MCP 工具返回失败，但没有提供错误文本"
                : "MCP 工具返回空结果";
    }

    /**
     * 当前官方 Git Server 返回文本内容；其他 MCP 内容块保留其结构化字符串表示。
     */
    private static String renderContent(
            McpSchema.Content content
    ) {
        if (content instanceof McpSchema.TextContent textContent) {
            return textContent.text();
        }

        return content.toString();
    }
}
