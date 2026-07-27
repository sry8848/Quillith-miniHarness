package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.AgentLoop;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一个独立子任务交给新的 Agent 循环处理。
 *
 * 每次执行都会创建全新的消息历史，
 * 子 Agent 的文件读取、工具结果和中间推理不会进入父 Agent 上下文。
 * 子 Agent 完成后，只把最终文本结论返回给父 Agent。
 */
public final class TaskTool implements AgentTool {

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "task",
                    "Launch a subagent to handle a self-contained "
                            + "complex subtask. The subagent uses an "
                            + "isolated conversation and returns only "
                            + "its final conclusion.",
                    Map.of(
                            "description",
                            ToolDefinitionFactory.stringProperty(
                                    "A complete description of the subtask. "
                                            + "Include all context the "
                                            + "subagent needs."
                            )
                    ),
                    List.of(
                            "description"
                    )
            );

    private final AgentLoop subagentLoop;

    /**
     * 创建使用指定子 Agent 循环的任务工具。
     *
     * @param subagentLoop 只拥有子 Agent 工具白名单的循环
     */
    public TaskTool(
            AgentLoop subagentLoop
    ) {
        this.subagentLoop =
                Objects.requireNonNull(
                        subagentLoop,
                        "子 AgentLoop 不能为空"
                );
    }

    /**
     * 返回发送给父模型的 task 工具定义。
     *
     * @return task 工具定义
     */
    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /**
     * 使用全新消息历史同步执行一个子任务。
     *
     * @param input 父模型生成的 task 工具参数
     * @return 子 Agent 的最终文本结论
     */
    @Override
    public String execute(
            JsonNode input
    ) {
        JsonNode descriptionNode =
                input.get(
                        "description"
                );

        /*
         * 工具输入来自模型，仍属于系统外部输入，
         * 必须在进入子 Agent 可信边界前完成校验。
         */
        if (descriptionNode == null
                || !descriptionNode.isTextual()
                || descriptionNode.textValue()
                .isBlank()) {
            return "Error: description must be "
                    + "a non-blank string";
        }

        String description =
                descriptionNode.textValue();

        /*
         * 这是上下文隔离真正发生的位置。
         *
         * 这里只放入本次子任务描述，
         * 不复制父 Agent 的任何历史消息。
         */
        List<MessageParam> subagentMessages =
                new ArrayList<>();

        subagentMessages.add(
                MessageParam.builder()
                        .role(
                                MessageParam.Role.USER
                        )
                        .content(
                                description
                        )
                        .build()
        );

        System.out.println();
        System.out.println(
                "[Subagent spawned]"
        );

        /*
         * 当前版本同步执行：
         * 子 Agent 完成前，父 Agent 会一直等待。
         *
         * 异步执行和后台通知属于 s13，
         * 本章不提前实现。
         */
        String conclusion =
                subagentLoop.run(
                        subagentMessages
                );

        System.out.println(
                "[Subagent done]"
        );

        return conclusion;
    }
}