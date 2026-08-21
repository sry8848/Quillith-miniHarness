// 声明 Git 仓库路径解析工具所属的包。
package dev.learn.agent.manual.utils;

// 引入 Git 子进程、文本编码和路径操作能力。
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 使用 Git 自己的目录解析能力发现仓库根目录。
 *
 * <p>工作目录可以是仓库中的任意子目录；Git 会负责向上查找真正的仓库根目录，
 * 避免应用重复实现 .git、工作树和其他 Git 目录形式的判断。</p>
 */
public final class GitRepositoryResolver {

    // 工具类只提供静态入口，不创建实例。
    private GitRepositoryResolver() {}

    /**
     * 从给定工作目录发现 Git 仓库根目录。
     *
     * @param workingDirectory 用户选择的 Agent 工作目录
     * @return Git 仓库根目录的真实路径
     * @throws IOException 工作目录不可访问、Git 命令失败或目录不属于 Git 仓库
     */
    public static Path findRoot(
            Path workingDirectory
    ) throws IOException {
        // [核心] 将用户选择的工作目录转换为真实路径，作为 Git 命令的起点。
        Path cwd =
                workingDirectory.toRealPath();

        // [核心] 启动 Git，让 Git 自己从当前目录向上发现仓库根目录。
        Process gitProcess =
                new ProcessBuilder(
                        "git",
                        "-C",
                        cwd.toString(),
                        "rev-parse",
                        "--show-toplevel"
                )
                        .redirectErrorStream(true)
                        .start();

        // [核心] 读取 Git 的标准输出，取得仓库根目录文本或错误信息。
        String output;

        // [核心] 关闭 Git 输出流，避免读取完成后继续占用子进程资源。
        try (var input = gitProcess.getInputStream()) {
            output =
                    new String(
                            input.readAllBytes(),
                            StandardCharsets.UTF_8
                    )
                            .trim();
        }

        // [核心] 等待 Git 命令结束，确认仓库发现是否成功。
        int exitCode;

        try {
            exitCode =
                    gitProcess.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "等待 Git 根目录发现命令结束时被中断",
                    exception
            );
        }

        // [边界：用户选择的目录不属于 Git 仓库 → 无法为 Git MCP 提供有效仓库目录]
        if (exitCode != 0) {
            throw new IOException(
                    "当前工作目录不属于 Git 仓库："
                            + cwd
                            + "\nGit 输出："
                            + output
            );
        }

        // [边界：Git 返回成功但没有返回路径 → 无法构造仓库根目录]
        if (output.isBlank()) {
            throw new IOException(
                    "Git 未返回仓库根目录："
                            + cwd
            );
        }

        // [核心] 将 Git 返回的路径转换为应用后续使用的真实仓库根目录。
        return Path.of(output).toRealPath();
    }
}
