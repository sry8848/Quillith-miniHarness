package dev.learn.agent.manual;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 持久化一个 AgentSession 可恢复的历史事实和当前 Turn 状态。
 */
public final class SessionStore {

    private static final JsonMapper JSON =
            ObjectMappers.jsonMapper();

    private static final String STATE_FILE =
            "state.json";

    private static final String HISTORY_FILE =
            "history.jsonl";

    private static final String INTERRUPTED_MESSAGE =
            "Previous turn was interrupted. Session recovery does not "
                    + "continue the old turn automatically. The next user "
                    + "message starts a new turn.";

    private static final String UNKNOWN_TOOL_MESSAGE =
            "UNKNOWN: this tool execution started before the previous "
                    + "process stopped, but its final outcome is unknown. "
                    + "Do not assume success or failure, and inspect the "
                    + "current real state before deciding whether to retry.";

    private final Path sessionDirectory;
    private final String sessionId;
    private final boolean enabled;

    /**
     * 创建真实或禁用的 SessionStore。
     *
     * @param sessionDirectory 当前 Session 的存储目录
     * @param sessionId 当前 Session ID
     * @param enabled false 时所有写入为 no-op
     */
    private SessionStore(
            Path sessionDirectory,
            String sessionId,
            boolean enabled
    ) {
        this.sessionDirectory = sessionDirectory;
        this.sessionId = sessionId;
        this.enabled = enabled;
    }

    /**
     * 创建测试和子 Agent 使用的禁用 Store。
     *
     * @return 不读写磁盘的 Store
     */
    public static SessionStore disabled() {
        return new SessionStore(
                null,
                "",
                false
        );
    }

    /**
     * 打开或创建一个指定 Session 的存储目录。
     *
     * @param agentHome Agent 自身数据目录
     * @param sessionId 当前 Session ID
     * @return 可读写该 Session 文件的 Store
     * @throws IOException 创建目录失败时抛出
     */
    public static SessionStore open(
            Path agentHome,
            String sessionId
    ) throws IOException {
        String normalizedSessionId =
                normalizeSessionId(
                        sessionId
                );
        Path sessionDirectory =
                Objects.requireNonNull(
                                agentHome,
                                "agentHome 不能为空"
                        )
                        .resolve(
                                ".sessions"
                        )
                        .resolve(
                                normalizedSessionId
                        );

        Files.createDirectories(
                sessionDirectory
        );

        return new SessionStore(
                sessionDirectory,
                normalizedSessionId,
                true
        );
    }

    /**
     * 读取旧 Session，并在发现未完成 Turn 时把它封口为可继续对话的历史。
     *
     * @param agentHome Agent 自身数据目录
     * @param sessionId 要恢复的 Session ID
     * @return 恢复后的历史、Store 和用户可见状态
     * @throws IOException Session 文件不存在或读取失败时抛出
     */
    public static ResumeResult resume(
            Path agentHome,
            String sessionId
    ) throws IOException {
        SessionStore store =
                open(
                        agentHome,
                        sessionId
                );
        SessionSnapshot snapshot =
                store.load();
        List<MessageParam> history =
                new ArrayList<>(
                        snapshot.history()
                );
        boolean previousTurnInterrupted =
                snapshot.turnStatus()
                        == TurnStatus.RUNNING;

        if (!previousTurnInterrupted) {
            return new ResumeResult(
                    store,
                    List.copyOf(
                            history
                    ),
                    false,
                    false
            );
        }

        boolean hasUnknownTool =
                appendInterruptedMarker(
                        history
                );
        store.saveIdle(
                history
        );

        return new ResumeResult(
                store,
                List.copyOf(
                        history
                ),
                true,
                hasUnknownTool
        );
    }

    /**
     * 读取当前 Store 保存的历史和 Turn 状态。
     *
     * @return 当前磁盘快照
     * @throws IOException 文件不存在、反序列化失败或内容无效时抛出
     */
    public SessionSnapshot load() throws IOException {
        ensureEnabled();

        Path statePath =
                statePath();
        Path historyPath =
                historyPath();

        if (!Files.exists(statePath)
                || !Files.exists(historyPath)) {
            throw new IOException(
                    "Session 文件不存在："
                            + sessionId
            );
        }

        TurnStatus turnStatus =
                TurnStatus.valueOf(
                        JSON.readTree(
                                        statePath.toFile()
                                )
                                .path(
                                        "turnStatus"
                                )
                                .asText()
                );

        List<MessageParam> history =
                new ArrayList<>();
        for (String line : Files.readAllLines(
                historyPath,
                StandardCharsets.UTF_8
        )) {
            if (line.isBlank()) {
                continue;
            }
            MessageParam message =
                    JSON.readValue(
                            line,
                            MessageParam.class
                    );
            history.add(
                    message.validate()
            );
        }

        return new SessionSnapshot(
                List.copyOf(
                        history
                ),
                turnStatus
        );
    }

    /**
     * 保存当前历史，并标记当前 Turn 正在执行。
     *
     * @param history 可恢复的主会话历史
     */
    public void saveRunning(
            List<MessageParam> history
    ) {
        if (!enabled) {
            return;
        }

        // 1. 先写 RUNNING，避免崩溃后把已开始 Turn 误判成正常空闲。
        writeUnchecked(
                TurnStatus.RUNNING,
                history,
                true
        );
    }

