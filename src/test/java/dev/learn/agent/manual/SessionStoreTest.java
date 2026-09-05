package dev.learn.agent.manual;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SessionStore 对跨进程恢复事实的持久化和封口语义。
 */
class SessionStoreTest {

    @TempDir
    Path agentHome;

    /**
     * 验证新 Session 可以保存空闲状态和历史。
     *
     * @throws IOException 读写临时 Session 文件失败时抛出
     */
    @Test
    void savesIdleHistory()
            throws IOException {
        String sessionId =
                "11111111-1111-1111-1111-111111111111";
        SessionStore store =
                SessionStore.open(
                        agentHome,
                        sessionId
                );

        store.saveIdle(
                List.of(
                        userMessage(
                                "hello"
                        )
                )
        );

        SessionStore.SessionSnapshot snapshot =
                store.load();

        assertEquals(
                SessionStore.TurnStatus.IDLE,
                snapshot.turnStatus()
        );
        assertEquals(
                "hello",
                snapshot.history()
                        .getFirst()
                        .content()
                        .asString()
        );
    }

    /**
     * 验证 RUNNING 状态恢复时追加 interrupted 提示，并回到 IDLE。
     *
     * @throws IOException 读写临时 Session 文件失败时抛出
     */
    @Test
    void sealsRunningTurnAsInterruptedOnResume()
            throws IOException {
        String sessionId =
                "22222222-2222-2222-2222-222222222222";
        SessionStore store =
                SessionStore.open(
                        agentHome,
                        sessionId
                );
        store.saveRunning(
                List.of(
                        userMessage(
                                "work"
                        )
                )
        );

        SessionStore.ResumeResult result =
                SessionStore.resume(
                        agentHome,
                        sessionId
                );

        assertTrue(
                result.previousTurnInterrupted()
        );
        assertFalse(
                result.hasUnknownTool()
        );
        assertEquals(
                2,
                result.history()
                        .size()
        );
        assertTrue(
                result.history()
                        .getLast()
                        .content()
                        .asString()
                        .contains(
                                "Previous turn was interrupted"
                        )
        );
        assertEquals(
                SessionStore.TurnStatus.IDLE,
                result.sessionStore()
                        .load()
                        .turnStatus()
        );
    }

    /**
     * 验证未配对 tool_use 会在恢复时补成 UNKNOWN tool_result。
     *
     * @throws IOException 读写临时 Session 文件失败时抛出
     */
    @Test
    void convertsPendingToolUseToUnknownResultOnResume()
            throws IOException {
        String sessionId =
                "33333333-3333-3333-3333-333333333333";
        SessionStore store =
                SessionStore.open(
                        agentHome,
                        sessionId
                );
        store.saveRunning(
                List.of(
                        assistantToolUse(
                                "tool-1"
                        )
                )
        );

        SessionStore.ResumeResult result =
                SessionStore.resume(
                        agentHome,
                        sessionId
                );

        assertTrue(
                result.hasUnknownTool()
        );

        ToolResultBlockParam toolResult =
                result.history()
                        .getLast()
                        .content()
                        .asBlockParams()
                        .getFirst()
                        .asToolResult();

        assertEquals(
                "tool-1",
                toolResult.toolUseId()
        );
        assertTrue(
                toolResult.isError()
                        .orElse(false)
        );
        assertTrue(
                toolResult.content()
                        .orElseThrow()
                        .asString()
                        .contains(
                                "UNKNOWN"
                        )
        );
    }

    /**
     * 验证正常结束的 Session 恢复时不追加中断提示。
     *
     * @throws IOException 读写临时 Session 文件失败时抛出
     */
    @Test
    void idleResumeDoesNotAddInterruptionMarker()
            throws IOException {
        String sessionId =
                "44444444-4444-4444-4444-444444444444";
        SessionStore store =
                SessionStore.open(
                        agentHome,
                        sessionId
                );
        store.saveIdle(
                List.of(
                        userMessage(
                                "done"
                        )
                )
        );

        SessionStore.ResumeResult result =
                SessionStore.resume(
                        agentHome,
                        sessionId
                );

        assertFalse(
                result.previousTurnInterrupted()
        );
        assertEquals(
                1,
                result.history()
                        .size()
        );
    }

    /**
     * 验证非法 Session ID 在入口边界直接失败。
     */
    @Test
    void rejectsInvalidSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionStore.open(
                                agentHome,
                                ".."
                        )
        );
    }

    private static MessageParam userMessage(
            String text
    ) {
        return MessageParam.builder()
                .role(
                        MessageParam.Role.USER
                )
                .content(
                        text
                )
                .build();
    }

    private static MessageParam assistantToolUse(
            String toolUseId
    ) {
        return MessageParam.builder()
                .role(
                        MessageParam.Role.ASSISTANT
                )
                .contentOfBlockParams(
                        List.of(
                                ContentBlockParam.ofToolUse(
                                        ToolUseBlockParam.builder()
                                                .id(
                                                        toolUseId
                                                )
                                                .name(
                                                        "test_tool"
                                                )
                                                .input(
                                                        ToolUseBlockParam.Input.builder()
                                                                .build()
                                                )
                                                .build()
                                )
                        )
                )
                .build();
    }
}
