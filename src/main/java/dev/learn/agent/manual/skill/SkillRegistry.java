package dev.learn.agent.manual.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 保存程序启动时发现的全部技能。
 *
 * 注册表以技能名称为唯一键，并保持目录扫描后的稳定顺序。
 * 创建完成后不再修改，保证同一次 Agent 会话看到的技能集合不变。
 */
public final class SkillRegistry {

    private final Path skillsDirectory;

    private final Map<String, SkillDefinition> skills;

    /**
     * 扫描指定技能根目录并建立不可修改的注册表。
     *
     * @param skillsDirectory 包含各个技能子目录的根目录
     * @throws IOException 技能目录或 SKILL.md 无法访问
     */
    public SkillRegistry(
            Path skillsDirectory
    ) throws IOException {
        this.skillsDirectory =
                Objects.requireNonNull(
                                skillsDirectory,
                                "技能根目录不能为空"
                        )
                        .toRealPath();

        if (!Files.isDirectory(
                this.skillsDirectory
        )) {
            throw new IllegalArgumentException(
                    "技能根路径必须是目录："
                            + this.skillsDirectory
            );
        }

        SkillFileParser parser =
                new SkillFileParser();

        Map<String, SkillDefinition> loadedSkills =
                new LinkedHashMap<>();

        /*
         * Files.list 返回的 Stream 持有目录句柄，
         * 必须通过 try-with-resources 及时关闭。
         */
        List<Path> candidateDirectories;

        try (
                Stream<Path> entries =
                        Files.list(
                                this.skillsDirectory
                        )
        ) {
            candidateDirectories =
                    entries
                            /*
                             * 技能根目录下的普通文件不是技能候选项。
                             * 每个技能必须拥有自己的一级子目录。
                             */
                            .filter(
                                    Files::isDirectory
                            )
                            .sorted()
                            .toList();
        }

        for (Path candidate : candidateDirectories) {
            registerDirectory(
                    candidate,
                    parser,
                    loadedSkills
            );
        }

        /*
         * 复制后再包装为不可修改 Map，
         * 防止构造阶段的临时 Map 被外部继续修改。
         *
         * LinkedHashMap 保留扫描顺序，使系统提示词稳定。
         */
        this.skills =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                loadedSkills
                        )
                );
    }

    /**
     * 生成提供给模型发现技能的轻量目录。
     *
     * 目录只包含名称和描述，不包含完整操作说明。
     * 完整说明必须由 load_skill 在真正需要时加载。
     *
     * @return 按稳定注册顺序排列的技能目录
     */
    public String catalog() {
        if (skills.isEmpty()) {
            return "(no skills available)";
        }

        return skills.values()
                .stream()
                .map(
                        skill ->
                                "- "
                                        + skill.name()
                                        + ": "
                                        /*
                                         * YAML 允许使用多行 description。
                                         *
                                         * 技能目录中每个技能固定占一行，
                                         * 既保持结构清楚，也避免多行描述看起来像新条目。
                                         */
                                        + skill.description()
                                        /*
                                         * 将所有空格替换为单个空格
                                         */
                                        .replaceAll(
                                                "\\s+",
                                                " "
                                        )
                )
                .collect(
                        Collectors.joining(
                                "\n"
                        )
                );
    }

    /**
     * 按标准名称查找技能。
     *
     * 未找到属于正常查询结果，由 load_skill 决定如何回传模型，
     * 因此这里使用 Optional 明确表达“可能不存在”。
     *
     * @param name 模型请求加载的技能名称
     * @return 对应技能；不存在时返回空 Optional
     */
    public Optional<SkillDefinition> find(
            String name
    ) {
        Objects.requireNonNull(
                name,
                "技能名称不能为空"
        );

        return Optional.ofNullable(
                skills.get(
                        name
                )
        );
    }

    /**
     * 解析并注册一个技能子目录。
     *
     * 目录路径、SKILL.md 真实路径和技能名称都在这里完成关联校验，
     * 通过后才允许进入注册表。
     */
    private void registerDirectory(
            Path candidate,
            SkillFileParser parser,
            Map<String, SkillDefinition> loadedSkills
    ) throws IOException {
        Path realSkillDirectory =
                candidate.toRealPath();

        /*
         * Files.isDirectory 默认会跟随符号链接。
         *
         * 如果 skills/ 下的链接实际指向外部目录，
         * 真实路径检查会在读取 SKILL.md 前拒绝它。
         */
        if (!realSkillDirectory.startsWith(
                skillsDirectory
        )) {
            throw new IllegalArgumentException(
                    "技能目录不能离开技能根目录："
                            + candidate
            );
        }

        Path manifest =
                realSkillDirectory.resolve(
                        "SKILL.md"
                );

        if (!Files.isRegularFile(
                manifest
        )) {
            throw new IllegalArgumentException(
                    "技能目录缺少 SKILL.md："
                            + realSkillDirectory
            );
        }

        SkillDefinition skill =
                parser.parse(
                        manifest
                );

        /*
         * SKILL.md 本身也可能是符号链接，
         * 因此解析后再次检查其真实父目录。
         */
        if (!skill.directory()
                .startsWith(
                        skillsDirectory
                )) {
            throw new IllegalArgumentException(
                    "SKILL.md 不能离开技能根目录："
                            + manifest
            );
        }

        String directoryName =
                realSkillDirectory
                        .getFileName()
                        .toString();

        if (!directoryName.equals(
                skill.name()
        )) {
            throw new IllegalArgumentException(
                    "技能名称必须与父目录一致：目录为 "
                            + directoryName
                            + "，name 为 "
                            + skill.name()
            );
        }

        SkillDefinition previous =
                loadedSkills.putIfAbsent(
                        skill.name(),
                        skill
                );

        if (previous != null) {
            throw new IllegalArgumentException(
                    "存在重复技能名称："
                            + skill.name()
            );
        }
    }

}