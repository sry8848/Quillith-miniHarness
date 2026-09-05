package dev.learn.agent.manual.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证记忆命令行开关的默认值和非法输入处理。
 */
class ApplicationOptionsTest {

    /**
     * 验证无子命令时保持现有 Interactive 和 Memory 默认值。
     */
    @Test
    void memoryIsEnabledByDefault() {
        ApplicationOptions options =
                ApplicationOptions.parse(
                        new String[0]
                );

        assertEquals(
                ApplicationOptions.Mode.INTERACTIVE,
                options.mode()
        );
        assertTrue(
                options.memoryEnabled()
        );
    }

    /**
     * 验证 Interactive 继续支持原有记忆关闭参数。
     */
    @Test
    void memoryCanBeDisabledFromCommandLine() {
        assertFalse(
                ApplicationOptions.parse(
                        new String[]{"--memory=off"}
                ).memoryEnabled()
        );
    }

    /**
     * 验证 exec 解析单次任务，并保持 Memory 默认开启。
     */
    @Test
    void parsesExecInstruction() {
        ApplicationOptions options =
                ApplicationOptions.parse(
                        new String[]{
                                "exec",
                                "fix",
                                "the tests"
                        }
                );

        assertEquals(
                ApplicationOptions.Mode.EXEC,
                options.mode()
        );
        assertTrue(
                options.memoryEnabled()
        );
        assertEquals(
                "fix the tests",
                options.instruction()
        );
    }

    /**
     * 验证 exec 支持现有记忆参数，且参数不会进入 instruction。
     */
    @Test
    void execSupportsExistingMemoryOption() {
        ApplicationOptions options =
                ApplicationOptions.parse(
                        new String[]{
                                "exec",
                                "--memory=off",
                                "inspect project"
                        }
                );

        assertFalse(
                options.memoryEnabled()
        );
        assertEquals(
                "inspect project",
                options.instruction()
        );
    }

    /**
     * 验证 exec 不接受缺失或空白任务。
     */
    @Test
    void rejectsMissingExecInstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApplicationOptions.parse(
                                new String[]{"exec"}
                        )
        );
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApplicationOptions.parse(
                                new String[]{"exec", "  "}
                        )
        );
    }

    /**
     * 验证未知参数继续在 CLI 解析边界直接失败。
     */
    @Test
    void rejectsUnknownCommandLineArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApplicationOptions.parse(
                                new String[]{"--memory=maybe"}
                        )
        );
    }

    /**
     * 验证 harbor 子命令进入机器输入模式并保持 Memory 默认开启。
     */
    @Test
    void parsesHarborMode() {
        ApplicationOptions options =
                ApplicationOptions.parse(
                        new String[]{"harbor"}
                );

        assertEquals(
                ApplicationOptions.Mode.HARBOR,
                options.mode()
        );
        assertTrue(
                options.memoryEnabled()
        );
        assertEquals(
                "",
                options.instruction()
        );
    }

    /**
     * 验证 Harbor 入口只复用已有 Memory 参数。
     */
    @Test
    void harborSupportsExistingMemoryOption() {
        ApplicationOptions options =
                ApplicationOptions.parse(
                        new String[]{
                                "harbor",
                                "--memory=off"
                        }
                );

        assertFalse(
                options.memoryEnabled()
        );
    }

    /**
     * 验证 Harbor instruction 不允许通过位置参数传入。
     */
    @Test
    void harborRejectsInstructionArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApplicationOptions.parse(
                                new String[]{
                                        "harbor",
                                        "unexpected"
                                }
                        )
        );
    }
}
