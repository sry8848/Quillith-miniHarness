package dev.learn.agent.manual.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证记忆命令行开关的默认值和非法输入处理。
 */
class ApplicationOptionsTest {

    @Test
    void memoryIsEnabledByDefault() {
        assertTrue(
                ApplicationOptions.parse(
                        new String[0]
                ).memoryEnabled()
        );
    }

    @Test
    void memoryCanBeDisabledFromCommandLine() {
        assertFalse(
                ApplicationOptions.parse(
                        new String[]{"--memory=off"}
                ).memoryEnabled()
        );
    }

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
}
