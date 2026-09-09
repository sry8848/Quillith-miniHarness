// [结构] 声明项目记忆仓库所属的包。
package dev.learn.agent.manual.memory;

// [结构] 引入 YAML 序列化和工作区路径边界。
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

// [结构] 引入文件写入、原子移动和空值检查所需的标准库类型。
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;

// [结构] 引入目录扫描、链接检查、排序和列表所需类型。
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理当前工作区中的项目长期记忆。
 *
 * 提供主题文件的保存、列举、读取和删除，
 * 并维护供模型发现记忆的派生索引 MEMORY.md。
 */
public final class MemoryRepository {

    /*
     * [结构] 项目记忆固定通过工作区根目录的 .memory 入口访问。
     *
     * 统一入口让后续读取、索引和删除共享同一个存储边界。
     */
    private static final String MEMORY_DIRECTORY =
            ".memory";

    // [结构] 所有记忆路径都复用现有的工作区逃逸检查。
    private final WorkspacePathResolver paths;

    // [结构] 元数据交给 YAML 序列化器转义，避免手工拼接改变字段含义。
    private final ObjectMapper yamlMapper;

    /*
     * [结构] MEMORY.md 是根据主题文件生成的派生索引，
     * 不能作为普通主题记忆参与解析。
     */
    private static final String MEMORY_INDEX_FILENAME =
            "MEMORY.md";

    // [结构] 单个主题文件统一交给已有解析器执行格式和领域校验。
    private final MemoryFileParser parser;


    /**
     * [准备] 创建项目记忆仓库及其 YAML 序列化器。
     *
     * @param paths 工作区路径边界
     */
    public MemoryRepository(
            WorkspacePathResolver paths
    ) {
        // [边界：防止仓库脱离工作区路径边界运行]
        this.paths =
                Objects.requireNonNull(
                        paths,
                        "工作区路径解析器不能为空"
                );

        /*
         * [准备] 关闭 YAML 自带的文档起始标记。
         *
         * Markdown frontmatter 的 --- 由仓库统一添加，
         * 避免序列化器再生成一组重复标记。
         */
        YAMLFactory yamlFactory =
                YAMLFactory.builder()
                        .disable(
                                YAMLGenerator.Feature
                                        .WRITE_DOC_START_MARKER
                        )
                        .build();

        // [准备] 保存只负责数据输出、不启用多态类型等额外 YAML 能力的序列化器。
        this.yamlMapper =
                new ObjectMapper(
                        yamlFactory
                );

        // [结果] 保存可复用的主题文件解析器，供仓库读取操作使用。
        this.parser =
                new MemoryFileParser();
    }

    /**
     * [核心] 新增或原子替换主题记忆，并重建派生索引。
     *
     * 文件名由 {@link MemoryEntry#name()} 唯一决定。
     * 主题文件是事实来源；索引重建失败时，
     * 已经发布的主题文件不会回滚，但本次保存仍会抛出异常。
     *
     * @param entry 已通过领域契约校验的记忆条目
     * @return 最终发布的主题文件路径
     * @throws IOException 目录创建、YAML 序列化、主题发布或索引重建失败
     */
    public Path save(
            MemoryEntry entry
    ) throws IOException {
        // [边界：防止调用方保存不存在的记忆条目]
        Objects.requireNonNull(
                entry,
                "记忆条目不能为空"
        );

        /*
         * [准备] 先解析写入路径，再创建固定的记忆目录。
         *
         * WorkspacePathResolver 会拒绝真实目标逃出工作区，
         * 同时允许仍指向工作区内部的目录符号链接。
         */
        Path requestedDirectory =
                paths.resolveForWrite(
                        MEMORY_DIRECTORY
                );

        Files.createDirectories(
                requestedDirectory
        );

        /*
         * [准备] 创建完成后重新取得真实目录。
         *
         * 临时文件和正式文件都从同一真实目录派生，
         * 才能落在同一个文件系统中执行原子移动。
         */
        Path memoryDirectory =
                paths.resolveExisting(
                        MEMORY_DIRECTORY
                );

        /*
         * [准备] MemoryEntry 已保证名称可以安全映射为文件名，
         * 仓库只负责应用这一唯一映射规则。
         */
        Path targetFile =
                memoryDirectory.resolve(
                        entry.name()
                                + ".md"
                );

        // [准备] 按固定顺序构造用于索引和解析的 YAML 元数据。
        ObjectNode metadata =
                yamlMapper.createObjectNode();

        metadata.put(
                "name",
                entry.name()
        );

        metadata.put(
                "description",
                entry.description()
        );

        metadata.put(
                "type",
                entry.type()
                        .wireValue()
        );

        /*
         * [核心] 使用 YAML 序列化器生成元数据，
         * 保证冒号、引号和井号等字符得到正确转义。
         */
        String yaml =
                yamlMapper.writeValueAsString(
                                metadata
                        )
                        .stripTrailing();

        /*
         * [核心] 生成解析器能够重新读取的标准主题文件。
         *
         * 文件统一使用 LF，并以一个换行结束，
         * 便于跨平台进入 Git 仓库。
         */
        String content =
                "---\n"
                        + yaml
                        + "\n---\n\n"
                        + entry.body()
                        + "\n";

        /*
         * [核心] 先发布完整主题文件。
         *
         * 主题文件是长期记忆的事实来源，
         * 后续索引只能根据它重新生成。
         */
        writeAtomically(
                targetFile,
                content
        );

        /*
         * [核心] 主题文件发布后，根据全部合法主题重建索引。
         *
         * [边界：防止主题文件已更新但调用方误以为索引也已同步]
         * 重建失败必须向上抛出；更深层原因是索引决定 Agent
         * 能发现哪些记忆，静默成功会制造不可观察的召回缺失。
         */
        rebuildIndex();

        // [结果] 返回已经发布的主题文件路径。
        return targetFile;
    }

