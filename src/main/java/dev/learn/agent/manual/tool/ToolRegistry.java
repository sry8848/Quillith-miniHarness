package dev.learn.agent.manual.tool;

import com.anthropic.models.messages.ToolUnion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 保存 Agent 可以使用的所有工具。
 */
public final class ToolRegistry {

    // 记录工具实现抛出的完整异常，模型只接收精简错误。
    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    ToolRegistry.class
            );

    /*
     * key 是模型使用的工具名称，
     * value 是对应的 Java 工具实现。
     *
     * LinkedHashMap 会保持工具注册顺序，
     * 发送给模型时顺序更加稳定。
     */
    private final Map<String, AgentTool> tools =
            new LinkedHashMap<>();

    /**
     * 注册一个工具。
     */
    public void register(AgentTool tool) {
        Objects.requireNonNull(
                tool,
                "AgentTool 不能为空"
        );

        String toolName =
                tool.definition()
                        .name();

        /*
         * putIfAbsent 只在名称不存在时加入工具。
         *
         * 如果已经存在同名工具，
         * previous 会保存原来的工具对象。
         */
        AgentTool previous =
                tools.putIfAbsent(
                        toolName,
                        tool
                );

        /*
         * 两个同名工具会导致模型请求无法确定应该执行哪一个，
         * 因此在程序启动时立即报错。
         */
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Duplicate tool name: "
                            + toolName
            );
        }
    }

    /**
     * 按参数顺序注册一组工具。
     *
     * 每个工具仍然通过 {@link #register(AgentTool)} 完成校验，
     * 因此批量注册不会绕过空值和重复名称检查。
     *
     * @param tools 需要注册的工具
     */
    public void registerAll(
            AgentTool... tools
    ) {
        Objects.requireNonNull(
                tools,
                "工具数组不能为空"
        );

        for (AgentTool tool : tools) {
            register(tool);
        }
    }

    /**
     * 返回需要发送给模型的全部工具定义。
     */
    public List<ToolUnion> definitions() {
        return tools.values()
                .stream()
                .map(AgentTool::definition)
                .map(ToolUnion::ofTool)
                .toList();
    }

    /**
     * 根据模型返回的工具名称执行对应工具。
     *
     * @param toolCall 已通过协议解析的工具调用
     * @return 可以继续回传模型的成功或失败结果
     */
    public ToolExecutionResult execute(
            ToolCall toolCall
    ) {
        Objects.requireNonNull(
                toolCall,
                "ToolCall 不能为空"
        );

        AgentTool tool =
                tools.get(
                        toolCall.name()
                );

        /*
         * 模型输出属于外部输入。
         *
         * 即使发送给模型的工具列表中不存在这个名字，
         * 模型仍可能生成错误的工具名称，因此必须检查。
         */
        if (tool == null) {
            return ToolExecutionResult.failure(
                    "Error: unknown tool: "
                            + toolCall.name()
            );
        }

        /*
         * 单个工具实现的运行时异常不能打断同批其他工具，
         * 否则已经写入历史的 tool_use 将缺少配对结果。
         * 完整异常只进入本地日志，模型接收可操作的错误摘要。
         */
        try {
            return Objects.requireNonNull(
                    tool.execute(
                            toolCall.input()
                    ),
                    "AgentTool.execute 不能返回 null"
            );
        } catch (RuntimeException exception) {
            // 保存完整堆栈，避免把内部实现细节全部塞进模型上下文。
            LOGGER.error(
                    "工具 {} 执行时发生未处理异常",
                    toolCall.name(),
                    exception
            );

            // 先读取一次异常消息，避免重复调用并集中处理空消息。
            String exceptionMessage =
                    exception.getMessage();

            // 没有消息时至少返回异常类型，保证模型得到非空错误原因。
            String detail =
                    exceptionMessage == null
                            || exceptionMessage.isBlank()
                            ? exception.getClass()
                                    .getSimpleName()
                            : exceptionMessage;

            // 把异常降级为本次工具失败，使同批其他工具仍能继续执行。
            return ToolExecutionResult.failure(
                    "Error: tool execution failed unexpectedly: "
                            + detail
            );
        }
    }
}
