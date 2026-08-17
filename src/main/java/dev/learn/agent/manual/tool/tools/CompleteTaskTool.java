package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.task.TaskRecord;
import dev.learn.agent.manual.task.TaskStore;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 用来“完成任务”的工具。
 *
 * 只能完成当前 owner 已经认领、并且处于 in_progress 状态的任务。
 * 完成之后，还会告诉模型哪些下游任务因此解除阻塞。
 */
public final class CompleteTaskTool implements AgentTool {

    /**
     * 工具定义。
     *
     * 相当于告诉大模型：
     *
     * 工具名：complete_task
     * 参数：
     * {
     *   "task_id": "字符串"
     * }
     *
     * 注意：
     * owner 不交给模型填写，而是由 Java 应用提前绑定。
     */
    private static final Tool DEFINITION = ToolDefinitionFactory.create(
            "complete_task",
            "完成当前执行者认领的 in_progress 任务。",

            // 定义工具可以接收哪些参数
            Map.of(
                    "task_id",
                    ToolDefinitionFactory.stringProperty("待完成任务 ID。")
            ),

            // 必填参数
            List.of("task_id")
    );

    /**
     * 保存任务数据的对象。
     *
     * 真正的“完成任务”逻辑主要由 TaskStore 负责。
     */
    private final TaskStore store;

    /**
     * 当前工具绑定的执行者身份。
     *
     * 这个值由应用程序提供，而不是由大模型提供，
     * 可以避免模型伪造 owner。
     */
    private final String owner;

    /**
     * 创建一个绑定指定 owner 的完成任务工具。
     */
    public CompleteTaskTool(TaskStore store, String owner) {

        // store 不能为空
        this.store = Objects.requireNonNull(
                store,
                "TaskStore 不能为空"
        );

        // 检查并保存 owner
        this.owner = requireOwner(owner);
    }

    /**
     * 返回工具定义。
     *
     * Agent 可以根据这个定义知道：
     * 工具叫什么、有什么参数、哪些参数必填。
     */
    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /**
     * 真正执行 complete_task。
     *
     * 主要流程：
     *
     * 1. 获取 task_id
     * 2. 校验 task_id
     * 3. 调用 TaskStore 完成任务
     * 4. 获取刚解除阻塞的任务
     * 5. 返回结果给 Agent
     */
    @Override
    public ToolExecutionResult execute(JsonNode input) {
        try {

            /*
             * 获取模型传来的 task_id。
             *
             * input 可能类似：
             *
             * {
             *   "task_id": "task_123"
             * }
             */
            JsonNode taskId =
                    input == null
                            ? null
                            : input.get("task_id");

            /*
             * task_id 必须存在，而且必须是字符串。
             */
            if (taskId == null || !taskId.isTextual()) {
                throw new IllegalArgumentException(
                        "task_id must be a string"
                );
            }

            /*
             * 调用 TaskStore 完成任务。
             *
             * taskId.textValue()
             *     → 模型传来的任务 ID
             *
             * owner
             *     → 当前应用绑定的执行者
             *
             * 返回值 CompletionResult 中包含：
             * 1. 已完成的任务
             * 2. 因为它完成而解除阻塞的任务
             */
            TaskStore.CompletionResult completion =
                    store.complete(
                            taskId.textValue(),
                            owner
                    );

            // 获取刚刚完成的任务
            TaskRecord task = completion.completed();

            /*
             * 构造基本返回信息，例如：
             *
             * Completed task_1 (实现登录功能)
             */
            String result =
                    "Completed "
                            + task.id()
                            + " ("
                            + task.subject()
                            + ")";

            /*
             * 如果完成当前任务后，
             * 有其他任务因此解除阻塞，就一起告诉 Agent。
             */
            if (!completion.unblocked().isEmpty()) {

                /*
                 * 假设解除阻塞的任务有：
                 *
                 * 登录测试 (task_2)
                 * 首页开发 (task_3)
                 *
                 * 最终拼成：
                 *
                 * 登录测试 (task_2), 首页开发 (task_3)
                 */
                String unblocked =
                        completion.unblocked()
                                .stream()

                                // 把 TaskRecord 转成可读字符串
                                .map(item ->
                                        item.subject()
                                                + " ("
                                                + item.id()
                                                + ")"
                                )

                                // 多个任务之间用逗号连接
                                .collect(
                                        Collectors.joining(", ")
                                );

                // 添加到返回结果
                result += "\nUnblocked: " + unblocked;
            }

            // 正常执行，返回成功结果
            return ToolExecutionResult.success(result);

        } catch (
                IllegalArgumentException
                | IllegalStateException
                | UncheckedIOException exception
        ) {

            /*
             * 常见业务错误统一转成 ToolExecutionResult.failure，
             * 而不是继续把异常抛到 Agent Harness 外面。
             *
             * 例如：
             * - task_id 不合法
             * - 任务状态不允许完成
             * - 当前 owner 不是任务拥有者
             * - TaskStore 读写失败
             */
            return ToolExecutionResult.failure(
                    "Error: " + exception.getMessage()
            );
        }
    }

    /**
     * 校验应用传进来的 owner。
     */
    private static String requireOwner(String owner) {

        /*
         * Objects.requireNonNull：
         * owner == null 时直接抛 NullPointerException。
         *
         * trim：
         * 去掉字符串首尾空格。
         */
        String value =
                Objects.requireNonNull(
                        owner,
                        "任务 owner 不能为空"
                ).trim();

        /*
         * 防止：
         *
         * owner = ""
         * owner = "   "
         */
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "任务 owner 不能为空"
            );
        }

        return value;
    }
}