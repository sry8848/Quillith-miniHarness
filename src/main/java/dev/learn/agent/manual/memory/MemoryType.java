package dev.learn.agent.manual.memory;

/**
 * 长期记忆允许使用的业务类型。
 *
 * 枚举名称遵循 Java 大写规范；
 * wireValue 是写入文件和发送给模型的稳定协议值。
 */
public enum MemoryType {

    // 封闭四种合法类型，禁止记忆系统临时创造新分类。
    USER("user"),
    FEEDBACK("feedback"),
    PROJECT("project"),
    REFERENCE("reference");

    // 保存 Markdown frontmatter 使用的小写协议值。
    private final String wireValue;

    /**
     * 创建一种记忆类型。
     *
     * @param wireValue 文件和模型协议使用的稳定值
     */
    MemoryType(
            String wireValue
    ) {
        this.wireValue =
                wireValue;
    }

    /**
     * 返回文件和模型协议使用的类型值。
     *
     * @return 小写类型值
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * 把外部协议值转换成可信的 Java 类型。
     *
     * 未知值说明文件或模型输出违反契约，
     * 必须立即报错，不能静默归类为 user。
     *
     * @param wireValue 外部提供的小写类型值
     * @return 对应的记忆类型
     */
    public static MemoryType fromWireValue(
            String wireValue
    ) {
        // null 表示外部数据缺少必填字段。
        if (wireValue == null) {
            throw new IllegalArgumentException(
                    "记忆类型不能为空"
            );
        }

        // 精确匹配唯一协议值，不接受别名或大小写兜底。
        return switch (wireValue) {
            case "user" ->
                    USER;
            case "feedback" ->
                    FEEDBACK;
            case "project" ->
                    PROJECT;
            case "reference" ->
                    REFERENCE;
            default ->
                    throw new IllegalArgumentException(
                            "未知记忆类型："
                                    + wireValue
                    );
        };
    }
}