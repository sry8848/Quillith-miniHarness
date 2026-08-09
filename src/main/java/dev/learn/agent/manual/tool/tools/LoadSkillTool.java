package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.skill.SkillDefinition;
import dev.learn.agent.manual.skill.SkillRegistry;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 根据注册名称加载一个技能的完整操作说明。
 *
 * 工具只接受技能名称，不接受文件路径。
 * 因此模型只能加载启动时已经扫描和校验过的技能。
 */
public final class LoadSkillTool implements AgentTool {

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "load_skill",
                    "Load the full instructions for an available "
                            + "skill by its exact catalog name. "
                            + "Use this when a skill catalog entry "
                            + "matches the current task.",
                    Map.of(
                            "name",
                            ToolDefinitionFactory.stringProperty(
                                    "Exact skill name from the "
                                            + "available skills catalog."
                            )
                    ),
                    List.of(
                            "name"
                    )
            );

    private final SkillRegistry skillRegistry;

    /**
     * 创建从指定注册表加载技能的工具。
     *
     * @param skillRegistry 启动时已经完成扫描和校验的技能注册表
     */
    public LoadSkillTool(
            SkillRegistry skillRegistry
    ) {
        this.skillRegistry =
                Objects.requireNonNull(
                        skillRegistry,
                        "SkillRegistry 不能为空"
                );
    }

    /**
     * 返回发送给模型的 load_skill 工具定义。
     *
     * @return load_skill 工具定义
     */
    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /**
     * 判断技能查询是否可以进入并发批次。
     *
     * @return 固定返回 true，因为注册表在启动后只读
     */
    @Override
    public boolean isConcurrencySafe() {
        // SkillRegistry 构造完成后不再修改，并发查询不会产生状态冲突。
        return true;
    }

    /**
     * 按精确名称返回技能说明。
     *
     * @param input 模型生成的工具参数
     * @return 包含完整技能说明或明确错误的工具执行结果
     */
    @Override
    public ToolExecutionResult execute(
            JsonNode input
    ) {
        JsonNode nameNode =
                input.get(
                        "name"
                );
        /*
         * 工具 Schema 只是提供给模型的生成约束，
         * 真正执行时仍需校验模型返回的数据。
         */
        if (nameNode == null
                || !nameNode.isTextual()
                || nameNode.textValue()
                .isBlank()) {
            return ToolExecutionResult.failure(
                    "Error: name must be "
                            + "a non-blank string"
            );
        }

        String name =
                nameNode.textValue();

        /*
         * 不对名称做 trim、大小写转换或别名匹配。
         *
         * 模型必须使用目录中提供的准确名称，
         * 错误名称应该尽早暴露，而不是被静默修正。
         */
        Optional<SkillDefinition> result =
                skillRegistry.find(
                        name
                );

        if (result.isEmpty()) {
            return ToolExecutionResult.failure(
                    "Error: unknown skill: "
                            + name
            );
        }

        SkillDefinition skill =
                result.get();

        /*
         * 返回技能根目录，帮助模型解析正文中的相对资源路径。
         *
         * 本工具只加载说明，不自动执行技能中的脚本。
         * 后续 read_file、bash 等调用仍会经过原有权限和 Hook。
         */
        return ToolExecutionResult.success(
                "Loaded skill: "
                        + skill.name()
                        + "\nBase directory: "
                        + skill.directory()
                        + "\n\n"
                        + skill.instructions()
        );
    }

}
