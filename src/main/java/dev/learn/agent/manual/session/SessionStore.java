package dev.learn.agent.manual.session;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite Session 的唯一持久化边界。
 */
public final class SessionStore implements AutoCloseable {

    private static final ObjectMapper JSON = ObjectMappers.jsonMapper();
    private final String jdbcUrl;

    /**
     * 创建并初始化指定 Agent Home 下的 Session 数据库。
     *
     * @param agentHome Agent 自身数据目录
     * @throws IOException 目录或数据库初始化失败
     */
    public SessionStore(
            Path agentHome
    ) throws IOException {
        Objects.requireNonNull(agentHome, "agentHome 不能为空");

        // 1. Session 数据属于 Agent 本身，不进入用户工作区输出目录。
        Path database = agentHome.resolve(".memory").resolve("sessions.db");
        Files.createDirectories(database.getParent());
        jdbcUrl = "jdbc:sqlite:" + database;

        // 2. 建表不创建 Session 行，空内存 Session 不会出现在列表。
        inTransaction(connection -> {
            initializeSchema(connection);
            return null;
        });
    }

    /**
     * 首次正常用户输入时创建 Session 并保存首条消息。
     */
    public synchronized void createSessionWithFirstMessage(
            String sessionId,
            String workspace,
            long turnSeq,
            MessageParam userMessage
    ) {
        inTransaction(connection -> {
            // 1. Session 与首条 committed user message 必须同时出现。
            String now = Instant.now().toString();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sessions(session_id, workspace, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, sessionId);
                statement.setString(2, workspace);
                statement.setString(3, now);
                statement.setString(4, now);
                statement.executeUpdate();
            }
            insertMessages(connection, sessionId, 0, turnSeq, List.of(userMessage));
            return null;
        });
    }

    /**
     * 追加已经正式提交的一批消息。
     */
    public synchronized void appendCommitted(
            String sessionId,
            long turnSeq,
            List<MessageParam> messages
    ) {
        if (messages.isEmpty()) {
            return;
        }

        inTransaction(connection -> {
            // 1. seq 只在完整 history 内递增，Checkpoint 不维护副本序号。
            insertMessages(connection, sessionId, nextSequence(connection, sessionId), turnSeq, messages);
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 保存当前活动上下文的压缩 Checkpoint。
     */
    public synchronized void saveContextCheckpoint(
            String sessionId,
            long throughSeq,
            List<MessageParam> context
    ) {
        inTransaction(connection -> {
            // 1. 一个 Session 只保留最新 Checkpoint，旧 Checkpoint 已被新上下文完全替代。
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO context_checkpoints(session_id, through_seq, context_json) VALUES (?, ?, ?) "
                            + "ON CONFLICT(session_id) DO UPDATE SET through_seq = excluded.through_seq, context_json = excluded.context_json")) {
                statement.setString(1, sessionId);
                statement.setLong(2, throughSeq);
                statement.setString(3, writeJson(context));
                statement.executeUpdate();
            }
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 保存一个完整关闭的 assistant block 列表。
     */
    public synchronized void recordClosedAssistantContent(
            String sessionId,
            List<ContentBlockParam> assistantContent
    ) {
        inTransaction(connection -> {
            // 1. 首个完整块才代表可恢复事实，之前不创建 in-flight 行。
            ensureInflight(connection, sessionId);
            updateAssistantContent(connection, sessionId, assistantContent);
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 原子记录完整工具调用和尚未开始的执行状态。
     */
    public synchronized void recordToolUse(
            String sessionId,
            List<ContentBlockParam> assistantContent,
            String toolUseId
    ) {
        inTransaction(connection -> {
            // 1. 先 durable tool_use 和 NOT_STARTED，随后才允许提交调度器。
            ensureInflight(connection, sessionId);
            updateAssistantContent(connection, sessionId, assistantContent);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tool_executions(session_id, tool_use_id, state, result_json) VALUES (?, ?, ?, NULL)")) {
                statement.setString(1, sessionId);
                statement.setString(2, toolUseId);
                statement.setString(3, ToolExecutionState.NOT_STARTED.name());
                statement.executeUpdate();
            }
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 在真实 ToolRegistry 调用前标记工具副作用可能开始。
     */
    public synchronized void markToolRunning(
            String sessionId,
            String toolUseId
    ) {
        inTransaction(connection -> {
            // 1. 只允许从未开始状态进入运行，状态错乱直接失败。
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE tool_executions SET state = ? WHERE session_id = ? AND tool_use_id = ? AND state = ?")) {
                statement.setString(1, ToolExecutionState.RUNNING.name());
                statement.setString(2, sessionId);
                statement.setString(3, toolUseId);
                statement.setString(4, ToolExecutionState.NOT_STARTED.name());
                requireSingleRow(statement.executeUpdate(), "工具状态不是 NOT_STARTED");
            }
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 持久化最终工具结果。
     */
    public synchronized void completeTool(
            String sessionId,
            String toolUseId,
            ToolExecutionResult result
    ) {
        inTransaction(connection -> {
            // 1. NOT_STARTED 表示未产生真实副作用的拒绝或校验失败，也允许直接完成。
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE tool_executions SET state = ?, result_json = ? WHERE session_id = ? AND tool_use_id = ? "
                            + "AND state IN (?, ?)")) {
                statement.setString(1, ToolExecutionState.COMPLETED.name());
                statement.setString(2, writeJson(result));
                statement.setString(3, sessionId);
                statement.setString(4, toolUseId);
                statement.setString(5, ToolExecutionState.NOT_STARTED.name());
                statement.setString(6, ToolExecutionState.RUNNING.name());
                requireSingleRow(statement.executeUpdate(), "工具状态无法完成");
            }
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 原子提交本轮历史并清理 in-flight 状态。
     */
    public synchronized void commitCompletedTurn(
            String sessionId,
            long turnSeq,
            List<MessageParam> committedMessages
    ) {
        inTransaction(connection -> {
            // 1. assistant/tool_result 先作为完整协议对追加，随后再清理恢复事实。
            insertMessages(connection, sessionId, nextSequence(connection, sessionId), turnSeq, committedMessages);
            try (PreparedStatement deleteTools = connection.prepareStatement(
                    "DELETE FROM tool_executions WHERE session_id = ?");
                 PreparedStatement deleteInflight = connection.prepareStatement(
                         "DELETE FROM inflight_response WHERE session_id = ?")) {
                deleteTools.setString(1, sessionId);
                deleteTools.executeUpdate();
                deleteInflight.setString(1, sessionId);
                deleteInflight.executeUpdate();
            }
            touch(connection, sessionId);
            return null;
        });
    }

    /**
     * 加载 Session、Checkpoint 与尚未封口的模型回复。
     */
    public synchronized LoadedSession loadSession(
            String sessionId,
            String workspace
    ) {
        return inTransaction(connection -> {
            // 1. 先校验身份和 workspace，避免把历史带入不同工作目录。
            String storedWorkspace = readWorkspace(connection, sessionId);
            if (!storedWorkspace.equals(workspace)) {
                throw new IllegalArgumentException("Session 不属于当前 workspace：" + sessionId);
            }

            // 2. 完整历史、Checkpoint 和 in-flight 共同构成可恢复状态。
            return new LoadedSession(
                    readMessages(connection, sessionId),
                    readLatestTurnSeq(connection, sessionId),
                    readCheckpoint(connection, sessionId),
                    readInflight(connection, sessionId)
            );
        });
    }

    /**
     * 返回全部已持久化 Session 的展示摘要。
     */
    public synchronized List<SessionSummary> listSessions() {
        return inTransaction(connection -> {
            List<SessionSummary> summaries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT session_id, updated_at FROM sessions ORDER BY updated_at DESC");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sessionId = rows.getString("session_id");
                    summaries.add(new SessionSummary(
                            sessionId,
                            rows.getString("updated_at"),
                            firstUserSummary(connection, sessionId)
                    ));
                }
            }
            return List.copyOf(summaries);
        });
    }

    /** 关闭 Store；当前实现每次操作单独关闭连接。 */
    @Override
    public void close() {
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sessions (session_id TEXT PRIMARY KEY, workspace TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS messages (session_id TEXT NOT NULL, seq INTEGER NOT NULL, turn_seq INTEGER NOT NULL, message_json TEXT NOT NULL, PRIMARY KEY (session_id, seq), FOREIGN KEY (session_id) REFERENCES sessions(session_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS context_checkpoints (session_id TEXT PRIMARY KEY, through_seq INTEGER NOT NULL, context_json TEXT NOT NULL, FOREIGN KEY (session_id) REFERENCES sessions(session_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS inflight_response (session_id TEXT PRIMARY KEY, assistant_content_json TEXT NOT NULL, FOREIGN KEY (session_id) REFERENCES sessions(session_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS tool_executions (session_id TEXT NOT NULL, tool_use_id TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, PRIMARY KEY (session_id, tool_use_id), FOREIGN KEY (session_id) REFERENCES inflight_response(session_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS sessions_updated_at_idx ON sessions(updated_at DESC)");
        }
    }

    private void ensureInflight(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO inflight_response(session_id, assistant_content_json) VALUES (?, '[]') ON CONFLICT(session_id) DO NOTHING")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    private void updateAssistantContent(Connection connection, String sessionId, List<ContentBlockParam> content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE inflight_response SET assistant_content_json = ? WHERE session_id = ?")) {
            statement.setString(1, writeJson(content));
            statement.setString(2, sessionId);
            requireSingleRow(statement.executeUpdate(), "in-flight 不存在");
        }
    }

    private void insertMessages(Connection connection, String sessionId, long firstSeq, long turnSeq, List<MessageParam> messages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO messages(session_id, seq, turn_seq, message_json) VALUES (?, ?, ?, ?)")) {
            for (int index = 0; index < messages.size(); index++) {
                statement.setString(1, sessionId);
                statement.setLong(2, firstSeq + index);
                statement.setLong(3, turnSeq);
                statement.setString(4, writeJson(messages.get(index)));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private long nextSequence(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq) + 1, 0) FROM messages WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * 返回下一次 submit 应使用的 Turn 序号。
     *
     * @param sessionId 已持久化 Session 的稳定 ID
     * @return 当前最大 Turn 序号加一；没有消息时为 0
     */
    public synchronized long nextTurnSequence(
            String sessionId
    ) {
        return inTransaction(connection -> nextTurnSequence(connection, sessionId));
    }

    private long nextTurnSequence(Connection connection, String sessionId) throws SQLException {
        // 1. 只以同一 Session 的已提交消息计算下一 Turn，避免跨 Session 共享序号。
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(turn_seq) + 1, 0) FROM messages WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void touch(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sessions SET updated_at = ? WHERE session_id = ?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, sessionId);
            requireSingleRow(statement.executeUpdate(), "Session 不存在：" + sessionId);
        }
    }

    private String readWorkspace(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT workspace FROM sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("不存在 Session：" + sessionId);
                }
                return rows.getString(1);
            }
        }
    }

    private List<MessageParam> readMessages(Connection connection, String sessionId) throws SQLException {
        List<MessageParam> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT message_json FROM messages WHERE session_id = ? ORDER BY seq")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    messages.add(readJson(rows.getString(1), MessageParam.class));
                }
            }
        }
        return List.copyOf(messages);
    }

    /**
     * 读取 Session 最后一条已提交消息所属的 Turn 序号。
     *
     * @param connection 当前读取事务的数据库连接
     * @param sessionId 已持久化 Session 的稳定 ID
     * @return 最后一条已提交消息的 Turn 序号
     * @throws SQLException SQL 查询失败
     */
    private long readLatestTurnSeq(Connection connection, String sessionId) throws SQLException {
        // 1. 按全会话 seq 倒序读取，保证恢复封口沿用被中断 submit 的 Turn。
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT turn_seq FROM messages WHERE session_id = ? ORDER BY seq DESC LIMIT 1")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Session 缺少已提交消息：" + sessionId);
                }
                return rows.getLong(1);
            }
        }
    }

    private ContextCheckpoint readCheckpoint(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT through_seq, context_json FROM context_checkpoints WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new ContextCheckpoint(
                        rows.getLong(1),
                        readMessageList(rows.getString(2))
                );
            }
        }
    }

    private InflightResponse readInflight(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT assistant_content_json FROM inflight_response WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new InflightResponse(
                        readContentList(rows.getString(1)),
                        readToolExecutions(connection, sessionId)
                );
            }
        }
    }

    private Map<String, PersistedToolExecution> readToolExecutions(Connection connection, String sessionId) throws SQLException {
        Map<String, PersistedToolExecution> executions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tool_use_id, state, result_json FROM tool_executions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ToolExecutionState state = ToolExecutionState.valueOf(rows.getString(2));
                    String resultJson = rows.getString(3);
                    if (state == ToolExecutionState.COMPLETED && resultJson == null) {
                        throw new IllegalStateException("COMPLETED 工具缺少结果：" + rows.getString(1));
                    }
                    executions.put(rows.getString(1), new PersistedToolExecution(
                            state,
                            resultJson == null ? null : readJson(resultJson, ToolExecutionResult.class)
                    ));
                }
            }
        }
        return Map.copyOf(executions);
    }

    private String firstUserSummary(Connection connection, String sessionId) throws SQLException {
        for (MessageParam message : readMessages(connection, sessionId)) {
            if (!MessageParam.Role.USER.equals(message.role())) {
                continue;
            }
            String text = message.content().isString()
                    ? message.content().asString()
                    : "[结构化消息]";
            String summary = text.replaceAll("\\s+", " ").strip();
            return summary.length() <= 80 ? summary : summary.substring(0, 80) + "…";
        }
        return "";
    }

    private static void requireSingleRow(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Session JSON 序列化失败", exception);
        }
    }

    private static <T> T readJson(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Session JSON 反序列化失败", exception);
        }
    }

    private static List<MessageParam> readMessageList(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<MessageParam>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Session 消息列表反序列化失败", exception);
        }
    }

    private static List<ContentBlockParam> readContentList(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<ContentBlockParam>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Session 内容块列表反序列化失败", exception);
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            connection.setAutoCommit(false);
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new SessionPersistenceException(
                    "Session SQLite 操作失败",
                    new IOException(exception)
            );
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** 已持久化的 Context Checkpoint。 */
    public record ContextCheckpoint(long throughSeq, List<MessageParam> context) {
    }

    /** 已持久化工具的稳定状态和可选最终结果。 */
    public record PersistedToolExecution(ToolExecutionState state, ToolExecutionResult result) {
    }

    /** 尚未正式提交到 history 的完整 assistant 内容与工具状态。 */
    public record InflightResponse(List<ContentBlockParam> assistantContent,
                                   Map<String, PersistedToolExecution> toolExecutions) {
    }

    /** 打开 Session 后交给 AgentSession 的全部持久化事实。 */
    public record LoadedSession(List<MessageParam> messages,
                                long latestTurnSeq,
                                ContextCheckpoint checkpoint,
                                InflightResponse inflight) {
    }

    /** `/session` 展示需要的最小摘要。 */
    public record SessionSummary(String sessionId, String updatedAt, String firstUserMessage) {
    }
}
