package dev.learn.agent.manual.utils;

import dev.learn.agent.manual.AgentState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 以 Workspace 为相对路径基准，并按 AgentState.allowedRoots() 约束文件工具的真实路径。
 *
 * <p>这里不是操作系统沙盒。它只负责路径解析和 allowed-roots 边界分类；Bash 等宿主进程
 * 工具仍由各自的执行环境负责边界。</p>
 */
public final class WorkspacePathResolver {

    /* Session 创建阶段已经确定路径集合，解析器只持有共享状态，不复制自己的边界配置。 */
    private final AgentState agentState;

    /**
     * 创建共享路径解析器。
     *
     * @param agentState 当前 CLI Session 的路径状态
     * @throws IOException workspace 或 allowed root 不是可用目录
     */
    public WorkspacePathResolver(
            AgentState agentState
    ) throws IOException {
        this.agentState =
                Objects.requireNonNull(
                        agentState,
                        "AgentState 不能为空"
                );

        /*
         * 这些路径应在 Session 装配阶段已经转成真实绝对目录；这里仅验证装配结果可用，
         * 不检查集合长度，也不把多根配置收窄成单根配置。
         */
        if (!Files.isDirectory(agentState.workspace())) {
            throw new IllegalArgumentException(
                    "Workspace 不是目录："
                            + agentState.workspace()
            );
        }

        for (Path allowedRoot : agentState.allowedRoots()) {
            if (!Files.isDirectory(allowedRoot)) {
                throw new IllegalArgumentException(
                        "Allowed root 不是目录："
                                + allowedRoot
                );
            }
        }
    }

    /**
     * 返回相对路径解析基准 Workspace。
     */
    public Path workspace() {
        return agentState.workspace();
    }

    /**
     * 返回当前 Session 的可访问目录集合。
     */
    public List<Path> allowedRoots() {
        return agentState.allowedRoots();
    }

    /**
     * 解析已经存在且位于任一 allowed root 内的目标。
     *
     * @param userPath 以 workspace 为相对基准的路径，或绝对路径
     * @return 解析符号链接后的真实目标
     * @throws IOException 目标不存在或不在 allowed roots 内
     */
    public Path resolveExisting(
            String userPath
    ) throws IOException {
        Path target =
                resolveLexically(
                        userPath
                );
        /* 先解析真实目标，再做边界检查，以支持 allowed root 外的链接指回允许目录。 */
        Path realTarget =
                target.toRealPath();
        ensureInsideAllowedRoots(
                realTarget,
                userPath
        );
        return realTarget;
    }

    /**
     * 解析允许尚未存在且位于任一 allowed root 内的写入目标。
     *
     * @param userPath 以 workspace 为相对基准的路径，或绝对路径
     * @return 可写入的真实目标路径
     * @throws IOException 目标父目录不存在或目标不在 allowed roots 内
     */
    public Path resolveForWrite(
            String userPath
    ) throws IOException {
        Path target =
                resolveLexically(
                        userPath
                );

        /* 已存在的文件必须解析自身的符号链接。 */
        if (Files.exists(
                target,
                LinkOption.NOFOLLOW_LINKS
        )) {
            Path realTarget =
                    target.toRealPath();
            ensureInsideAllowedRoots(
                    realTarget,
                    userPath
            );
            return realTarget;
        }

        /*
         * 新文件本身还不存在，因此向上寻找最近的已存在父目录，再检查父目录的真实位置。
         * 这样 workspace 内的目录链接无法把新文件写到 allowed roots 外。
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
        ensureInsideAllowedRoots(
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
        ensureInsideAllowedRoots(
                resolvedTarget,
                userPath
        );
        return resolvedTarget;
    }

    /**
     * 判断解析后的目标是否位于当前 Workspace 内。
     *
     * @param target 已经解析的目标路径
     * @return 目标是否位于 workspace 内
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
                .startsWith(
                        workspace()
                                .toAbsolutePath()
                                .normalize()
                );
    }

    /**
     * 判断解析后的目标是否位于任一 allowed root 内。
     *
     * @param target 已经解析的目标路径
     * @return 目标是否位于允许目录集合内
     */
    public boolean isInsideAllowedRoots(
            Path target
    ) {
        Objects.requireNonNull(
                target,
                "目标路径不能为空"
        );

        Path normalizedTarget =
                target.toAbsolutePath()
                        .normalize();
        return allowedRoots().stream()
                .map(
                        root -> root.toAbsolutePath()
                                .normalize()
                )
                .anyMatch(
                        normalizedTarget::startsWith
                );
    }

    /**
     * 以 Workspace 为相对路径基准进行规范化，不在此阶段执行真实路径边界判断。
     */
    private Path resolveLexically(
            String userPath
    ) {
        return workspace()
                .resolve(
                        Objects.requireNonNull(
                                userPath,
                                "路径不能为空"
                        )
                )
                .normalize();
    }

    private void ensureInsideAllowedRoots(
            Path target,
            String userPath
    ) throws IOException {
        if (!isInsideAllowedRoots(target)) {
            throw new IOException(
                    "Path escapes allowed roots: "
                            + userPath
            );
        }
    }
}
