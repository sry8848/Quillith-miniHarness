package dev.learn.agent.manual.tool.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 glob 以 workspace 为默认根，并支持一次选择一个 allowed root。 */
class GlobToolTest {

    @TempDir
    Path workspace;

    /** 未传 root 时只搜索 workspace，并返回 workspace 相对路径。 */
    @Test
    void defaultsToWorkspaceRoot()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "glob-default-second-root-"
                );
        Path workspaceFile =
                workspace.resolve("workspace.txt");
        Path secondFile =
                secondRoot.resolve("second.txt");
        Files.writeString(
                workspaceFile,
                "workspace"
        );
        Files.writeString(
                secondFile,
                "second"
        );

        try {
            GlobTool glob =
                    new GlobTool(
                            resolver(workspace, secondRoot)
                    );
            ToolExecutionResult result =
                    glob.execute(
                            input(
                                    "*.txt",
                                    null
                            )
                    );

            assertEquals(
                    "workspace.txt",
                    result.content()
            );
            assertFalse(result.error());
        } finally {
            Files.deleteIfExists(workspaceFile);
            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(secondRoot);
        }
    }

    /** root 参数选择第二个 allowed root，结果相对于该根返回。 */
    @Test
    void searchesSelectedAllowedRoot()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "glob-selected-root-"
                );
        Path file =
                secondRoot.resolve("selected.txt");
        Files.writeString(
                file,
                "selected"
        );

        try {
            GlobTool glob =
                    new GlobTool(
                            resolver(workspace, secondRoot)
                    );
            ToolExecutionResult result =
                    glob.execute(
                            input(
                                    "*.txt",
                                    secondRoot.toString()
                            )
                    );

            assertEquals(
                    "selected.txt",
                    result.content()
            );
            assertFalse(result.error());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(secondRoot);
        }
    }

    /** root 参数指向未授权目录时，不能借 glob 绕过解析器边界。 */
    @Test
    void rejectsForbiddenSearchRoot()
            throws IOException {
        Path forbiddenRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "glob-forbidden-root-"
                );

        try {
            GlobTool glob =
                    new GlobTool(
                            resolver(workspace)
                    );
            ToolExecutionResult result =
                    glob.execute(
                            input(
                                    "*.txt",
                                    forbiddenRoot.toString()
                            )
                    );

            assertTrue(result.error());
            assertTrue(
                    result.content()
                            .contains("not an allowed directory")
            );
        } finally {
            Files.deleteIfExists(forbiddenRoot);
        }
    }

    /** 搜索根中的链接指向未授权目录时，不暴露该链接结果。 */
    @Test
    void filtersSymlinkTargetsOutsideAllowedRoots()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "glob-link-root-"
                );
        Path forbiddenRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "glob-link-forbidden-"
                );
        Path link =
                secondRoot.resolve("outside.txt");
        Path forbiddenFile =
                forbiddenRoot.resolve("target.txt");
        Files.writeString(
                forbiddenFile,
                "forbidden"
        );

        try {
            createSymlinkOrSkip(
                    link,
                    forbiddenFile
            );
            GlobTool glob =
                    new GlobTool(
                            resolver(workspace, secondRoot)
                    );
            ToolExecutionResult result =
                    glob.execute(
                            input(
                                    "*.txt",
                                    secondRoot.toString()
                            )
                    );

            assertEquals(
                    "(no matches)",
                    result.content()
            );
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(forbiddenFile);
            Files.deleteIfExists(forbiddenRoot);
            Files.deleteIfExists(secondRoot);
        }
    }

    private WorkspacePathResolver resolver(
            Path... allowedRoots
    ) throws IOException {
        return new WorkspacePathResolver(
                new SessionState(
                        false,
                        ToolApprovalMode.BYPASS,
                        workspace,
                        workspace,
                        List.of(allowedRoots),
                        null
                )
        );
    }

    private static ObjectNode input(
            String pattern,
            String root
    ) {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();
        input.put(
                "pattern",
                pattern
        );
        if (root != null) {
            input.put(
                    "root",
                    root
            );
        }
        return input;
    }

    private static void createSymlinkOrSkip(
            Path link,
            Path target
    ) {
        try {
            Files.createSymbolicLink(
                    link,
                    target
            );
        } catch (IOException
                 | UnsupportedOperationException
                 | SecurityException exception) {
            Assumptions.assumeTrue(
                    false,
                    "当前环境不支持创建符号链接："
                            + exception.getMessage()
            );
        }
    }
}