    /**
     * 保存当前历史，并标记当前没有未完成 Turn。
     *
     * @param history 可恢复的主会话历史
     */
    public void saveIdle(
            List<MessageParam> history
    ) {
        if (!enabled) {
            return;
        }

        // 1. 先写完整历史，最后再把状态切回 IDLE。
        writeUnchecked(
                TurnStatus.IDLE,
                history,
                false
        );
    }

    /**
     * 返回当前 Store 是否真实写入磁盘。
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 校验并标准化 Session ID。
     */
    public static String normalizeSessionId(
            String sessionId
    ) {
        return UUID.fromString(
                Objects.requireNonNull(
                        sessionId,
                        "sessionId 不能为空"
                )
        ).toString();
    }

    private static boolean appendInterruptedMarker(
            List<MessageParam> history
    ) {
        List<String> pendingToolUseIds =
                pendingToolUseIds(
                        history
                );

        if (pendingToolUseIds.isEmpty()) {
            history.add(
                    MessageParam.builder()
                            .role(
                                    MessageParam.Role.USER
                            )
                            .content(
                                    INTERRUPTED_MESSAGE
                            )
                            .build()
            );
            return false;
        }

        List<ContentBlockParam> userContent =
                new ArrayList<>();
        for (String toolUseId : pendingToolUseIds) {
            userContent.add(
                    ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(
                                            toolUseId
                                    )
                                    .content(
                                            UNKNOWN_TOOL_MESSAGE
                                    )
                                    .isError(
                                            true
                                    )
                                    .build()
                    )
            );
        }
        userContent.add(
                ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text(
                                        INTERRUPTED_MESSAGE
                                )
                                .build()
                )
        );

        history.add(
                MessageParam.builder()
                        .role(
                                MessageParam.Role.USER
                        )
                        .contentOfBlockParams(
                                userContent
                        )
                        .build()
        );

        return true;
    }

    private static List<String> pendingToolUseIds(
            List<MessageParam> history
    ) {
        if (history.isEmpty()) {
            return List.of();
        }

        MessageParam lastMessage =
                history.getLast();
        if (!MessageParam.Role.ASSISTANT.equals(
                lastMessage.role()
        ) || !lastMessage.content()
                .isBlockParams()) {
            return List.of();
        }

        return lastMessage.content()
                .asBlockParams()
                .stream()
                .filter(
                        ContentBlockParam::isToolUse
                )
                .map(
                        block -> block.asToolUse()
                                .id()
                )
                .toList();
    }

    private void writeUnchecked(
            TurnStatus turnStatus,
            List<MessageParam> history,
            boolean writeStateFirst
    ) {
        try {
            write(
                    turnStatus,
                    history,
                    writeStateFirst
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "无法持久化 Session："
                            + sessionId,
                    exception
            );
        }
    }

    private void write(
            TurnStatus turnStatus,
            List<MessageParam> history,
            boolean writeStateFirst
    ) throws IOException {
        Objects.requireNonNull(
                history,
                "消息历史不能为空"
        );

        if (writeStateFirst) {
            writeState(
                    turnStatus
            );
            writeHistory(
                    history
            );
            return;
        }

        writeHistory(
                history
        );
        writeState(
                turnStatus
        );
    }

    private void writeState(
            TurnStatus turnStatus
    ) throws IOException {
        writeAtomically(
                statePath(),
                "{\"sessionId\":\""
                        + sessionId
                        + "\",\"turnStatus\":\""
                        + turnStatus.name()
                        + "\"}"
        );
    }

    private void writeHistory(
            List<MessageParam> history
    ) throws IOException {
        Path target =
                historyPath();
        Path temporary =
                temporaryPath(
                        target
                );

        try (BufferedWriter writer =
                     Files.newBufferedWriter(
                             temporary,
                             StandardCharsets.UTF_8,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE
                     )) {
            for (MessageParam message : history) {
                writer.write(
                        JSON.writeValueAsString(
                                message
                        )
                );
                writer.newLine();
            }
        }

        Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    private void writeAtomically(
            Path target,
            String content
    ) throws IOException {
        Path temporary =
                temporaryPath(
                        target
                );
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    private Path temporaryPath(
            Path target
    ) {
        return target.resolveSibling(
                target.getFileName()
                        + ".tmp"
        );
    }

    private Path statePath() {
        return sessionDirectory.resolve(
                STATE_FILE
        );
    }

    private Path historyPath() {
        return sessionDirectory.resolve(
                HISTORY_FILE
        );
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException(
                    "禁用的 SessionStore 不能读取"
            );
        }
    }

    /**
     * 当前持久化 Turn 状态。
     */
    public enum TurnStatus {
        IDLE,
        RUNNING
    }

    /**
     * 当前磁盘中的 Session 快照。
     *
     * @param history 可恢复的消息历史
     * @param turnStatus 当前 Turn 状态
     */
    public record SessionSnapshot(
            List<MessageParam> history,
            TurnStatus turnStatus
    ) {
    }

    /**
     * Session 恢复结果。
     *
     * @param sessionStore 后续继续写入同一 Session 的 Store
     * @param history 已封口的可继续对话历史
     * @param previousTurnInterrupted 上一 Turn 是否被恢复流程封口
     * @param hasUnknownTool 是否追加过 UNKNOWN 工具结果
     */
    public record ResumeResult(
            SessionStore sessionStore,
            List<MessageParam> history,
            boolean previousTurnInterrupted,
            boolean hasUnknownTool
    ) {
    }
}
