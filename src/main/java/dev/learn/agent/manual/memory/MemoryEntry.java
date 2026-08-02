// [结构] 声明长期记忆领域模型所属的包。
package dev.learn.agent.manual.memory;

// [结构] 引入空值检查和名称规则所需的标准库类型。
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * [结构] 一条已经进入系统可信边界的长期记忆。
 *
 * @param name 记忆的稳定名称，同时用于生成 Markdown 文件名
 * @param type 记忆的业务类型
 * @param description 用于索引和相关性选择的单行描述
 * @param body 记忆的完整 Markdown 正文
 */
public record MemoryEntry(
        String name,
        MemoryType type,
        String description,
        String body
) {
    /*
     * [结构] 名称直接参与文件名生成。
     *
     * 限制为 kebab-case 可以阻止斜杠、.. 和空格进入路径，
     * 同时避免多个显示名称转换成同一个文件名。
     */
    private static final Pattern NAME_PATTERN =
            Pattern.compile(
                    "[a-z0-9]+(?:-[a-z0-9]+)*"
            );

    /*
     * [结构] MEMORY.md 是记忆索引的固定文件名。
     *
     * Windows 默认忽略文件名大小写，因此名称 memory 生成的
     * memory.md 会与 MEMORY.md 冲突。
     */
    private static final String RESERVED_INDEX_NAME =
            "memory";

    /*
     * [结构] 这些名称在 Windows 中表示系统设备。
     *
     * 即使添加 .md 扩展名也不能成为普通文件，
     * 因此在领域对象进入存储层前统一拒绝。
     */
    private static final Pattern WINDOWS_DEVICE_NAME_PATTERN =
            Pattern.compile(
                    "(?:con|prn|aux|nul|com[1-9]|lpt[1-9])"
            );

    /**
     * [边界：防止非法领域数据进入记忆存储层]
     * 在记忆进入存储层之前统一检查业务契约。
     *
     * 文件解析结果和模型提取结果都属于外部输入；
     * 只有通过这里的对象才允许交给后续仓库存储。
     */
    public MemoryEntry {
        /*
         * [边界：当模型或文件提供空名称、路径片段、索引保留名或
         * Windows 设备名时，防止系统接受无法安全映射为主题文件的身份]
         */
        name =
                requireValidName(
                        name
                );

        // [边界：防止不存在的记忆类型进入系统] 类型必须由 MemoryType 明确表达。
        type =
                Objects.requireNonNull(
                        type,
                        "记忆类型不能为空"
                );

        /*
         * [边界：防止多行描述破坏一条记忆占一行的索引结构]
         * description 会作为 MEMORY.md 中的一条索引摘要。
         * 多行描述会破坏“一条记忆占一行”的索引结构。
         */
        if (description != null
                && (description.contains("\n")
                || description.contains("\r"))) {
            throw new IllegalArgumentException(
                    "记忆描述必须是单行文本"
            );
        }

        // [准备] 统一清理描述和正文的首尾空白。
        description =
                requireText(
                        description,
                        "记忆描述"
                );

        // [准备] 清理正文首尾空白，保留正文内部的 Markdown 结构。
        body =
                requireText(
                        body,
                        "记忆正文"
                );

    }

    /**
     * 校验领域身份、主题文件名和索引键共同使用的记忆名称。
     *
     * @param name 外部提供的记忆名称
     * @return 经过完整校验的稳定名称
     */
    static String requireValidName(
            String name
    ) {
        String normalized =
                requireText(
                        name,
                        "记忆名称"
                );

        /*
         * [边界：当名称包含空格、斜杠或上级目录片段时，
         * 防止一次保存定位到意料之外的文件，或多个输入映射成同一文件]
         */
        if (!NAME_PATTERN.matcher(
                        normalized
                )
                .matches()) {
            throw new IllegalArgumentException(
                    "记忆名称只能使用小写字母、数字和连字符："
                            + normalized
            );
        }

        /*
         * [边界：当名称为 memory 时，防止 Windows 将 memory.md
         * 与 MEMORY.md 视为同一文件，导致保存主题时覆盖记忆索引]
         */
        if (RESERVED_INDEX_NAME.equals(
                normalized
        )) {
            throw new IllegalArgumentException(
                    "记忆名称不能使用索引保留名称："
                            + normalized
            );
        }

        /*
         * [边界：当名称为 con、nul、com1 等 Windows 设备名时，
         * 防止领域对象创建成功、真正保存时却无法生成普通文件]
         */
        if (WINDOWS_DEVICE_NAME_PATTERN.matcher(
                        normalized
                )
                .matches()) {
            throw new IllegalArgumentException(
                    "记忆名称不能使用 Windows 保留设备名："
                            + normalized
            );
        }

        return normalized;
    }

    /**
     * [边界：防止缺失或只有空白的文本进入可信领域对象]
     * 检查必填文本并去掉首尾空白。
     *
     * @param value 待检查文本
     * @param fieldName 报错时使用的字段名称
     * @return 清理后的非空文本
     */
    private static String requireText(
            String value,
            String fieldName
    ) {
        // [边界：防止必填文本为 null] null 不属于可规范化的文本。
        Objects.requireNonNull(
                value,
                fieldName + "不能为空"
        );

        // [准备] 去掉外部文本输入常见的首尾空白。
        String normalized =
                value.trim();

        // [边界：防止只包含空白的值绕过必填校验]
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + "不能为空"
            );
        }

        // [结果] 返回可以进入领域对象的规范化文本。
        return normalized;
    }
}
