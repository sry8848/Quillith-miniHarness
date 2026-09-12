package dev.learn.agent.manual.systemprompt;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 llmwiki 根索引进入 System Prompt，以及运行中开关的效果。 */
class MemorySystemPromptProviderTest {

    @TempDir
    Path workspace;

    /** Session 数据库存在但根索引缺失时，不注入空记忆 section。 */
    @Test
    void treatsSessionDatabaseWithoutRootIndexAsEmptyMemory() throws IOException {
        SessionState state = sessionState(true);
        AnthropicClient client = testClient();
        try (SessionStore ignored = new SessionStore(workspace);
             MemoryRuntime runtime = runtime(client, state)) {
            SystemPromptManager manager = manager(runtime);

            // 1. sessions.db 是原始运行事实，不属于 llmwiki 导航内容。
            SystemPrompt prompt = manager.refreshFrom(RefreshScope.APPLICATION, state);
            assertFalse(prompt.content().contains("<memory>"));
        } finally {
            client.close();
        }
    }

    /** 根索引存在时只注入导航内容和中文渐进读取说明。 */
    @Test
    void loadsRootIndexIntoSystemPrompt() throws IOException {
        Path llmwiki = Files.createDirectories(workspace.resolve(".memory/llmwiki"));
        Files.writeString(llmwiki.resolve("index.md"),
                "- [coding-style.md](coding-style.md) — 项目编码风格\n");
        SessionState state = sessionState(true);
        AnthropicClient client = testClient();
        try (MemoryRuntime runtime = runtime(client, state)) {
            SystemPrompt prompt = manager(runtime).refreshFrom(RefreshScope.APPLICATION, state);

            // 1. 根索引常驻，但正文不会被同步加载。
            assertTrue(prompt.content().contains("coding-style.md"));
            assertTrue(prompt.content().contains("项目编码风格"));
            assertTrue(prompt.content().contains("目录被折叠时"));
            assertTrue(prompt.content().contains(".memory/llmwiki/"));
            assertFalse(prompt.content().contains("一次最多五条"));
        } finally {
            client.close();
        }
    }

    /** 运行中关闭再开启 Memory 时，根索引 section 随 Session 状态增删。 */
    @Test
    void togglesMemorySectionAtRuntime() throws IOException {
        Path llmwiki = Files.createDirectories(workspace.resolve(".memory/llmwiki"));
        Files.writeString(llmwiki.resolve("index.md"), "- [fact.md](fact.md) — 项目事实\n");
        SessionState state = sessionState(true);
        AnthropicClient client = testClient();
        try (MemoryRuntime runtime = runtime(client, state)) {
            SystemPromptManager manager = manager(runtime);
            assertTrue(manager.refreshFrom(RefreshScope.APPLICATION, state).content().contains("fact.md"));

            // 1. MemoryRuntime 和 Provider 读取同一 SessionState，不维护第二份开关。
            runtime.setEnabled(false);
            assertFalse(manager.refreshFrom(RefreshScope.SESSION, state).content().contains("<memory>"));
            runtime.setEnabled(true);
            assertTrue(manager.refreshFrom(RefreshScope.SESSION, state).content().contains("fact.md"));
        } finally {
            client.close();
        }
    }

    /** 创建只包含 Memory Provider 的 Prompt Manager。 */
    private static SystemPromptManager manager(MemoryRuntime runtime) {
        return new SystemPromptManager(List.of(new MemorySystemPromptProvider(runtime)));
    }

    /** 创建复用指定 SessionState 的真实 MemoryRuntime。 */
    private MemoryRuntime runtime(AnthropicClient client, SessionState state) throws IOException {
        return new MemoryRuntime(state, client, "test-model", new WorkspacePathResolver(state), bashExecutable());
    }

    /** 创建当前临时工作区的 SessionState。 */
    private SessionState sessionState(boolean enabled) {
        return new SessionState(enabled, ToolApprovalMode.BYPASS,
                workspace, workspace, List.of(workspace), null);
    }

    /** 返回测试宿主上已经存在的 Bash。 */
    private static Path bashExecutable() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? Path.of("C:/Windows/System32/bash.exe")
                : Path.of("/bin/bash");
    }

    /** 创建不会在索引测试中发出网络请求的客户端。 */
    private static AnthropicClient testClient() {
        return AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost")
                .build();
    }
}
