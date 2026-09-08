package dev.learn.agent.manual.systemprompt;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.memory.MemoryEntry;
import dev.learn.agent.manual.memory.MemoryRepository;
import dev.learn.agent.manual.memory.MemoryRuntime;
import dev.learn.agent.manual.memory.MemoryTurnResult;
import dev.learn.agent.manual.memory.MemoryType;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MEMORY.md 索引进入 System Prompt，以及运行中开关的效果。
 */
class MemorySystemPromptProviderTest {

    // 为每个测试提供隔离的工作区。
    @TempDir
    Path workspace;

    /**
     * Given SessionStore 已创建 .memory，when 还没有长期记忆主题，then 启动得到空索引。
     */
    @Test
    void treatsSessionDatabaseWithoutMemoryTopicsAsEmptyMemory()
            throws IOException {
        AnthropicClient client =
                testClient();
        try (SessionStore ignored =
                     new SessionStore(
                             workspace
                     )) {
            SessionState sessionState =
                    new SessionState(
                            true,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            null
                    );
            MemoryRuntime runtime =
                    new MemoryRuntime(
                            sessionState,
                            client,
                            "test-model",
                            new WorkspacePathResolver(
                                    sessionState
                            )
                    );
            SystemPromptManager manager =
                    new SystemPromptManager(
                            List.of(
                                    new MemorySystemPromptProvider(
                                            runtime
                                    )
                            )
                    );

            // 1. sessions.db 不属于长期记忆主题，不能阻止全新 workspace 启动。
            SystemPrompt prompt =
                    manager.refreshFrom(
                            RefreshScope.APPLICATION,
                            sessionState
                    );

            assertFalse(
                    prompt.content()
                            .contains(
                                    "<memory>"
                            )
            );
        } finally {
            client.close();
        }
    }

    /**
     * Given 已有长期记忆主题，when MEMORY.md 丢失，then 仍暴露仓库损坏。
     */
    @Test
    void rejectsMemoryTopicsWithoutIndex()
            throws IOException {
        Path memoryDirectory =
                Files.createDirectories(
                        workspace.resolve(
                                ".memory"
                        )
                );
        Files.writeString(
                memoryDirectory.resolve(
                        "project-fact.md"
                ),
                "---\n"
                        + "name: project-fact\n"
                        + "description: 项目事实\n"
                        + "type: project\n"
                        + "---\n"
                        + "正文\n"
        );
        AnthropicClient client =
                testClient();
        try {
            SessionState sessionState =
                    new SessionState(
                            true,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            null
                    );
            MemoryRuntime runtime =
                    new MemoryRuntime(
                            sessionState,
                            client,
                            "test-model",
                            new WorkspacePathResolver(
                                    sessionState
                            )
                    );

            // 1. 已有主题意味着索引缺失是真实不一致，不能作为空仓库读取。
            assertThrows(
                    IllegalStateException.class,
                    runtime::loadIndex
            );
        } finally {
            client.close();
        }
    }

    /**
     * Given 已保存记忆，when 刷新 System Prompt，then 只注入索引内容。
     */
    @Test
    void loadsMemoryIndexIntoSystemPrompt()
            throws IOException {
        AnthropicClient client =
                testClient();
        try {
            SessionState sessionState =
                    new SessionState(
                            true,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            null
                    );
            WorkspacePathResolver paths =
                    new WorkspacePathResolver(
                            sessionState
                    );
            MemoryRepository repository =
                    new MemoryRepository(
                            paths
                    );
            repository.save(
                    new MemoryEntry(
                            "coding-style",
                            MemoryType.PROJECT,
                            "项目编码风格",
                            "正文不应在索引注入时加载"
                    )
            );
            MemoryRuntime runtime =
                    new MemoryRuntime(
                            sessionState,
                            client,
                            "test-model",
                            paths
                    );
            SystemPromptManager manager =
                    new SystemPromptManager(
                            List.of(
                                    new MemorySystemPromptProvider(
                                            runtime
                                    )
                            )
                    );

            SystemPrompt prompt =
                    manager.refreshFrom(
                            RefreshScope.APPLICATION,
                            sessionState
                    );

            assertTrue(
                    prompt.content()
                            .contains(
                                    "<memory>"
                            )
            );
            assertTrue(
                    prompt.content()
                            .contains(
                                    "coding-style"
                            )
            );
            assertTrue(
                    prompt.content()
                            .contains(
                                    "项目编码风格"
                            )
            );
            assertFalse(
                    prompt.content()
                            .contains(
                                    "正文不应在索引注入时加载"
                            )
            );
        } finally {
            client.close();
        }
    }

    /**
     * Given 已加载记忆，when 运行中切换开关，then System Prompt section 随之增删。
     */
    @Test
    void togglesMemorySectionAtRuntime()
            throws IOException {
        AnthropicClient client =
                testClient();
        try {
            SessionState sessionState =
                    new SessionState(
                            true,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            null
                    );
            WorkspacePathResolver paths =
                    new WorkspacePathResolver(
                            sessionState
                    );
            MemoryRepository repository =
                    new MemoryRepository(
                            paths
                    );
            repository.save(
                    new MemoryEntry(
                            "project-fact",
                            MemoryType.PROJECT,
                            "项目事实",
                            "正文"
                    )
            );
            MemoryRuntime runtime =
                    new MemoryRuntime(
                            sessionState,
                            client,
                            "test-model",
                            paths
                    );
            SystemPromptManager manager =
                    new SystemPromptManager(
                            List.of(
                                    new MemorySystemPromptProvider(
                                            runtime
                                    )
                            )
                    );
            assertTrue(
                    manager.refreshFrom(
                                    RefreshScope.APPLICATION,
                                    sessionState
                            )
                            .content()
                            .contains(
                                    "project-fact"
                            )
            );

            sessionState.setMemoryEnabled(
                    false
            );
            assertFalse(
                    manager.refreshFrom(
                                    RefreshScope.SESSION,
                                    sessionState
                            )
                            .content()
                            .contains(
                                    "<memory>"
                            )
            );

            sessionState.setMemoryEnabled(
                    true
            );
            assertTrue(
                    manager.refreshFrom(
                                    RefreshScope.SESSION,
                                    sessionState
                            )
                            .content()
                            .contains(
                                    "project-fact"
                            )
            );
        } finally {
            client.close();
        }
    }

    /**
     * Given 记忆关闭，when 调用记忆生命周期方法，then 不产生记忆结果。
     */
    @Test
    void disablesAllMemoryRuntimeOperations()
            throws IOException {
        AnthropicClient client =
                testClient();
        try {
            SessionState sessionState =
                    new SessionState(
                            false,
                            ToolApprovalMode.BYPASS,
                            workspace,
                            workspace,
                            List.of(workspace),
                            null
                    );
            WorkspacePathResolver paths =
                    new WorkspacePathResolver(
                            sessionState
                    );
            MemoryRuntime runtime =
                    new MemoryRuntime(
                            sessionState,
                            client,
                            "test-model",
                            paths
                    );

            assertEquals(
                    "",
                    runtime.recall(
                            "query"
                    )
            );
            assertEquals(
                    List.of(),
                    runtime.capture(
                            List.of()
                    )
            );
            assertEquals(
                    MemoryTurnResult.NONE,
                    runtime.completeTurn(
                            List.of()
                    )
            );
        } finally {
            client.close();
        }
    }

    private static AnthropicClient testClient() {
        return AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost")
                .build();
    }
}
