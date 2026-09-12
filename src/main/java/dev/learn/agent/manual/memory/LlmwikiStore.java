package dev.learn.agent.manual.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 执行 llmwiki 边界上的固定文件操作，不解释或整理完整记忆树。
 */
public final class LlmwikiStore {

    private static final String LLMWIKI_DIRECTORY = ".memory/llmwiki";
    private static final String RECENT_DIRECTORY = "recent-unorganized";
    private static final String ROOT_INDEX = "index.md";

    // 所有规范记忆、派生索引和独立 Git 历史都位于该目录。
    private final Path root;

    // YAML 序列化器只负责正确转义固定的 frontmatter 字段。
    private final ObjectMapper yamlMapper;

    /**
     * 创建 llmwiki 和近期目录，并准备固定 frontmatter 的序列化器。
     *
     * @param paths 当前项目工作区的路径边界
     * @throws IOException 目录无法创建或解析
     */
    public LlmwikiStore(WorkspacePathResolver paths) throws IOException {
        Objects.requireNonNull(paths, "WorkspacePathResolver 不能为空");

        // 1. 先通过项目工作区边界创建规范记忆根和近期目录。
        Path requestedRoot = paths.resolveForWrite(LLMWIKI_DIRECTORY);
        Files.createDirectories(requestedRoot.resolve(RECENT_DIRECTORY));
        this.root = paths.resolveExisting(LLMWIKI_DIRECTORY);

        // 2. 关闭 YAML 自带的文档起始标记，frontmatter 分隔线由 Store 统一写入。
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        this.yamlMapper = new ObjectMapper(yamlFactory);
    }

    /**
     * 把一批提取草稿写入近期目录，并一次性追加根索引入口。
     *
     * @param drafts 已由提取器校验的记忆草稿
     * @return 实际创建的绝对文件路径
     * @throws IOException 文件或根索引无法写入
     */
    public List<Path> writeRecent(List<MemoryDraft> drafts) throws IOException {
        Objects.requireNonNull(drafts, "记忆草稿列表不能为空");
        if (drafts.isEmpty()) {
            return List.of();
        }

        Path recentDirectory = root.resolve(RECENT_DIRECTORY);
        Set<String> batchNames = new HashSet<>();
        List<Path> createdPaths = new ArrayList<>(drafts.size());
        List<String> indexLines = new ArrayList<>(drafts.size());

        // 1. 每个候选只在近期目录选择一个未占用名称，不覆盖尚未整理的同名文件。
        for (MemoryDraft draft : drafts) {
            String filename = availableFilename(recentDirectory, draft.name(), batchNames);
            Path targetFile = recentDirectory.resolve(filename);
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

            // 2. 创建时间来自本机，提取模型不负责生成或推测时间。
            writeAtomically(targetFile, serialize(draft, timestamp));
            createdPaths.add(targetFile);
            indexLines.add("- [" + RECENT_DIRECTORY + "/" + filename + "](" + RECENT_DIRECTORY + "/"
                    + filename + ") — " + draft.description());
        }

        // 3. 全部候选发布后一次性追加根索引，避免一批候选重复改写同一个文件。
        Path rootIndex = root.resolve(ROOT_INDEX);
        String existingIndex = Files.exists(rootIndex)
                ? Files.readString(rootIndex, StandardCharsets.UTF_8)
                : "";
        StringBuilder updatedIndex = new StringBuilder(existingIndex);
        if (!updatedIndex.isEmpty() && updatedIndex.charAt(updatedIndex.length() - 1) != '\n') {
            updatedIndex.append('\n');
        }
        for (String indexLine : indexLines) {
            updatedIndex.append(indexLine).append('\n');
        }
        writeAtomically(rootIndex, updatedIndex.toString());
        return List.copyOf(createdPaths);
    }

    /**
     * 读取供主 Agent 常驻感知的根索引原文。
     *
     * @return 不存在或空白时为空，否则返回去除首尾空白的文本
     * @throws IOException 索引无法读取
     */
    public Optional<String> readRootIndex() throws IOException {
        Path rootIndex = root.resolve(ROOT_INDEX);
        if (Files.notExists(rootIndex)) {
            return Optional.empty();
        }

        String index = Files.readString(rootIndex, StandardCharsets.UTF_8).strip();
        return index.isEmpty() ? Optional.empty() : Optional.of(index);
    }

    /**
     * 返回整理 Agent 与 Store 共用的规范记忆根。
     *
     * @return llmwiki 的真实绝对路径
     */
    Path root() {
        return root;
    }

    /**
     * 选择不会覆盖已有近期候选的文件名。
     *
     * @param directory 近期候选目录
     * @param name 未带扩展名的稳定名称
     * @param batchNames 本批已占用的文件名
     * @return 未被文件系统或本批其他候选占用的 Markdown 文件名
     */
    private static String availableFilename(Path directory, String name, Set<String> batchNames) {
        String candidate = name + ".md";
        int suffix = 2;
        while (Files.exists(directory.resolve(candidate)) || !batchNames.add(candidate)) {
            candidate = name + "-" + suffix++ + ".md";
        }
        return candidate;
    }

    /**
     * 将草稿序列化为固定 frontmatter 和 Markdown 正文。
     *
     * @param draft 已校验的记忆草稿
     * @param timestamp 同时用作创建时间和更新时间的 ISO-8601 时间
     * @return 可直接写入候选文件的完整文本
     * @throws IOException YAML 无法序列化
     */
    private String serialize(MemoryDraft draft, String timestamp) throws IOException {
        ObjectNode metadata = yamlMapper.createObjectNode();
        metadata.put("description", draft.description());
        metadata.put("created_at", timestamp);
        metadata.put("updated_at", timestamp);
        String yaml = yamlMapper.writeValueAsString(metadata).stripTrailing();
        return "---\n" + yaml + "\n---\n\n" + draft.body() + "\n";
    }

    /**
     * 通过同目录临时文件原子发布完整 UTF-8 文本。
     *
     * @param targetFile 最终目标文件
     * @param content 要发布的完整内容
     * @throws IOException 临时文件创建、写入、发布或清理失败
     */
    private static void writeAtomically(Path targetFile, String content) throws IOException {
        Path temporaryFile = Files.createTempFile(targetFile.getParent(),
                "." + targetFile.getFileName() + "-", ".tmp");
        try {
            // 1. 正式路径只在完整内容写完后出现，避免读取到半截 Markdown。
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporaryFile, targetFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                // 清理失败只补充诊断，不能覆盖真正的发布失败。
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
