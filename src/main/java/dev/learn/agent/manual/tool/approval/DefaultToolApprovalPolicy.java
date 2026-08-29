package dev.learn.agent.manual.tool.approval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * manual-agent 当前版本的集中式工具审批策略。
 *
 * <p>当前只按工具类型和文件写入目标判断 REQUIRED 或 NOT_REQUIRED，
 * 不实现系统硬拒绝，也不实现操作系统级沙盒。</p>
 */
public final class DefaultToolApprovalPolicy
        implements ToolApprovalPolicy {

    /*
     * MCP 工具由 McpAgentTool 统一暴露为 mcp__<namespace>__<tool>，
     * 因此使用通用前缀可以覆盖当前和未来注册的所有 MCP 工具。
     */
    private static final String MCP_TOOL_PREFIX =
            "mcp__";

    /*
     * 文件审批只需要借助路径解析器判断最终目标位于 Workspace 内还是外。
     */
    private final WorkspacePathResolver paths;

    /**
     * 创建默认审批策略。
     *
     * @param paths 共享的 Workspace 路径解析器
     */
    public DefaultToolApprovalPolicy(
            WorkspacePathResolver paths
    ) {
        this.paths =
                Objects.requireNonNull(
                        paths,
                        "WorkspacePathResolver 不能为空"
                );
    }

    /**
     * 按当前工具审批规则分类完整工具调用。
     *
     * @param toolCall 实际准备执行的完整工具调用
     * @return 工具调用是否需要审批
     */
    @Override
    public ToolApprovalRequirement requirementFor(
            ToolCall toolCall
    ) {
        Objects.requireNonNull(
                toolCall,
                "ToolCall 不能为空"
        );

        String toolName =
                toolCall.name();

        /*
         * 所有 MCP 工具统一需要审批，不能再通过 Git 只读白名单绕过。
         */
        if (toolName.startsWith(MCP_TOOL_PREFIX)) {
            return ToolApprovalRequirement.REQUIRED;
        }

        return switch (toolName) {
            /* 整个 Bash 工具都需要审批，不分析命令文本。 */
            case "bash" ->
                    ToolApprovalRequirement.REQUIRED;

            /* 只有 Workspace 外的文件写入需要审批。 */
            case "write_file", "edit_file" ->
                    fileWriteRequirement(toolCall);

            /* 读取和 Workspace 搜索都不需要审批。 */
            case "read_file", "glob" ->
                    ToolApprovalRequirement.NOT_REQUIRED;

            /* 当前其他工具没有需要审批的规则。 */
            default ->
                    ToolApprovalRequirement.NOT_REQUIRED;
        };
    }

    /**
     * 判断文件写入目标是否位于 Workspace 外。
     *
     * <p>无法解析的参数交给具体文件工具处理，避免把普通参数或 IO 错误
     * 伪装成权限系统的硬拒绝。</p>
     *
     * @param toolCall write_file 或 edit_file 调用
     * @return Workspace 外返回 REQUIRED，否则返回 NOT_REQUIRED
     */
    private ToolApprovalRequirement fileWriteRequirement(
            ToolCall toolCall
    ) {
        JsonNode pathNode =
                toolCall.input()
                        .get("path");

        if (pathNode == null
                || !pathNode.isTextual()) {
            return ToolApprovalRequirement.NOT_REQUIRED;
        }

        try {
            Path target =
                    switch (toolCall.name()) {
                        case "write_file" ->
                                paths.resolveForWriteAnywhere(
                                        pathNode.textValue()
                                );

                        case "edit_file" ->
                                paths.resolveExistingAnywhere(
                                        pathNode.textValue()
                                );

                        default ->
                                throw new IllegalStateException(
                                        "不支持的文件写入工具："
                                                + toolCall.name()
                                );
                    };

            return paths.isInsideWorkspace(target)
                    ? ToolApprovalRequirement.NOT_REQUIRED
                    : ToolApprovalRequirement.REQUIRED;
        } catch (IOException
                 | InvalidPathException exception) {
            return ToolApprovalRequirement.NOT_REQUIRED;
        }
    }
}
