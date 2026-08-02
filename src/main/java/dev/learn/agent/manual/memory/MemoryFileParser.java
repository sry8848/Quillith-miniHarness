package dev.learn.agent.manual.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一个 Markdown 记忆文件解析成可信的记忆条目。
 *
 * 本类只负责单个主题文件的格式边界：
 * 分离 YAML frontmatter、读取固定元数据并校验文件名。
 *
 * 目录扫描、文件写入和索引重建由后续仓库负责。
 */
public final class MemoryFileParser {
    /*
     * 记忆文件必须从第一行开始提供完整 frontmatter。
     *
     * \\R 同时兼容 Windows CRLF 和 Unix LF；
     * \\A 与 \\z 保证不会误把正文中的 --- 当成元数据。
     */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile(
                    "\\A---\\R"
                            + "(?<metadata>.*?)"
                            + "\\R---(?:\\R|\\z)"
                            + "(?<body>.*)\\z",
                    Pattern.DOTALL
            );

    /*
     * frontmatter 采用封闭字段集合。
     *
     * 拼错字段名时应立即失败，
     * 不能让 descriptionn 之类的错误被静默忽略。
     */
    private static final Set<String> ALLOWED_FIELDS =
            Set.of(
                    "name",
                    "description",
                    "type"
            );

    private final ObjectMapper yamlMapper;

    /**
     * 创建启用重复字段检测的记忆文件解析器。
     *
     * 同一个 YAML 字段出现两次时，没有可靠理由猜测哪个值有效，
     * 因此在进入业务对象前直接拒绝。
     */
    public MemoryFileParser() {
        // 只启用当前文件边界真正需要的严格重复字段检查。
        YAMLFactory yamlFactory =
                YAMLFactory.builder()
                        .enable(
                                StreamReadFeature
                                        .STRICT_DUPLICATE_DETECTION
                        )
                        .build();

        // 使用树形解析，不允许 YAML 指定要实例化的 Java 类型。
        this.yamlMapper =
                new ObjectMapper(
                        yamlFactory
                );
    }

    /**
     * 读取并解析一个 Markdown 记忆文件。
     *
     * 文件名必须等于 {@code name + ".md"}，
     * 保证名称、查询键和存储位置不会产生三套身份。
     *
     * @param memoryFile 待解析的主题文件
     * @return 已通过业务契约校验的记忆条目
     * @throws IOException 文件不存在、无法读取或真实路径解析失败
     */
    public MemoryEntry parse(
            Path memoryFile
    ) throws IOException {
        // 先解析真实路径，后续仓库还会负责检查它是否位于记忆目录内。
        Path realFile =
                Objects.requireNonNull(
                                memoryFile,
                                "记忆文件路径不能为空"
                        )
                        .toRealPath();

        // 目录和其他特殊文件不能被当作普通记忆文件读取。
        if (!Files.isRegularFile(
                realFile
        )) {
            throw new IllegalArgumentException(
                    "记忆路径必须是普通文件："
                            + realFile
            );
        }

        // Markdown 文件统一使用 UTF-8 读取。
        String source =
                Files.readString(
                        realFile
                );

        // frontmatter 缺失时不能根据文件名或默认类型猜测元数据。
        Matcher matcher =
                FRONTMATTER_PATTERN.matcher(
                        source
                );

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "记忆文件必须以完整的 YAML "
                            + "frontmatter 开头："
                            + realFile
            );
        }

        // YAML 仍是不可信文件输入，先解析成数据树。
        JsonNode metadata =
                parseMetadata(
                        matcher.group(
                                "metadata"
                        ),
                        realFile
                );

        /*
         * 拒绝契约之外的字段。
         *
         * 当前系统只有三个元数据来源，
         * 新字段必须先定义真实业务语义，不能随文件内容自动扩展。
         */
        metadata.fieldNames()
                .forEachRemaining(
                        fieldName -> {
                            if (!ALLOWED_FIELDS.contains(
                                    fieldName
                            )) {
                                throw new IllegalArgumentException(
                                        "记忆文件包含未知字段 "
                                                + fieldName
                                                + "："
                                                + realFile
                                );
                            }
                        }
                );

        // 三个 frontmatter 字段都必须是明确的字符串。
        String name =
                requireTextField(
                        metadata,
                        "name",
                        realFile
                );

        String description =
                requireTextField(
                        metadata,
                        "description",
                        realFile
                );

        MemoryType type =
                MemoryType.fromWireValue(
                        requireTextField(
                                metadata,
                                "type",
                                realFile
                        )
                );

        // MemoryEntry 统一执行名称、描述和正文的业务校验。
        MemoryEntry entry =
                new MemoryEntry(
                        name,
                        type,
                        description,
                        matcher.group(
                                "body"
                        )
                );

        /*
         * 文件名与稳定名称必须完全一致。
         *
         * 如果允许二者不同，更新和删除时将无法确定
         * 应该按 frontmatter 名称还是文件名定位记忆。
         */
        String expectedFilename =
                entry.name()
                        + ".md";

        String actualFilename =
                realFile.getFileName()
                        .toString();

        if (!expectedFilename.equals(
                actualFilename
        )) {
            throw new IllegalArgumentException(
                    "记忆文件名必须是 "
                            + expectedFilename
                            + "，实际为 "
                            + actualFilename
            );
        }

        return entry;
    }

    /**
     * 使用 Jackson 把 YAML frontmatter 解析成普通数据树。
     *
     * @param yaml frontmatter 原始内容
     * @param memoryFile 当前记忆文件
     * @return YAML 对象节点
     */
    private JsonNode parseMetadata(
            String yaml,
            Path memoryFile
    ) {
        try {
            // 树形解析只读取数据，不启用多态对象反序列化。
            JsonNode metadata =
                    yamlMapper.readTree(
                            yaml
                    );

            if (metadata == null
                    || !metadata.isObject()) {
                throw new IllegalArgumentException(
                        "记忆 frontmatter 必须是 YAML 对象："
                                + memoryFile
                );
            }

            return metadata;
        } catch (JsonProcessingException exception) {
            // 保留原始解析异常，便于定位具体 YAML 格式错误。
            throw new IllegalArgumentException(
                    "无法解析记忆文件的 YAML frontmatter："
                            + memoryFile,
                    exception
            );
        }
    }

    /**
     * 读取一个必填字符串字段。
     *
     * 空白内容的进一步校验交给 {@link MemoryEntry}，
     * 本方法只确认 YAML 数据类型与字段存在性。
     *
     * @param metadata 已解析的 YAML 对象
     * @param fieldName 字段名称
     * @param memoryFile 当前记忆文件
     * @return 字段原始字符串
     */
    private static String requireTextField(
            JsonNode metadata,
            String fieldName,
            Path memoryFile
    ) {
        // 不支持数字、数组或对象自动转成字符串。
        JsonNode value =
                metadata.get(
                        fieldName
                );

        if (value == null
                || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "记忆 frontmatter 缺少字符串字段 "
                            + fieldName
                            + "："
                            + memoryFile
            );
        }

        return value.textValue();
    }

}