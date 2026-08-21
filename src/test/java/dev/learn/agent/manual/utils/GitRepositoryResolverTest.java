// 声明 Git 仓库解析测试所属的包。
package dev.learn.agent.manual.utils;

// 引入 JUnit 测试注解和临时目录能力。
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 引入测试使用的路径和异常类型。
import java.io.IOException;
import java.nio.file.Path;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Git 工作目录与仓库根目录的自动发现契约。
 */
class GitRepositoryResolverTest {

    // [边界：测试目录不属于 Git 仓库 → 解析器必须明确失败]
    @TempDir
    Path temporaryDirectory;

    /**
     * 从当前项目的嵌套目录发现项目仓库根目录。
     *
     * @throws IOException 目录解析或 Git 命令执行失败
     */
    @Test
    void findsRepositoryRootFromNestedDirectory()
            throws IOException {
        // 取得 Maven 模块目录，模拟用户从项目子目录启动 Agent。
        Path moduleDirectory =
                Path.of("")
                        .toRealPath();

        // 选择模块内部的 Java 源码目录作为 Git 根目录发现起点。
        Path nestedDirectory =
                moduleDirectory.resolve(
                        "src/main/java"
                );

        // 根据当前项目固定目录结构计算期望的仓库根目录。
        Path expectedRoot =
                moduleDirectory
                        .getParent()
                        .getParent()
                        .toRealPath();

        // 验证 Git 能从嵌套目录返回项目的真实仓库根目录。
        assertEquals(
                expectedRoot,
                GitRepositoryResolver.findRoot(
                        nestedDirectory
                )
        );
    }

    /**
     * 不属于 Git 仓库的工作目录必须尽早失败，不能让 MCP 客户端等待超时。
     */
    @Test
    void rejectsDirectoryOutsideGitRepository() {
        // 使用临时目录模拟用户选择了一个不属于 Git 仓库的工作目录。
        assertThrows(
                IOException.class,
                () ->
                        GitRepositoryResolver.findRoot(
                                temporaryDirectory
                        )
        );
    }
}
