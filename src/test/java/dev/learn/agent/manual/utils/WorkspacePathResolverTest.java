package dev.learn.agent.manual.utils;

import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 workspace 相对基准、allowedRoots 多根边界和符号链接解析。
 */
class WorkspacePathResolverTest {

    @TempDir
    Path workspace;

    /** 相对路径始终以 workspace 为基准。 */
    @Test
    void relativePathsUseWorkspaceAsBase()
            throws IOException {
        Path allowedRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-relative-root-"
                );
        Path insideFile =
                workspace.resolve("inside.txt");
        Files.writeString(
                insideFile,
                "inside"
        );

        try {
            WorkspacePathResolver resolver =
                    resolver(workspace, allowedRoot);

            assertEquals(
                    insideFile.toRealPath(),
                    resolver.resolveExisting("inside.txt")
            );
        } finally {
            Files.deleteIfExists(insideFile);
            Files.deleteIfExists(allowedRoot);
        }
    }

    /** 第二个 allowed root 中的已有文件和新文件都可解析。 */
    @Test
    void allowsExistingAndNewFileInSecondRoot()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-second-root-"
                );
        Path existingFile =
                secondRoot.resolve("existing.txt");
        Path newFile =
                secondRoot.resolve("new").resolve("file.txt");
        Files.writeString(
                existingFile,
                "existing"
        );

        try {
            WorkspacePathResolver resolver =
                    resolver(workspace, secondRoot);

            assertEquals(
                    existingFile.toRealPath(),
                    resolver.resolveExisting(
                            existingFile.toString()
                    )
            );
            assertEquals(
                    newFile.toAbsolutePath()
                            .normalize(),
                    resolver.resolveForWrite(
                            newFile.toString()
                    )
            );
        } finally {
            Files.deleteIfExists(newFile);
            Files.deleteIfExists(newFile.getParent());
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(secondRoot);
        }
    }

    /** 第三个未授权目录中的读写目标都必须被解析器拒绝。 */
    @Test
    void rejectsTargetsOutsideAllAllowedRoots()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-allowed-root-"
                );
        Path forbiddenRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-forbidden-root-"
                );
        Path existingFile =
                forbiddenRoot.resolve("existing.txt");
        Path newFile =
                forbiddenRoot.resolve("new.txt");
        Files.writeString(
                existingFile,
                "forbidden"
        );

        try {
            WorkspacePathResolver resolver =
                    resolver(workspace, secondRoot);

            assertThrows(
                    IOException.class,
                    () -> resolver.resolveExisting(
                            existingFile.toString()
                    )
            );
            assertThrows(
                    IOException.class,
                    () -> resolver.resolveForWrite(
                            newFile.toString()
                    )
            );
        } finally {
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(newFile);
            Files.deleteIfExists(forbiddenRoot);
            Files.deleteIfExists(secondRoot);
        }
    }

    /** .. 逃逸到未授权目录时，真实目标检查仍然拒绝访问。 */
    @Test
    void rejectsParentTraversalOutsideAllowedRoots()
            throws IOException {
        Path forbiddenRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-parent-forbidden-"
                );
        Path forbiddenFile =
                forbiddenRoot.resolve("parent.txt");
        Files.writeString(
                forbiddenFile,
                "forbidden"
        );

        try {
            WorkspacePathResolver resolver =
                    resolver(workspace);
            String escapedPath =
                    workspace.resolve("..")
                            .resolve(forbiddenRoot.getFileName())
                            .resolve("parent.txt")
                            .toString();

            assertThrows(
                    IOException.class,
                    () -> resolver.resolveExisting(escapedPath)
            );
        } finally {
            Files.deleteIfExists(forbiddenFile);
            Files.deleteIfExists(forbiddenRoot);
        }
    }

    /** allowed root 内的目录链接指向未授权目录时，已有目标和新写入都拒绝。 */
    @Test
    void rejectsSymlinkInsideAllowedRootPointingOutside()
            throws IOException {
        Path forbiddenRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-link-forbidden-"
                );
        Path forbiddenFile =
                forbiddenRoot.resolve("target.txt");
        Files.writeString(
                forbiddenFile,
                "forbidden"
        );
        Path link =
                workspace.resolve("outside-link");

        try {
            createSymlinkOrSkip(
                    link,
                    forbiddenRoot
            );
            WorkspacePathResolver resolver =
                    resolver(workspace);

            assertThrows(
                    IOException.class,
                    () -> resolver.resolveExisting(
                            link.resolve("target.txt").toString()
                    )
            );
            assertThrows(
                    IOException.class,
                    () -> resolver.resolveForWrite(
                            link.resolve("new.txt").toString()
                    )
            );
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(forbiddenFile);
            Files.deleteIfExists(forbiddenRoot);
        }
    }

    /** allowed root 外的目录链接指回允许目录时，按真实目标允许访问。 */
    @Test
    void allowsSymlinkOutsideAllowedRootPointingInside()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-link-allowed-"
                );
        Path externalContainer =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-link-container-"
                );
        Path link =
                externalContainer.resolve("allowed-link");
        Path existingFile =
                secondRoot.resolve("target.txt");
        Files.writeString(
                existingFile,
                "allowed"
        );

        try {
            createSymlinkOrSkip(
                    link,
                    secondRoot
            );
            WorkspacePathResolver resolver =
                    resolver(workspace, secondRoot);

            assertEquals(
                    existingFile.toRealPath(),
                    resolver.resolveExisting(
                            link.resolve("target.txt").toString()
                    )
            );
            assertEquals(
                    secondRoot.resolve("new.txt")
                            .toAbsolutePath()
                            .normalize(),
                    resolver.resolveForWrite(
                            link.resolve("new.txt").toString()
                    )
            );
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(secondRoot);
            Files.deleteIfExists(externalContainer);
        }
    }

    /** 重叠 roots 不需要最小化，任一 root 命中即可。 */
    @Test
    void overlappingRootsDoNotChangeResolution()
            throws IOException {
        Path nestedRoot =
                Files.createDirectories(
                        workspace.resolve("nested")
                );
        Path file =
                nestedRoot.resolve("file.txt");
        Files.writeString(
                file,
                "nested"
        );

        WorkspacePathResolver resolver =
                resolver(
                        workspace,
                        nestedRoot,
                        workspace
                );

        assertEquals(
                file.toRealPath(),
                resolver.resolveExisting(
                        file.toString()
                )
        );
    }

    /** workspace 只是分类基准，第二个 allowed root 不是 workspace。 */
    @Test
    void classifiesWorkspaceSeparatelyFromOtherAllowedRoots()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-classification-root-"
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
            WorkspacePathResolver resolver =
                    resolver(workspace, secondRoot);
            Path resolvedWorkspaceFile =
                    resolver.resolveExisting(
                            workspaceFile.toString()
                    );
            Path resolvedSecondFile =
                    resolver.resolveExisting(
                            secondFile.toString()
                    );

            assertTrue(
                    resolver.isInsideWorkspace(
                            resolvedWorkspaceFile
                    )
            );
            assertFalse(
                    resolver.isInsideWorkspace(
                            resolvedSecondFile
                    )
            );
            assertTrue(
                    resolver.isInsideAllowedRoots(
                            resolvedSecondFile
                    )
            );
        } finally {
            Files.deleteIfExists(workspaceFile);
            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(secondRoot);
        }
    }

    /** 解析器公开完整 roots 集合，不把它折叠成单目录。 */
    @Test
    void exposesAllAllowedRoots()
            throws IOException {
        Path secondRoot =
                Files.createTempDirectory(
                        workspace.getParent(),
                        "resolver-roots-"
                );

        try {
            WorkspacePathResolver resolver =
                    resolver(workspace, secondRoot);

            assertEquals(
                    List.of(workspace, secondRoot),
                    resolver.allowedRoots()
            );
        } finally {
            Files.deleteIfExists(secondRoot);
        }
    }

    /** 创建使用指定 roots 的 Session 状态。 */
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

    /** Windows 没有创建符号链接权限时跳过链接专用测试。 */
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
