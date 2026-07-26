package dev.learn.agent.manual.tool;

import com.anthropic.models.messages.ToolUnion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 保存 Agent 可以使用的所有工具。
 */
public final class ToolRegistry {

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
     */
    public String execute(
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
            return "Error: unknown tool: "
                    + toolCall.name();
        }

        return tool.execute(
                toolCall.input()
        );
    }
}
