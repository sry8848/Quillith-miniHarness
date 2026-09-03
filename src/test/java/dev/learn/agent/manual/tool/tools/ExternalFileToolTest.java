// 声明文件工具外部路径测试所属的包。
package dev.learn.agent.manual.tool.tools;

// 引入 JSON 输入、文件工具和 Workspace 路径解析类型。
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.manual.AgentState;
import dev.learn.agent.manual.tool.NonRetryableToolException;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 引入测试使用的文件和路径类型。
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证文件工具在审批层放行后确实能够操作 Workspace 外路径。
 */
class ExternalFileToolTest {

    // 为工具和外部目录提供隔离的临时 Workspace。
    @TempDir
    Path workspace;

    /**
     * Given 三个文件工具收到 Workspace 外路径，when 直接执行工具，then 读写能力不再被 Workspace hard deny 阻断。
     */
    @Test
    void fileToolsCanOperateOnExternalPaths()
            throws IOException {
        Path outsideDirectory =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "external-file-tool-"
                );
        Path file =
                outsideDirectory.resolve("external.txt");
        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        new AgentState(
                                false,
                                ToolApprovalMode.BYPASS,
                                workspace,
                                workspace,
                                List.of(
                                        workspace,
                                        outsideDirectory
                                ),
                                null
                        )
                );

        WriteFileTool writeFileTool =
                new WriteFileTool(
                        paths
                );
        ReadFileTool readFileTool =
                new ReadFileTool(
                        paths
                );
        EditFileTool editFileTool =
                new EditFileTool(
                        paths
                );

        try {
            ToolExecutionResult writeResult =
                    writeFileTool.execute(
                            writeInput(
                                    file,
                                    "before"
                            )
                    );
            assertFalse(
                    writeResult.error(),
                    writeResult.content()
            );

            ToolExecutionResult readResult =
                    readFileTool.execute(
                            pathInput(file)
                    );
            assertFalse(
                    readResult.error(),
                    readResult.content()
            );
            assertEquals(
                    "before",
                    readResult.content()
            );

            ToolExecutionResult editResult =
                    editFileTool.execute(
                            editInput(
                                    file,
                                    "before",
                                    "after"
                            )
                    );
            assertFalse(
                    editResult.error(),
                    editResult.content()
            );
            assertEquals(
                    "after",
                    Files.readString(file)
            );
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(outsideDirectory);
        }
    }

    /** allowed roots 外的目标即使直接执行，也不能被三个文件工具访问。 */
    @Test
    void fileToolsRejectPathsOutsideAllowedRoots()
            throws IOException {
        Path forbiddenDirectory =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "external-file-tool-forbidden-"
                );
        Path existingFile =
                forbiddenDirectory.resolve("existing.txt");
        Path newFile =
                forbiddenDirectory.resolve("new.txt");
        Files.writeString(
                existingFile,
                "before"
        );
        WorkspacePathResolver paths =
                new WorkspacePathResolver(
                        new AgentState(
                                false,
                                ToolApprovalMode.BYPASS,
                                workspace,
                                workspace,
                                List.of(workspace),
                                null
                        )
                );

        try {
            NonRetryableToolException readException =
                    assertThrows(
                            NonRetryableToolException.class,
                            () -> new ReadFileTool(
                                    paths
                            ).execute(
                                    pathInput(existingFile)
                            )
                    );
            ToolExecutionResult writeResult =
                    new WriteFileTool(
                            paths
                    ).execute(
                            writeInput(
                                    newFile,
                                    "blocked"
                            )
                    );
            ToolExecutionResult editResult =
                    new EditFileTool(
                            paths
                    ).execute(
                            editInput(
                                    existingFile,
                                    "before",
                                    "after"
                            )
                    );

            assertTrue(
                    readException.getMessage()
                            .contains(
                                    "Path escapes allowed roots"
                            )
            );
            assertTrue(writeResult.error(), writeResult.content());
            assertTrue(editResult.error(), editResult.content());
            assertEquals(
                    "before",
                    Files.readString(existingFile)
            );
        } finally {
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(newFile);
            Files.deleteIfExists(forbiddenDirectory);
        }
    }

    /** 创建只有 path 的输入。 */
    private static ObjectNode pathInput(
            Path path
    ) {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();
        input.put(
                "path",
                path.toString()
        );
        return input;
    }

    /** 创建 write_file 输入。 */
    private static ObjectNode writeInput(
            Path path,
            String content
    ) {
        ObjectNode input =
                pathInput(path);
        input.put(
                "content",
                content
        );
        return input;
    }

    /** 创建 edit_file 输入。 */
    private static ObjectNode editInput(
            Path path,
            String oldText,
            String newText
    ) {
        ObjectNode input =
                pathInput(path);
        input.put(
                "old_text",
                oldText
        );
        input.put(
                "new_text",
                newText
        );
        return input;
    }
}
