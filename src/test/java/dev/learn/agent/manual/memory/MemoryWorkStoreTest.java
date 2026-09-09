package dev.learn.agent.manual.memory;

import com.anthropic.models.messages.MessageParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证后台记忆工作在进程重建后仍可恢复。 */
class MemoryWorkStoreTest {

    @TempDir
    Path agentHome;

    @Test
    void keepsPendingTaskUntilFileWriteIsAcknowledged() throws Exception {
        MemoryExtractionTask task = new MemoryExtractionTask("task-1", List.of(user("turn")), List.of(user("context")));

        // 1. 入队后的新 Store 必须仍能读取同一份冻结上下文。
        new MemoryWorkStore(agentHome).enqueue(task);
        MemoryWorkStore recovered = new MemoryWorkStore(agentHome);
        assertEquals(List.of(task), recovered.pendingTasks());

        // 2. 只有写文件成功后的确认才删除工作并增加整理计数。
        recovered.completeTaskAndIncrement("task-1", 2);
        assertEquals(List.of(), recovered.pendingTasks());
        assertEquals(2, recovered.maintenanceState().unconsolidatedCount());
    }

    /** 创建最小可序列化的用户消息。 */
    private static MessageParam user(String text) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(text).build();
    }
}
