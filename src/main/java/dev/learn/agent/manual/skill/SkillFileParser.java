package dev.learn.agent.manual.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一个 SKILL.md 文件解析成经过校验的技能定义。
 *
 * 本类只负责单个文件的格式边界：
 * 分离 YAML frontmatter、解析元数据并保留 Markdown 正文。
 *
 * 技能目录扫描、重复名称检查和路径范围检查由后续注册表负责。
 */
public final class SkillFileParser {

    /*
     * \\A 和 \\z 表示整个字符串的开始与结束，
     * 防止在文件中间误识别出一段 frontmatter。
     *
     * \\R 同时兼容 Windows 的 CRLF 和 Unix 的 LF 换行。
     */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile(
                    "\\A---\\R"
                            + "(?<metadata>.*?)"
                            + "\\R---(?:\\R|\\z)"
                            + "(?<instructions>.*)\\z",
                    Pattern.DOTALL
            );

    private final ObjectMapper yamlMapper;

    /**
     * 创建使用安全树形映射方式的技能文件解析器。
     *
     * 严格重复字段检测可以阻止同一个 YAML 字段被定义两次，
     * 避免程序和维护者对“哪个值生效”产生不同理解。
     */
    public SkillFileParser() {
        YAMLFactory yamlFactory =
                YAMLFactory.builder()
                        .enable(
                                StreamReadFeature
                                        .STRICT_DUPLICATE_DETECTION
                        )
                        .build();

        this.yamlMapper =
                new ObjectMapper(
                        yamlFactory
                );
    }

    /**
     * 读取并解析一个 SKILL.md。
     *
     * @param manifest SKILL.md 文件路径
     * @return 已通过 {@link SkillDefinition} 校验的技能
     * @throws IOException 文件不存在、无法读取或真实路径解析失败
     */
    public SkillDefinition parse(
            Path manifest
    ) throws IOException {
        Path realManifest =
                Objects.requireNonNull(
                                manifest,
                                "SKILL.md 路径不能为空"
                        )
                        .toRealPath();

        String source =
                Files.readString(
                        realManifest
                );

        Matcher matcher =
                FRONTMATTER_PATTERN.matcher(
                        source
                );

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "SKILL.md 必须以完整的 YAML "
                            + "frontmatter 开头："
                            + realManifest
            );
        }

        JsonNode metadata =
                parseMetadata(
                        matcher.group(
                                "metadata"
                        ),
                        realManifest
                );

        String name =
                requireTextField(
                        metadata,
                        "name",
                        realManifest
                );

        String description =
                requireTextField(
                        metadata,
                        "description",
                        realManifest
                );

        String instructions =
                matcher.group(
                        "instructions"
                );

        return new SkillDefinition(
                name,
                description,
                instructions,
                realManifest.getParent()
        );
    }

    /**
     * 使用 Jackson 解析 frontmatter 中的 YAML。
     *
     * 这里只生成数据树，不启用多态对象反序列化，
     * 避免 YAML 内容决定要创建的任意 Java 类型。
     */
    private JsonNode parseMetadata(
            String yaml,
            Path manifest
    ) {
        try {
            JsonNode metadata =
                    yamlMapper.readTree(
                            yaml
                    );

            if (metadata == null
                    || !metadata.isObject()) {
                throw new IllegalArgumentException(
                        "SKILL.md 的 frontmatter "
                                + "必须是 YAML 对象："
                                + manifest
                );
            }

            return metadata;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "无法解析 SKILL.md 的 YAML "
                            + "frontmatter："
                            + manifest,
                    exception
            );
        }
    }

    /**
     * 从 YAML 对象中读取一个必填字符串字段。
     *
     * @param metadata 已解析的 YAML 对象
     * @param fieldName 字段名称
     * @param manifest 当前 SKILL.md 路径
     * @return 字段原始文本，空白检查交给 SkillDefinition 统一完成
     */
    private static String requireTextField(
            JsonNode metadata,
            String fieldName,
            Path manifest
    ) {
        JsonNode value =
                metadata.get(
                        fieldName
                );

        if (value == null
                || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "SKILL.md 缺少字符串字段 "
                            + fieldName
                            + "："
                            + manifest
            );
        }

        return value.textValue();
    }
}