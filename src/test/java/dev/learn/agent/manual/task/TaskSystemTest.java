package dev.learn.agent.manual.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.learn.agent.manual.AgentState;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.tool.tools.ClaimTaskTool;
import dev.learn.agent.manual.tool.tools.CompleteTaskTool;
import dev.learn.agent.manual.tool.tools.CreateTaskTool;
import dev.learn.agent.manual.tool.tools.GetTaskTool;
import dev.learn.agent.manual.tool.tools.ListTasksTool;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 s10 持久化任务系统的核心教学行为。 */
class TaskSystemTest {
    // 每个测试使用独立工作区。
    @TempDir
    Path workspace;

    /** 任务应跨 Store 实例恢复，依赖应去重并稳定排序。 */
    @Test
    void persistsTasksAndDependencies() throws IOException {
        TaskStore first = newStore();
        TaskRecord upstream = first.create(" 设计数据库 ", " 创建表结构 ", List.of());
        TaskRecord downstream = first.create(
                "开发接口",
                "",
                List.of(upstream.id(), upstream.id())
        );

        // 新实例证明事实来源是磁盘而不是内存。
        TaskStore reopened = newStore();
        assertEquals("开发接口", reopened.get(downstream.id()).subject());
        assertEquals(List.of(upstream.id()), reopened.get(downstream.id()).blockedBy());

        List<String> ids = reopened.list().stream().map(TaskRecord::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    /** 状态必须按顺序转换，完成上游只解锁满足条件的直接下游。 */
    @Test
    void enforcesStateMachineAndReportsUnblockedTasks() throws IOException {
        TaskStore store = newStore();
        TaskRecord upstream = store.create("数据库", "", List.of());
        TaskRecord other = store.create("安全评审", "", List.of());
        TaskRecord api = store.create("接口", "", List.of(upstream.id()));
        TaskRecord release = store.create("发布", "", List.of(upstream.id(), other.id()));

        // 不能越级完成或绕过依赖认领。
        assertThrows(
                IllegalStateException.class,
                () -> store.complete(upstream.id(), "main-agent")
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.claim(api.id(), "main-agent")
        );

        TaskRecord claimed = store.claim(upstream.id(), "main-agent");
        assertEquals(TaskStatus.IN_PROGRESS, claimed.status());
        assertEquals("main-agent", claimed.owner());
        assertThrows(
                IllegalStateException.class,
                () -> store.claim(upstream.id(), "main-agent")
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.complete(upstream.id(), "other-agent")
        );

        TaskStore.CompletionResult result = store.complete(upstream.id(), "main-agent");
        assertEquals(TaskStatus.COMPLETED, newStore().get(upstream.id()).status());
        assertEquals(List.of(api.id()), result.unblocked().stream().map(TaskRecord::id).toList());
        assertFalse(result.unblocked().stream().anyMatch(task -> task.id().equals(release.id())));
    }

    /** 外部输入和损坏任务文件应明确失败。 */
    @Test
    void rejectsInvalidInputAndCorruptFiles() throws IOException {
        TaskStore store = newStore();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create("   ", "", List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create("接口", "", List.of("task_00000000"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> store.get("../task_00000000")
        );

        // 人工损坏的 JSON 不能被 list 静默跳过。
        Path taskDirectory = Files.createDirectories(workspace.resolve(".tasks"));
        Path taskFile = taskDirectory.resolve("task_00000000.json");
        Files.writeString(taskFile, "{invalid-json");
        assertThrows(IllegalStateException.class, store::list);

        // 文件名和内容 ID 必须指向同一个任务。
        Files.writeString(
                taskFile,
                """
                        {
                          "id": "task_11111111",
                          "subject": "接口",
                          "description": "",
                          "status": "pending",
                          "owner": null,
                          "blockedBy": []
                        }
                        """
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.get("task_00000000")
        );
    }

    /** 五个模型工具应保留成功、失败和并发边界。 */
    @Test
    void exposesFiveTaskTools() throws IOException {
        TaskStore store = newStore();
        CreateTaskTool create = new CreateTaskTool(store);
        ListTasksTool list = new ListTasksTool(store);
        GetTaskTool get = new GetTaskTool(store);
        ClaimTaskTool claim = new ClaimTaskTool(store, "main-agent");
        CompleteTaskTool complete = new CompleteTaskTool(store, "main-agent");

        ToolExecutionResult invalid = create.execute(json("{\"subject\": 1}"));
        assertTrue(invalid.error());

        ToolExecutionResult created = create.execute(json("""
                {"subject": "设计数据库", "description": "创建表结构"}
                """));
        assertFalse(created.error());
        TaskRecord task = store.list().getFirst();
        assertTrue(created.content().contains(task.id()));

        ToolExecutionResult loaded = get.execute(taskIdInput(task.id()));
        assertFalse(loaded.error());
        assertEquals("创建表结构", json(loaded.content()).path("description").asText());
        assertTrue(list.execute(json("{}")).content().contains("[pending] [ready]"));

        // 工具失败标记必须反映状态机错误。
        assertTrue(complete.execute(taskIdInput(task.id())).error());
        assertFalse(claim.execute(taskIdInput(task.id())).error());
        assertTrue(claim.execute(taskIdInput(task.id())).error());
        assertFalse(complete.execute(taskIdInput(task.id())).error());

        assertEquals(
                List.of("create_task", "list_tasks", "get_task", "claim_task", "complete_task"),
                List.of(
                        create.definition().name(),
                        list.definition().name(),
                        get.definition().name(),
                        claim.definition().name(),
                        complete.definition().name()
                )
        );
        assertFalse(create.isConcurrencySafe());
        assertTrue(list.isConcurrencySafe());
        assertTrue(get.isConcurrencySafe());
        assertFalse(claim.isConcurrencySafe());
        assertFalse(complete.isConcurrencySafe());
    }

    /** 创建模拟应用重启的 Store。 */
    private TaskStore newStore() throws IOException {
        return new TaskStore(
                new WorkspacePathResolver(
                        new AgentState(
                                false,
                                ToolApprovalMode.BYPASS,
                                workspace,
                                workspace,
                                List.of(workspace),
                                null
                        )
                )
        );
    }

    /** 解析测试工具输入。 */
    private static JsonNode json(String content) {
        try {
            return JsonMapper.builder().build().readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("测试 JSON 非法", exception);
        }
    }

    /** 构造 task_id 输入。 */
    private static JsonNode taskIdInput(String taskId) {
        return json("{\"task_id\": \"" + taskId + "\"}");
    }
}
