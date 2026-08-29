// 声明 Workspace 路径解析测试所属的包。
package dev.learn.agent.manual.utils;

// 引入 JUnit 测试注解和临时目录能力。
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 引入测试使用的文件和路径类型。
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Workspace 内部边界方法和 Agent 文件工具开放解析方法各自的职责。
 */
class WorkspacePathResolverTest {

    // 为每个测试提供隔离的真实 Workspace。
    @TempDir
    Path workspace;

    /**
     * Given Workspace 外已有文件，when 使用内部读取解析方法，then 仍然拒绝外部路径。
     */
    @Test
    void internalExistingResolverKeepsWorkspaceBoundary()
            throws IOException {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        workspace
                );
        Path outsideFile =
                Files.createTempFile(
                        workspace.getParent(),
                        "resolver-outside-",
                        ".txt"
                );

        try {
            assertThrows(
                    IOException.class,
                    () ->
                            resolver.resolveExisting(
                                    outsideFile.toString()
                            )
            );
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    /**
     * Given Workspace 外新文件，when 使用内部写入解析方法，then 仍然拒绝外部路径。
     */
    @Test
    void internalWriteResolverKeepsWorkspaceBoundary()
            throws IOException {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        workspace
                );
        Path outsideDirectory =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-write-outside-"
                );
        Path outsideFile =
                outsideDirectory.resolve("new.txt");

        try {
            assertThrows(
                    IOException.class,
                    () ->
                            resolver.resolveForWrite(
                                    outsideFile.toString()
                            )
            );
        } finally {
            Files.deleteIfExists(outsideFile);
            Files.deleteIfExists(outsideDirectory);
        }
    }

    /**
     * Given Workspace 外已有文件，when 使用开放读取解析方法，then 返回真实外部路径。
     */
    @Test
    void anywhereExistingResolverAllowsExternalFile()
            throws IOException {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        workspace
                );
        Path outsideFile =
                Files.createTempFile(
                        workspace.getParent(),
                        "resolver-existing-anywhere-",
                        ".txt"
                );

        try {
            assertEquals(
                    outsideFile.toRealPath(),
                    resolver.resolveExistingAnywhere(
                            outsideFile.toString()
                    )
            );
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    /**
     * Given Workspace 外尚不存在的目标，when 使用开放写入解析方法，then 可以返回待写入路径。
     */
    @Test
    void anywhereWriteResolverAllowsExternalNewFile()
            throws IOException {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        workspace
                );
        Path outsideDirectory =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-write-anywhere-"
                );
        Path outsideFile =
                outsideDirectory.resolve("new.txt");

        try {
            assertEquals(
                    outsideFile.toAbsolutePath()
                            .normalize(),
                    resolver.resolveForWriteAnywhere(
                            outsideFile.toString()
                    )
            );
        } finally {
            Files.deleteIfExists(outsideFile);
            Files.deleteIfExists(outsideDirectory);
        }
    }

    /**
     * Given 已解析的 Workspace 内外路径，when 调用分类方法，then 内外结果准确区分。
     */
    @Test
    void classifiesResolvedPathsByWorkspace()
            throws IOException {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        workspace
                );
        Path insideFile =
                workspace.resolve("inside.txt");
        Path outsideFile =
                Files.createTempFile(
                        workspace.getParent(),
                        "resolver-classification-",
                        ".txt"
                );

        try {
            assertTrue(
                    resolver.isInsideWorkspace(
                            resolver.resolveForWriteAnywhere(
                                    insideFile.toString()
                            )
                    )
            );
            assertFalse(
                    resolver.isInsideWorkspace(
                            resolver.resolveExistingAnywhere(
                                    outsideFile.toString()
                            )
                    )
            );
        } finally {
            Files.deleteIfExists(insideFile);
            Files.deleteIfExists(outsideFile);
        }
    }

    /**
     * Given 使用 .. 逃离 Workspace 的路径，when 使用开放写入解析方法，then 应分类为 Workspace 外。
     */
    @Test
    void normalizesParentTraversalBeforeClassification()
            throws IOException {
        WorkspacePathResolver resolver =
                new WorkspacePathResolver(
                        workspace
                );
        Path outsideFile =
                workspace.getParent()
                        .resolve("parent-traversal.txt");

        assertFalse(
                resolver.isInsideWorkspace(
                        resolver.resolveForWriteAnywhere(
                                workspace
                                        .resolve("..")
                                        .resolve("parent-traversal.txt")
                                        .toString()
                        )
                )
        );
        Files.deleteIfExists(outsideFile);
    }
}
