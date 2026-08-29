package dev.learn.agent.manual.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 以 Workspace 为相对路径基准，统一处理路径规范化和符号链接解析。
 *
 * <p>resolveExisting 和 resolveForWrite 保留给应用内部数据，继续强制
 * Workspace 边界；文件工具使用 Anywhere 方法，由上层审批策略负责判断
 * Workspace 内外。</p>
 *
 * <p>这里不是操作系统沙盒。它只负责路径解析和边界分类。</p>
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
     * 解析必须已经存在且位于 Workspace 内的目标。
     */
    public Path resolveExisting(
            String userPath
    ) throws IOException {
        return resolveExisting(
                userPath,
                true
        );
    }

    /**
     * 解析必须已经存在的目标，允许目标位于 Workspace 外。
     *
     * <p>相对路径仍然以 Workspace 为基准，目标中的符号链接会被解析为
     * 真实路径，调用方可以再使用 isInsideWorkspace() 进行分类。</p>
     */
    public Path resolveExistingAnywhere(
            String userPath
    ) throws IOException {
        return resolveExisting(
                userPath,
                false
        );
    }

    /**
     * 按指定边界策略解析已经存在的目标。
     */
    private Path resolveExisting(
            String userPath,
            boolean enforceWorkspace
    ) throws IOException {
        Path target =
                resolveLexically(
                        userPath,
                        enforceWorkspace
                );

        /*
         * toRealPath() 会解析目标路径中的符号链接。
         */
        Path realTarget =
                target.toRealPath();

        if (enforceWorkspace) {
            ensureInsideWorkspace(
                    realTarget,
                    userPath
            );
        }

        return realTarget;
    }

    /**
     * 解析允许尚未存在且位于 Workspace 内的写入目标。
     */
    public Path resolveForWrite(
            String userPath
    ) throws IOException {
        return resolveForWrite(
                userPath,
                true
        );
    }

    /**
     * 解析允许尚未存在的写入目标，允许目标位于 Workspace 外。
     *
     * <p>该方法仍然解析已存在目标和最近的真实父目录，
     * 只是不会因为 Workspace 外路径直接失败。</p>
     */
    public Path resolveForWriteAnywhere(
            String userPath
    ) throws IOException {
        return resolveForWrite(
                userPath,
                false
        );
    }

    /**
     * 按指定边界策略解析写入目标。
     */
    private Path resolveForWrite(
            String userPath,
            boolean enforceWorkspace
    ) throws IOException {
        Path target =
                resolveLexically(
                        userPath,
                        enforceWorkspace
                );

        /*
         * 已存在的文件必须解析自身的符号链接。
         */
        if (Files.exists(
                target,
                LinkOption.NOFOLLOW_LINKS
        )) {
            Path realTarget =
                    target.toRealPath();

            if (enforceWorkspace) {
                ensureInsideWorkspace(
                        realTarget,
                        userPath
                );
            }

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

        if (enforceWorkspace) {
            ensureInsideWorkspace(
                    realParent,
                    userPath
            );
        }

        Path unresolvedSuffix =
                existingParent.relativize(
                        target
                );

        Path resolvedTarget =
                realParent.resolve(
                                unresolvedSuffix
                        )
                        .normalize();

        if (enforceWorkspace) {
            ensureInsideWorkspace(
                    resolvedTarget,
                    userPath
            );
        }

        return resolvedTarget;
    }

    /**
     * 判断解析后的目标是否位于当前 Workspace 内。
     *
     * <p>调用方应传入本类解析方法返回的绝对路径；解析方法已经处理了
     * 已存在目标和父目录中的符号链接。</p>
     */
    public boolean isInsideWorkspace(
            Path target
    ) {
        Objects.requireNonNull(
                target,
                "目标路径不能为空"
        );

        return target.toAbsolutePath()
                .normalize()
                .startsWith(workspace);
    }

    /**
     * 以 Workspace 为相对路径基准进行规范化，按需执行 Workspace 边界检查。
     */
    private Path resolveLexically(
            String userPath,
            boolean enforceWorkspace
    ) throws IOException {
        Path target =
                workspace.resolve(userPath)
                        .normalize();

        if (enforceWorkspace) {
            ensureInsideWorkspace(
                    target,
                    userPath
            );
        }

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
