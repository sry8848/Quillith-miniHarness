// 声明默认工具审批策略测试所属的包。
package dev.learn.agent.manual.tool.approval;

// 引入 JSON 输入、工具调用和 Workspace 路径解析类型。
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.ToolCall;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 引入测试使用的文件和路径类型。
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证默认工具审批策略只按工具类型和文件目标分类。
 */
class DefaultToolApprovalPolicyTest {

    // 使用临时 Workspace 验证文件目标的内外分类。
    @TempDir
    Path workspace;

    /**
     * Given read_file 或 glob 调用，when 通过默认策略分类，then 不需要审批。
     */
    @Test
    void readAndGlobDoNotRequireApproval()
            throws IOException {
        DefaultToolApprovalPolicy policy =
                newPolicy();

        assertEquals(
                ToolApprovalRequirement.NOT_REQUIRED,
                policy.requirementFor(
                        toolCall("read_file")
                )
        );
        assertEquals(
                ToolApprovalRequirement.NOT_REQUIRED,
                policy.requirementFor(
                        toolCall("glob")
                )
        );
    }

    /**
     * Given 文件写入目标，when 目标在 Workspace 内外分类，then 只有外部目标需要审批。
     */
    @Test
    void onlyExternalFileWritesRequireApproval()
            throws IOException {
        Path insideTarget =
                workspace.resolve("inside.txt");
        Path outsideDirectory =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "approval-policy-outside-"
                );
        Path outsideTarget =
                outsideDirectory.resolve("outside.txt");
        DefaultToolApprovalPolicy policy =
                newPolicy(outsideDirectory);

        try {
            assertEquals(
                    ToolApprovalRequirement.NOT_REQUIRED,
                    policy.requirementFor(
                            fileCall(
                                    "write_file",
                                    insideTarget
                            )
                    )
            );
            assertEquals(
                    ToolApprovalRequirement.REQUIRED,
                    policy.requirementFor(
                            fileCall(
                                    "write_file",
                                    outsideTarget
                            )
                    )
            );

            Files.writeString(
                    insideTarget,
                    "inside"
            );
            Files.writeString(
                    outsideTarget,
                    "outside"
            );

            assertEquals(
                    ToolApprovalRequirement.NOT_REQUIRED,
                    policy.requirementFor(
                            fileCall(
                                    "edit_file",
                                    insideTarget
                            )
                    )
            );
            assertEquals(
                    ToolApprovalRequirement.REQUIRED,
                    policy.requirementFor(
                            fileCall(
                                    "edit_file",
                                    outsideTarget
                            )
                    )
            );
        } finally {
            Files.deleteIfExists(insideTarget);
            Files.deleteIfExists(outsideTarget);
            Files.deleteIfExists(outsideDirectory);
        }
    }

    /** allowed roots 外的路径不能通过“需要审批”获得访问资格。 */
    @Test
    void outsideAllowedRootsIsNotClassifiedAsApprovableExternalWrite()
            throws IOException {
        DefaultToolApprovalPolicy policy =
                newPolicy();
        Path forbiddenDirectory =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "approval-policy-forbidden-"
                );

        try {
            assertEquals(
                    ToolApprovalRequirement.NOT_REQUIRED,
                    policy.requirementFor(
                            fileCall(
                                    "write_file",
                                    forbiddenDirectory.resolve(
                                            "forbidden.txt"
                                    )
                            )
                    )
            );
        } finally {
            Files.deleteIfExists(forbiddenDirectory);
        }
    }

    /**
     * Given 任意 Bash 命令，when 通过默认策略分类，then 整个 Bash 工具都需要审批。
     */
    @Test
    void everyBashCommandRequiresApproval()
            throws IOException {
        DefaultToolApprovalPolicy policy =
                newPolicy();

        for (String command : new String[]{
                "echo hello",
                "sudo echo hello",
                "rm -rf /",
                "python -c \"print('hello')\""
        }) {
            ObjectNode input =
                    JsonNodeFactory.instance.objectNode();
            input.put(
                    "command",
                    command
            );

            assertEquals(
                    ToolApprovalRequirement.REQUIRED,
                    policy.requirementFor(
                            new ToolCall(
                                    "bash-id",
                                    "bash",
                                    input
                            )
                    )
            );
        }
    }

    /**
     * Given Git、GitHub 和未来命名空间的 MCP 工具，when 通过默认策略分类，then 全部需要审批。
     */
    @Test
    void everyMcpToolRequiresApproval()
            throws IOException {
        DefaultToolApprovalPolicy policy =
                newPolicy();

        for (String toolName : new String[]{
                "mcp__git__git_status",
                "mcp__git__git_diff",
                "mcp__github__search_repositories",
                "mcp__future__read_only_tool"
        }) {
            assertEquals(
                    ToolApprovalRequirement.REQUIRED,
                    policy.requirementFor(
                            toolCall(toolName)
                    )
            );
        }
    }

    /**
     * Given 普通工具或缺少 path 的文件调用，when 通过默认策略分类，then 不由策略硬拒绝。
     */
    @Test
    void ordinaryAndInvalidCallsDoNotBecomeHardDenied()
            throws IOException {
        DefaultToolApprovalPolicy policy =
                newPolicy();

        assertEquals(
                ToolApprovalRequirement.NOT_REQUIRED,
                policy.requirementFor(
                        toolCall("todo_write")
                )
        );

        assertEquals(
                ToolApprovalRequirement.NOT_REQUIRED,
                policy.requirementFor(
                        toolCall("write_file")
                )
        );
    }

    /** 创建包含 workspace 和额外 allowed roots 的测试策略。 */
    private DefaultToolApprovalPolicy newPolicy(
            Path... extraRoots
    ) throws IOException {
        List<Path> roots =
                new ArrayList<>(
                        List.of(workspace)
                );
        roots.addAll(
                List.of(extraRoots)
        );
        return new DefaultToolApprovalPolicy(
                new WorkspacePathResolver(
                        new SessionState(
                                false,
                                ToolApprovalMode.BYPASS,
                                workspace,
                                workspace,
                                roots,
                                null
                        )
                )
        );
    }

    /** 创建不带参数的工具调用。 */
    private static ToolCall toolCall(
            String name
    ) {
        return new ToolCall(
                name + "-id",
                name,
                JsonNodeFactory.instance.objectNode()
        );
    }

    /** 创建带 path 参数的文件工具调用。 */
    private static ToolCall fileCall(
            String name,
            Path path
    ) {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();
        input.put(
                "path",
                path.toString()
        );

        return new ToolCall(
                name + "-id",
                name,
                input
        );
    }
}