    /**
     * [核心] 列举当前项目中的全部主题记忆。
     * <p>
     * 结果按文件名排序，保证索引和调用方得到稳定顺序。
     * 任意主题文件违反契约时，本次列举整体失败。
     *
     * @return 按文件名排序的不可修改记忆列表
     * @throws IOException 目录扫描、真实路径解析或主题文件读取失败
     */
    public List<MemoryEntry> list() throws IOException {
        /*
         * [准备] 先得到 .memory 的词法入口。
         *
         * notExists 只有在文件系统能够确认目录不存在时才返回 true；
         * 无法判断的情况不会被静默当成空仓库。
         */
        Path requestedDirectory =
                paths.workspace()
                        .resolve(
                                MEMORY_DIRECTORY
                        );

        // [结果] 尚未创建 .memory 表示项目当前没有长期记忆。
        if (Files.notExists(
                requestedDirectory,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return List.of();
        }

        /*
         * [边界：防止记忆目录通过符号链接逃出工作区]
         * 已存在的目录必须重新解析并检查真实路径。
         */
        Path memoryDirectory =
                paths.resolveExisting(
                        MEMORY_DIRECTORY
                );

        // [准备] 先收集候选路径，再进行稳定排序和严格解析。
        List<Path> topicFiles =
                new ArrayList<>();

        /*
         * [边界：防止目录扫描流长期占用 Windows 文件句柄]
         * DirectoryStream 必须在扫描完成后立即关闭。
         */
        try (DirectoryStream<Path> files =
                     Files.newDirectoryStream(
                             memoryDirectory,
                             "*.md"
                     )) {
            // [核心] 收集主题 Markdown，并排除派生索引。
            for (Path file : files) {
                if (!MEMORY_INDEX_FILENAME.equals(
                        file.getFileName()
                                .toString()
                )) {
                    topicFiles.add(
                            file
                    );
                }
            }
        }

        // [准备] 文件名排序使扫描顺序不依赖文件系统返回顺序。
        topicFiles.sort(
                Comparator.comparing(
                        file -> file.getFileName()
                                .toString()
                )
        );

        // [准备] 为已经通过文件边界的主题创建领域对象列表。
        List<MemoryEntry> entries =
                new ArrayList<>(
                        topicFiles.size()
                );

        // [核心] 逐个解析主题文件，不为损坏记忆提供默认值或静默跳过。
        for (Path topicFile : topicFiles) {
            /*
             * [边界：防止一个主题文件通过符号链接形成别名或重复记忆]
             * .memory 目录入口可以解析工作区内部链接，
             * 但目录中的主题文件必须拥有自己的普通文件身份。
             */
            if (Files.isSymbolicLink(
                    topicFile
            )) {
                throw new IOException(
                        "记忆主题文件不能是符号链接："
                                + topicFile
                );
            }

            /*
             * [边界：防止扫描期间文件被替换后逃出真实记忆目录]
             * 解析后的主题文件仍必须直接位于 memoryDirectory 下。
             */
            Path realTopicFile =
                    topicFile.toRealPath();

            if (!memoryDirectory.equals(
                    realTopicFile.getParent()
            )) {
                throw new IOException(
                        "记忆主题文件必须直接位于记忆目录："
                                + topicFile
                );
            }

            // [核心] 文件格式和 MemoryEntry 领域规则统一由解析器校验。
            entries.add(
                    parser.parse(
                            realTopicFile
                    )
            );
        }

        // [结果] 返回不可修改快照，修改列表不会被误认为修改了磁盘仓库。
        return List.copyOf(
                entries
        );

    }

    /**
     * 将提取器候选作为全新的主题文件写入仓库。
     *
     * @param candidates 已通过领域校验的提取候选
     * @return 实际创建的记忆；同名候选会获得新的文件名
     * @throws IOException 读取或保存记忆失败
     */
    public List<MemoryEntry> createNew(
            List<MemoryEntry> candidates
    ) throws IOException {
        Objects.requireNonNull(candidates, "candidates 不能为空");

        // 1. 提取阶段不覆盖既有文件；短期同名重复交给整理器以后收敛。
        Set<String> usedNames = new HashSet<>();
        for (MemoryEntry entry : list()) {
            usedNames.add(entry.name());
        }

        List<MemoryEntry> created = new ArrayList<>();
        for (MemoryEntry candidate : candidates) {
            String name = candidate.name();
            int suffix = 2;
            while (!usedNames.add(name)) {
                name = candidate.name() + "-" + suffix++;
            }

            MemoryEntry createdEntry = name.equals(candidate.name())
                    ? candidate
                    : new MemoryEntry(name, candidate.type(), candidate.description(), candidate.body());
            save(createdEntry);
            created.add(createdEntry);
        }
        return List.copyOf(created);
    }

    /**
     * [核心] 根据全部合法主题文件重建 MEMORY.md。
     * <p>
     * 索引只包含可由 {@link #list()} 成功解析的记忆，
     * 不把索引自身或损坏文件当作事实来源。
     *
     * @throws IOException 主题扫描、解析或索引发布失败
     */
    private void rebuildIndex() throws IOException {
        /*
         * [核心] 从主题文件重新取得完整记忆集合。
         *
         * 不读取旧索引，是为了保持“主题文件产生索引”
         * 的单向依赖，避免两个数据来源互相覆盖。
         */
        List<MemoryEntry> entries =
                list();

        // [准备] 按 list() 的稳定顺序生成完整索引内容。
        StringBuilder index =
                new StringBuilder();

        // [核心] 每条合法记忆生成一行可按需加载的 Markdown 链接。
        for (MemoryEntry entry : entries) {
            index.append("- [")
                    .append(
                            entry.name()
                    )
                    .append("](")
                    .append(
                            entry.name()
                    )
                    .append(".md) — ")
                    .append(
                            entry.description()
                    )
                    .append('\n');
        }

        /*
         * [准备] 索引目标从已经验证的真实记忆目录派生。
         *
         * [边界：防止索引被写到主题目录之外]
         * 直接风险是 MEMORY.md 与主题文件分离；
         * 更深层原因是仓库将无法用一次目录扫描确定完整状态，
         * 事实来源和导航入口也会失去共同的隔离边界。
         */
        Path memoryDirectory =
                paths.resolveExisting(
                        MEMORY_DIRECTORY
                );

        Path indexFile =
                memoryDirectory.resolve(
                        MEMORY_INDEX_FILENAME
                );

        /*
         * [核心] 将完整索引作为一个文件系统目录项发布。
         *
         * 如果发布失败，旧索引仍可能过期，但不会被逐步截断成
         * 看似合法、实际只包含部分记忆的目录。
         */
        writeAtomically(
                indexFile,
                index.toString()
        );


    }

    /**
     * [核心] 通过同目录临时文件原子发布完整 UTF-8 文本。
     *
     * 该方法同时服务主题文件和 MEMORY.md，
     * 集中保持两类文件相同的发布语义。
     *
     * @param targetFile 最终目标文件
     * @param content 要完整发布的文本
     * @throws IOException 临时文件创建、写入、移动或清理失败
     */
    private void writeAtomically(
            Path targetFile,
            String content
    ) throws IOException {
        /*
         * [准备] 临时文件必须与目标位于同一真实目录。
         *
         * 直接原因是跨文件系统移动通常无法保证原子性；
         * 更深层原因是正式文件必须只呈现“旧版本或新版本”
         * 两种可解释状态，不能暴露中间写入过程。
         */
        Path parentDirectory =
                targetFile.getParent();

        Path temporaryFile =
                Files.createTempFile(
                        parentDirectory,
                        "." + targetFile.getFileName() + "-",
                        ".tmp"
                );

        try {
            // [核心] 先把全部 UTF-8 内容写入不可见于主题扫描的临时文件。
            Files.writeString(
                    temporaryFile,
                    content,
                    StandardCharsets.UTF_8
            );

            /*
             * [核心] 完整写入后再原子发布正式目录项。
             *
             * 这只保证当前文件系统操作不逐步暴露半截正式文件，
             * 不表示已经获得机器断电后的持久性保证。
             */
            Files.move(
                    temporaryFile,
                    targetFile,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException | RuntimeException failure) {
            /*
             * [边界：防止普通保存失败后遗留无归属的临时文件]
             * 直接风险是 .memory 持续堆积无效文件；
             * 更深层原因是恢复流程将无法判断临时文件属于
             * 已失败任务、仍在运行的任务，还是尚未发布的完整记忆。
             */
            try {
                Files.deleteIfExists(
                        temporaryFile
                );
            } catch (IOException cleanupFailure) {
                /*
                 * [边界：防止清理异常覆盖真正的保存失败]
                 * 保存失败决定业务结果，清理失败是附加诊断；
                 * 如果反过来覆盖，调用方会错误定位记忆未保存的原因。
                 */
                failure.addSuppressed(
                        cleanupFailure
                );
            }

            throw failure;
        }
    }

    /**
     * [核心] 根据稳定名称读取一条完整主题记忆。
     *
     * 调用方只能提供领域名称，不能提供任意文件路径；
     * 文件位置由仓库根据唯一映射规则生成。
     *
     * @param name 记忆的稳定名称
     * @return 已通过文件格式和领域契约校验的记忆
     * @throws IOException 记忆目录或主题文件不存在、越界或读取失败
     */
    public MemoryEntry read(
            String name
    ) throws IOException {
        /*
         * [边界：防止读取入口采用与保存入口不同的名称规则]
         * 直接风险是某些名称能够保存却不能读取；
         * 更深层原因是名称承担领域身份、文件名和索引键，
         * 三种用途必须共享一个契约才能保持可逆映射。
         */
        String validName =
                MemoryEntry.requireValidName(
                        name
                );

        /*
         * [边界：防止记忆目录的真实位置逃出授权工作区]
         * 直接风险是读取项目外文件；
         * 更深层原因是读取结果会进入模型上下文，
         * 越界内容可能绕过用户授权并被伪装成长期记忆。
         */
        Path memoryDirectory =
                paths.resolveExisting(
                        MEMORY_DIRECTORY
                );

        /*
         * [核心] 根据稳定名称生成唯一主题文件路径。
         *
         * 不接受调用方传入文件名或 Path，
         * 保证仓库始终控制名称到存储位置的映射。
         */
        Path topicFile =
                memoryDirectory.resolve(
                        validName
                                + ".md"
                );

        /*
         * [边界：防止主题文件通过符号链接形成第二存储身份]
         * 直接风险是同一真实文件通过多个名称被重复读取；
         * 更深层原因是更新、删除和 consolidation 必须能够判断
         * 两个名称代表两条记忆，而不是同一文件的两个别名。
         */
        if (Files.isSymbolicLink(
                topicFile
        )) {
            throw new IOException(
                    "记忆主题文件不能是符号链接："
                            + topicFile
            );
        }

        /*
         * [边界：防止读取期间目标被替换到真实记忆目录之外]
         * 直接风险是名称映射最终指向其他目录；
         * 更深层原因是仓库只能为 .memory 直接管理的文件
         * 提供身份、索引和生命周期保证。
         */
        Path realTopicFile =
                topicFile.toRealPath();

        if (!memoryDirectory.equals(
                realTopicFile.getParent()
        )) {
            throw new IOException(
                    "记忆主题文件必须直接位于记忆目录："
                            + topicFile
            );
        }

        /*
         * [核心] 统一执行 frontmatter、正文和文件名契约校验。
         *
         * 读取成功不只表示文件存在，
         * 还表示磁盘内容能够重新进入可信领域边界。
         */
        MemoryEntry entry =
                parser.parse(
                        realTopicFile
                );

        // [结果] 返回可以安全交给选择和上下文加载阶段的完整记忆。
        return entry;
    }

    /**
     * 根据稳定名称删除一条主题记忆，并重建派生索引。
     *
     * 损坏但仍是普通文件的主题允许删除，
     * 避免坏文件永久阻断后续索引重建。
     *
     * @param name 记忆的稳定名称
     * @throws IOException 记忆目录不存在、目标类型错误、删除或索引重建失败
     */
    public void delete(
            String name
    ) throws IOException {
        /*
         * [边界：当调用方传入路径片段、索引保留名或平台非法名称时，
         * 防止删除请求定位到普通主题记忆之外的目录项]
         */
        String validName =
                MemoryEntry.requireValidName(
                        name
                );

        Path memoryDirectory =
                paths.resolveExisting(
                        MEMORY_DIRECTORY
                );

        Path topicFile =
                memoryDirectory.resolve(
                        validName
                                + ".md"
                );

        /*
         * [边界：当同名路径被用户或其他工具创建成目录、设备等对象时，
         * 防止记忆删除操作移除并非由仓库创建的文件系统对象]
         *
         * 损坏的普通文件仍允许删除；符号链接也允许删除，
         * 因为 Files.delete() 删除的是链接目录项本身，不会删除其目标。
         */
        if (Files.exists(
                topicFile,
                LinkOption.NOFOLLOW_LINKS
        ) && !Files.isRegularFile(
                topicFile,
                LinkOption.NOFOLLOW_LINKS
        ) && !Files.isSymbolicLink(
                topicFile
        )) {
            throw new IOException(
                    "记忆删除目标必须是普通文件或符号链接："
                            + topicFile
            );
        }

        /*
         * [核心] 删除名称对应的主题目录项。
         *
         * 使用 delete 而不是 deleteIfExists；
         * 目标不存在时必须报错，不能让调用方误认为删除已经生效。
         */
        Files.delete(
                topicFile
        );

        /*
         * [核心] 根据剩余主题文件重建索引。
         *
         * 如果重建失败，删除不会回滚，方法会继续抛出异常；
         * 此时主题文件是新事实，旧索引可能暂时引用已删除记忆。
         */
        rebuildIndex();
    }

    /**
     * 读取当前项目的 MEMORY.md 索引。
     *
     * .memory 从未创建，或目录只包含非记忆运行数据时返回空字符串；
     * 主题文件已经存在但索引缺失时视为状态不一致。
     *
     * @return 去掉首尾空白的索引文本；没有记忆目录时返回空字符串
     * @throws IOException 记忆目录或索引状态异常、读取失败
     */
    public String readIndex() throws IOException {
        Path requestedDirectory =
                paths.workspace()
                        .resolve(
                                MEMORY_DIRECTORY
                        );

        // 从未创建记忆目录，表示项目尚无长期记忆。
        if (Files.notExists(
                requestedDirectory,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return "";
        }

        /*
         * [边界：当 .memory 是指向工作区外部的链接时，
         * 防止项目外文件被读取并作为长期记忆目录交给模型]
         *
         * 仓库内容或用户操作都可能创建目录链接；
         * 真实位置必须继续受 WorkspacePathResolver 检查。
         */
        Path memoryDirectory =
                paths.resolveExisting(
                        MEMORY_DIRECTORY
                );

        /*
         * [边界：当 .memory 被普通文件或其他对象占用时，
         * 防止后续把虚构的子路径当成缺失索引并掩盖目录损坏]
         */
        if (!Files.isDirectory(
                memoryDirectory
        )) {
            throw new IOException(
                    "记忆存储位置必须是目录："
                            + memoryDirectory
            );
        }

        Path indexFile =
                memoryDirectory.resolve(
                        MEMORY_INDEX_FILENAME
                );

        // 1. 索引缺失时先区分空记忆仓库和已有主题的损坏仓库。
        if (Files.notExists(
                indexFile,
                LinkOption.NOFOLLOW_LINKS
        )) {
            if (list().isEmpty()) {
                return "";
            }

            /*
             * SessionStore 也会在 .memory 中保存 sessions.db，
             * 所以目录存在本身不能再证明长期记忆已经初始化。
             */
            throw new NoSuchFileException(
                    indexFile.toString()
            );
        }

        /*
         * [边界：当 MEMORY.md 是指向其他文件的符号链接时，
         * 防止链接目标内容绕过主题文件解析并直接进入模型上下文]
         */
        if (Files.isSymbolicLink(
                indexFile
        )) {
            throw new IOException(
                    "记忆索引不能是符号链接："
                            + indexFile
            );
        }

        /*
         * [边界：当 MEMORY.md 是目录、管道或设备文件时，
         * 防止索引读取阻塞，或把非记忆数据当成目录内容]
         */
        if (!Files.isRegularFile(
                indexFile,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw new IOException(
                    "记忆索引必须是普通文件："
                            + indexFile
            );
        }

        // [核心] 读取由 rebuildIndex() 生成的完整 UTF-8 索引。
        String index =
                Files.readString(
                        indexFile,
                        StandardCharsets.UTF_8
                );

        return index.strip();
    }
}
