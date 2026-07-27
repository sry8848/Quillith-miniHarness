package dev.learn.agent.manual.skill;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个已经通过启动校验、可以提供给 Agent 使用的技能。
 *
 * @param name 技能的唯一名称，也是模型调用 load_skill 时使用的名称
 * @param description 技能的用途及适用时机，用于帮助模型判断是否加载
 * @param instructions SKILL.md 中按需加载的完整操作说明
 * @param directory 技能所在目录，用于定位该技能引用的资料和脚本
 */
public record SkillDefinition(
        String name,
        String description,
        String instructions,
        Path directory
) {

    private static final int MAX_NAME_LENGTH =
            64;

    private static final int MAX_DESCRIPTION_LENGTH =
            1_024;

    private static final Pattern NAME_PATTERN =
            Pattern.compile(
                    "[a-z0-9]+(?:-[a-z0-9]+)*"
            );

    /**
     * 在技能进入系统可信边界前统一检查数据契约。
     *
     * SKILL.md 属于文件系统输入，可能被用户或第三方内容修改，
     * 因此不能把缺失字段静默替换成目录名或默认描述。
     */
    public SkillDefinition {
        name =
                requireText(
                        name,
                        "技能名称"
                );

        description =
                requireText(
                        description,
                        "技能描述"
                );

        instructions =
                requireText(
                        instructions,
                        "技能说明"
                );

        directory =
                Objects.requireNonNull(
                                directory,
                                "技能目录不能为空"
                        )
                        .toAbsolutePath()
                        .normalize();

        if (name.length()
                > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "技能名称不能超过 "
                            + MAX_NAME_LENGTH
                            + " 个字符："
                            + name
            );
        }

        if (!NAME_PATTERN.matcher(name)
                .matches()) {
            throw new IllegalArgumentException(
                    "技能名称只能包含小写字母、数字和连字符："
                            + name
            );
        }

        if (description.length()
                > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "技能描述不能超过 "
                            + MAX_DESCRIPTION_LENGTH
                            + " 个字符："
                            + name
            );
        }
    }

    /**
     * 检查必填文本，并清理文件格式产生的首尾空白。
     *
     * @param value 待检查的文本
     * @param fieldName 报错时显示的字段名称
     * @return 清理首尾空白后的文本
     */
    private static String requireText(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName + "不能为空"
        );

        String normalized =
                value.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + "不能为空"
            );
        }

        return normalized;
    }
}