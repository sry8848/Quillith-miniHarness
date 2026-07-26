package dev.learn.agent.manual.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 为所有文件工具提供统一的工作区路径边界。
 */
public final class WorkspacePathResolver {

    /*
     * 保存工作区的真实路径。
     *
     * toRealPath() 会解析工作区自身包含的符号链接。
     */
    private final Path workspace;

    public WorkspacePathResolver(
            Path workspace
    ) throws IOException {
        this.workspace =
                Objects.requireNonNull(
                                workspace,
                                "Workspace 不能为空"
                        )
                        .toRealPath();

        if (!Files.isDirectory(this.workspace)) {
            throw new IllegalArgumentException(
                    "Workspace 不是目录："
                            + this.workspace
            );
        }
    }

    public Path workspace() {
        return workspace;
    }

    /**
     * 解析必须已经存在的读取目标。
     */
    public Path resolveExisting(
            String userPath
    ) throws IOException {
        Path target =
                resolveLexically(userPath);

        /*
         * toRealPath() 会解析目标路径中的符号链接。
         */
        Path realTarget =
                target.toRealPath();

        ensureInsideWorkspace(
                realTarget,
                userPath
        );

        return realTarget;
    }

    /**
     * 解析允许尚未存在的写入目标。
     */
    public Path resolveForWrite(
            String userPath
    ) throws IOException {
        Path target =
                resolveLexically(userPath);

        /*
         * 已存在的文件必须解析自身的符号链接。
         */
        if (Files.exists(
                target,
                LinkOption.NOFOLLOW_LINKS
        )) {
            Path realTarget =
                    target.toRealPath();

            ensureInsideWorkspace(
                    realTarget,
                    userPath
            );

            return realTarget;
        }

        /*
         * 新文件本身还不存在，因此向上寻找最近的已存在父目录，
         * 再检查这个父目录是否通过符号链接逃离工作区。
         */
        Path existingParent =
                target.getParent();

        while (existingParent != null
                && !Files.exists(
                        existingParent,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            existingParent =
                    existingParent.getParent();
        }

        if (existingParent == null) {
            throw new IOException(
                    "Cannot resolve parent directory: "
                            + userPath
            );
        }

        Path realParent =
                existingParent.toRealPath();

        ensureInsideWorkspace(
                realParent,
                userPath
        );

        Path unresolvedSuffix =
                existingParent.relativize(
                        target
                );

        Path resolvedTarget =
                realParent.resolve(
                                unresolvedSuffix
                        )
                        .normalize();

        ensureInsideWorkspace(
                resolvedTarget,
                userPath
        );

        return resolvedTarget;
    }

    /**
     * 先阻止普通的绝对路径和 .. 路径逃逸。
     */
    private Path resolveLexically(
            String userPath
    ) throws IOException {
        Path target =
                workspace.resolve(userPath)
                        .normalize();

        ensureInsideWorkspace(
                target,
                userPath
        );

        return target;
    }

    private void ensureInsideWorkspace(
            Path target,
            String userPath
    ) throws IOException {
        if (!target.startsWith(workspace)) {
            throw new IOException(
                    "Path escapes workspace: "
                            + userPath
            );
        }
    }
}
