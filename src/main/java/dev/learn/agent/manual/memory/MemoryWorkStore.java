package dev.learn.agent.manual.memory;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 记忆后台工作的 SQLite 持久化边界。 */
public final class MemoryWorkStore {

    private static final ObjectMapper JSON = ObjectMappers.jsonMapper();
    private static final TypeReference<List<MessageParam>> MESSAGE_LIST = new TypeReference<>() { };
    private final String jdbcUrl;

    /**
     * 创建记忆工作库。
     *
     * @param agentHome Agent 自身的数据目录
     * @throws IOException 初始化目录或数据库失败
     */
    public MemoryWorkStore(Path agentHome) throws IOException {
        Objects.requireNonNull(agentHome, "agentHome 不能为空");
        Path database = agentHome.resolve(".memory").resolve("memory-work.db");
        Files.createDirectories(database.getParent());
        jdbcUrl = "jdbc:sqlite:" + database;
        inTransaction(connection -> {
            initializeSchema(connection);
            return null;
        });
    }

    /** 将尚未提取的工作写入队列。 */
    public synchronized void enqueue(MemoryExtractionTask task) {
        Objects.requireNonNull(task, "task 不能为空");
        inTransaction(connection -> {
            // 1. 两个上下文一起提交，后台重启后仍使用同一次 Turn 的快照。
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO memory_tasks(task_id, turn_context_json, model_context_json) VALUES (?, ?, ?)")) {
                statement.setString(1, task.taskId());
                statement.setString(2, writeJson(task.turnContext()));
                statement.setString(3, writeJson(task.modelContext()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** 读取当前未完成工作，保持入队顺序。 */
    public synchronized List<MemoryExtractionTask> pendingTasks() {
        return inTransaction(connection -> {
            List<MemoryExtractionTask> tasks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT task_id, turn_context_json, model_context_json FROM memory_tasks ORDER BY rowid");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    tasks.add(new MemoryExtractionTask(rows.getString(1),
                            readMessages(rows.getString(2)), readMessages(rows.getString(3))));
                }
            }
            return List.copyOf(tasks);
        });
    }

    /**
     * 标记一项工作完成，并累计本次真正新建的文件数。
     *
     * 写文件与本事务不跨资源原子化；进程在二者之间退出时允许再次提取，
     * 由后续整理收敛短期重复。
     */
    public synchronized void completeTaskAndIncrement(String taskId, int createdCount) {
        if (createdCount < 0) {
            throw new IllegalArgumentException("createdCount 不能小于 0");
        }
        inTransaction(connection -> {
            // 1. 只有工作确实删除后才增加整理计数，避免重复计数。
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM memory_tasks WHERE task_id = ?")) {
                delete.setString(1, taskId);
                if (delete.executeUpdate() != 1) {
                    throw new IllegalStateException("记忆工作不存在：" + taskId);
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE memory_maintenance_state SET unconsolidated_count = unconsolidated_count +  ? WHERE id = 1")) {
                update.setInt(1, createdCount);
                update.executeUpdate();
            }
            return null;
        });
    }

    /** 返回整理触发所需的持久化状态。 */
    public synchronized MaintenanceState maintenanceState() {
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT unconsolidated_count FROM memory_maintenance_state WHERE id = 1");
                 ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("记忆整理状态不存在");
                }
                return new MaintenanceState(rows.getInt(1));
            }
        });
    }

    /** 在一次整理成功后清除新文件计数。 */
    public synchronized void clearUnconsolidatedCount() {
        updateMaintenance("UPDATE memory_maintenance_state SET unconsolidated_count = 0 WHERE id = 1", null);
    }

    private void updateMaintenance(String sql, String value) {
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (value != null) {
                    statement.setString(1, value);
                }
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("记忆整理状态不存在");
                }
            }
            return null;
        });
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS memory_tasks (task_id TEXT PRIMARY KEY, turn_context_json TEXT NOT NULL, model_context_json TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_maintenance_state (id INTEGER PRIMARY KEY CHECK(id = 1), unconsolidated_count INTEGER NOT NULL)");
            statement.execute("INSERT INTO memory_maintenance_state(id, unconsolidated_count) VALUES (1, 0) ON CONFLICT(id) DO NOTHING");
        }
    }

    private List<MessageParam> readMessages(String json) {
        try {
            return JSON.readValue(json, MESSAGE_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取记忆工作上下文", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存记忆工作上下文", exception);
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("记忆工作数据库操作失败", exception);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** 持久化的整理触发状态。 */
    public record MaintenanceState(int unconsolidatedCount) { }
}
