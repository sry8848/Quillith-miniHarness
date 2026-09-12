package dev.learn.agent.manual.memory;

import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证近期候选写入和根索引读取的固定文件契约。 */
class LlmwikiStoreTest {

    @TempDir
    Path workspace;

    /** 新候选同时写入三个 frontmatter 字段和根索引入口。 */
    @Test
    void writesRecentMemoryAndRootIndex() throws Exception {
        LlmwikiStore store = store();

        // 1. 写入一条已经通过提取器校验的草稿。
        List<Path> created = store.writeRecent(List.of(
                new MemoryDraft("coding-style", "项目编码风格", "# 编码风格\n\n保持实现简单。")
        ));

        assertEquals(1, created.size());
        String content = Files.readString(created.getFirst());
        assertEquals("项目编码风格", yamlValue(content, "description"));
        assertFalse(content.contains("name:"));
        assertFalse(content.contains("type:"));
        assertTrue(content.contains("# 编码风格"));

        // 2. 创建时间和更新时间来自同一次写入，值相同且是合法 ISO-8601。
        String createdAt = yamlValue(content, "created_at");
        String updatedAt = yamlValue(content, "updated_at");
        assertEquals(createdAt, updatedAt);
        Instant.parse(createdAt);

        String index = store.readRootIndex().orElseThrow();
        assertTrue(index.contains("[recent-unorganized/coding-style.md]"));
        assertTrue(index.contains("项目编码风格"));
    }

    /** 同名候选只添加文件名后缀，不覆盖已有文件。 */
    @Test
    void addsSuffixForDuplicateNames() throws Exception {
        LlmwikiStore store = store();
        MemoryDraft draft = new MemoryDraft("project-fact", "项目事实", "正文");

        // 1. 两批同名草稿都应成为可见候选，后续由整理 Agent 判断是否合并。
        Path first = store.writeRecent(List.of(draft)).getFirst();
        Path second = store.writeRecent(List.of(draft)).getFirst();

        assertEquals("project-fact.md", first.getFileName().toString());
        assertEquals("project-fact-2.md", second.getFileName().toString());
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    /** 根索引不存在或只有空白时返回空，有正文时返回清理后的原文。 */
    @Test
    void readsOptionalRootIndex() throws Exception {
        LlmwikiStore store = store();
        assertTrue(store.readRootIndex().isEmpty());

        Path index = store.root().resolve("index.md");
        Files.writeString(index, "  \n");
        assertTrue(store.readRootIndex().isEmpty());

        Files.writeString(index, "\n- [fact.md](fact.md) — 事实\n");
        assertEquals("- [fact.md](fact.md) — 事实", store.readRootIndex().orElseThrow());
    }

    /** 创建绑定临时工作区的 Store。 */
    private LlmwikiStore store() throws Exception {
        SessionState state = new SessionState(true, ToolApprovalMode.BYPASS,
                workspace, workspace, List.of(workspace), null);
        return new LlmwikiStore(new WorkspacePathResolver(state));
    }

    /** 读取简单 YAML frontmatter 中一个标量字段。 */
    private static String yamlValue(String content, String fieldName) {
        String prefix = fieldName + ": ";
        return content.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).replace("\"", ""))
                .findFirst()
                .orElseThrow();
    }
}
