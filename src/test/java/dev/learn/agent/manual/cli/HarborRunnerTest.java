package dev.learn.agent.manual.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Harbor 单行协议能无损承载普通、固定历史和最终回答请求。
 */
class HarborRunnerTest {

    /** 验证现有 TURN 的多行 Unicode instruction 保持原样。 */
    @Test
    void decodesTurn() {
        String instruction =
                "第一步：检查文件。\n"
                        + "Second line with spaces.\n"
                        + "最后一行。";

        HarborRunner.Request request =
                HarborRunner.decodeRequest(
                        "TURN "
                                + encode(
                                instruction
                        )
                );

        assertEquals(
                HarborRunner.RequestType.TURN,
                request.type()
        );
        assertEquals(
                instruction,
                request.userText()
        );
        assertEquals(
                null,
                request.assistantText()
        );
    }

    /** 验证固定 user/assistant 使用两个独立字段无损传输。 */
    @Test
    void decodesRecordedTurn() {
        String userText = "用户第一行\n用户第二行";
        String assistantText = "助手第一行\n助手第二行";

        HarborRunner.Request request =
                HarborRunner.decodeRequest(
                        "RECORDED_TURN "
                                + encode(userText)
                                + " "
                                + encode(assistantText)
                );

        assertEquals(
                HarborRunner.RequestType.RECORDED_TURN,
                request.type()
        );
        assertEquals(
                userText,
                request.userText()
        );
        assertEquals(
                assistantText,
                request.assistantText()
        );
    }

    /** 验证 Session 控制消息不携带文本。 */
    @Test
    void decodesNewSession() {
        HarborRunner.Request request =
                HarborRunner.decodeRequest(
                        "NEW_SESSION"
                );

        assertEquals(
                HarborRunner.RequestType.NEW_SESSION,
                request.type()
        );
        assertEquals(
                null,
                request.userText()
        );
        assertEquals(
                null,
                request.assistantText()
        );
    }

    /** 验证 Memory 收尾控制消息及其两种明确状态。 */
    @Test
    void decodesMemoryWaitAndEncodesStatus() {
        HarborRunner.Request request =
                HarborRunner.decodeRequest(
                        "WAIT_MEMORY_IDLE"
                );

        // 1. 收尾命令不携带用户文本，也不属于普通 Turn。
        assertEquals(
                HarborRunner.RequestType.WAIT_MEMORY_IDLE,
                request.type()
        );
        assertEquals(
                null,
                request.userText()
        );
        assertEquals(
                "MEMORY_READY",
                HarborRunner.encodeMemoryStatus(0)
        );
        assertEquals(
                "MEMORY_PENDING 2",
                HarborRunner.encodeMemoryStatus(2)
        );
    }

    /** 验证最终问题和多行 Unicode 答案都能无损传输。 */
    @Test
    void decodesAnswerAndEncodesResult() {
        String question = "我之前说了什么？";
        HarborRunner.Request request =
                HarborRunner.decodeRequest(
                        "ANSWER "
                                + encode(question)
                );

        assertEquals(
                HarborRunner.RequestType.ANSWER,
                request.type()
        );
        assertEquals(
                question,
                request.userText()
        );

        String answer = "第一行答案\n第二行答案";
        String encodedResult =
                HarborRunner.encodeResult(
                        answer
                );
        assertEquals(
                answer,
                new String(
                        Base64.getDecoder()
                                .decode(
                                        encodedResult.substring(
                                                "RESULT ".length()
                                        )
                                ),
                        StandardCharsets.UTF_8
                )
        );
    }

    /** 验证缺少 assistant 字段的固定请求直接失败。 */
    @Test
    void rejectsIncompleteRecordedTurn() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarborRunner.decodeRequest(
                                "RECORDED_TURN "
                                        + encode("user")
                        )
        );
    }

    /** 验证第一版拒绝未定义的请求类型。 */
    @Test
    void rejectsUnknownRequestType() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarborRunner.decodeRequest(
                                "CLOSE"
                        )
        );
    }

    /** 验证非法 Base64 不会作为普通文本进入 Agent。 */
    @Test
    void rejectsInvalidBase64() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarborRunner.decodeRequest(
                                "ANSWER %%%"
                        )
        );
    }

    /** 把测试文本编码成协议字段。 */
    private static String encode(
            String text
    ) {
        return Base64.getEncoder()
                .encodeToString(
                        text.getBytes(
                                StandardCharsets.UTF_8
                        )
                );
    }
}
