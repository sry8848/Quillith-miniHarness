package dev.learn.agent.manual.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Harbor 单行协议能无损承载完整 instruction。
 */
class HarborRunnerTest {

    /**
     * 验证多行 Unicode instruction 在 Base64 边界后保持原样。
     */
    @Test
    void decodesMultilineUtf8Instruction() {
        String instruction =
                "第一步：检查文件。\n"
                        + "Second line with spaces.\n"
                        + "最后一行。";
        String request =
                "TURN "
                        + Base64.getEncoder()
                        .encodeToString(
                                instruction.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        );

        assertEquals(
                instruction,
                HarborRunner.decodeInstruction(
                        request
                )
        );
    }

    /**
     * 验证第一版拒绝未定义的请求类型。
     */
    @Test
    void rejectsUnknownRequestType() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarborRunner.decodeInstruction(
                                "CLOSE"
                        )
        );
    }

    /**
     * 验证非法 Base64 不会作为普通 instruction 进入 Agent。
     */
    @Test
    void rejectsInvalidBase64() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarborRunner.decodeInstruction(
                                "TURN %%%"
                        )
        );
    }
}
