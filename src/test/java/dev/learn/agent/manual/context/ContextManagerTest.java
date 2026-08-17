// 声明上下文管理器测试所属的包。
package dev.learn.agent.manual.context;

// 引入 Anthropic 客户端，用于满足 ContextManager 的模型依赖契约。
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageParam;

// 引入工作区路径边界和 JUnit 测试能力。
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 引入文件、集合和流操作。
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证上下文有损裁剪的持久化契约。
 */
class ContextManagerTest {

    // JUnit 为每个测试提供独立的真实工作区。
    @TempDir
    Path workspace;

    /**
     * 验证中段消息被删除前保存完整 transcript，并向模型提供恢复路径。
     *
     * @throws IOException 创建工作区边界或读取 transcript 失败
     */
    @Test
    void shouldArchiveCompleteHistoryBeforeSnippingMiddle()
            throws IOException {
        // 创建无需真正发起网络请求的客户端。
        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .apiKey(
                                "test-key"
                        )
                        .build();

        try {
            // 使用临时工作区组装本次测试的上下文管理器。
            ContextManager contextManager =
                    new ContextManager(
                            client,
                            "test-model",
                            new WorkspacePathResolver(
                                    workspace
                            )
                    );

            // 构造超过五十条软目标的历史，确保本次调用真正发生中段裁剪。
            List<MessageParam> messages =
                    new ArrayList<>();

            for (int index = 0;
                 index < 60;
                 index++) {
                MessageParam.Role role =
                        index % 2 == 0
                                ? MessageParam.Role.USER
                                : MessageParam.Role.ASSISTANT;

                messages.add(
                        MessageParam.builder()
                                .role(
                                        role
                                )
                                .content(
                                        "message-" + index
                                )
                                .build()
                );
            }

            // 执行有损裁剪，并取得占位消息中的 transcript 引用。
            List<MessageParam> compacted =
                    contextManager.snipMiddle(
                            messages
                    );

            String marker =
                    compacted.get(3)
                            .content()
                            .asString();

            // 裁剪结果保持既有五十条目标，并明确给出工作区相对恢复路径。
            assertEquals(
                    50,
                    compacted.size()
            );

            assertTrue(
                    marker.contains(
                            ".transcripts/context-"
                    )
            );

            // 检查磁盘中只产生一份 transcript，且完整保留裁剪前的六十条消息。
            Path transcriptDirectory =
                    workspace.resolve(
                            ".transcripts"
                    );

            List<Path> transcripts;

            try (Stream<Path> paths =
                         Files.list(
                                 transcriptDirectory
                         )) {
                transcripts =
                        paths.toList();
            }

            assertEquals(
                    1,
                    transcripts.size()
            );

            assertEquals(
                    60,
                    Files.readAllLines(
                            transcripts.getFirst()
                    ).size()
            );
        } finally {
            // AnthropicClient 提供 close()，但未实现 AutoCloseable，需要显式释放。
            client.close();
        }
    }
}
