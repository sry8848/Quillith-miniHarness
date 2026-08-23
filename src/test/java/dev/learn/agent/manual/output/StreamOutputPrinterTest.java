// 声明流式输出打印器测试所在的包。
package dev.learn.agent.manual.output;

// 引入工具调用 JSON、SDK 类型和项目工具结果类型。
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.ToolUseBlock;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 引入测试所需的内存输出流、路径和集合类型。
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证模型流结束与本地工具结果之间的追踪生命周期契约。
 */
class StreamOutputPrinterTest {

    // [边界：模型流先结束、工具结果后到达 → 两者必须保存在同一个请求追踪中]
    @TempDir
    Path temporaryDirectory;

    /**
     * Given 一个已经输出工具调用的模型请求，
     * when 模型流先结束且工具结果随后到达，
     * then 结果应成功写入，并且请求结束记录位于工具结果之后。
     *
     * @throws IOException 临时工作区或追踪文件读取失败
     */
    @Test
    void keepsRequestOpenUntilPendingToolResultIsRecorded()
            throws IOException {
        // 创建只写入内存终端和临时工作区的打印器。
        ByteArrayOutputStream terminalBytes =
                new ByteArrayOutputStream();
        StreamOutputPrinter printer =
                new StreamOutputPrinter(
                        new WorkspacePathResolver(
                                temporaryDirectory
                        ),
                        new PrintStream(
                                terminalBytes,
                                true,
                                StandardCharsets.UTF_8
                        ),
                        "test-session",
                        "parent"
                );

        // 构造一个与故障现场 glob 调用等价的完整工具调用。
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id(
                                "toolu_test"
                        )
                        .name(
                                "glob"
                        )
                        .caller(
                                DirectCaller.builder()
                                        .build()
                        )
                        .input(
                                JsonValue.from(
                                        Map.of(
                                                "pattern",
                                                "**/*"
                                        )
                                )
                        )
                        .build();

        // 复现真实顺序：先完成模型流，再由 AgentLoop 收集和打印工具结果。
        printer.beginRequest();
        printer.print(
                ParsedEvent.toolCallCompleted(
                        toolUse
                )
        );
        printer.finishRequest();
        assertDoesNotThrow(
                () ->
                        printer.print(
                                ParsedEvent.toolResult(
                                        toolUse,
                                        ToolExecutionResult.success(
                                                "matched-file.txt"
                                        )
                                )
                        )
        );

        // 读取本次请求唯一的 JSONL 文件，核对关键记录的先后顺序。
        Path traceDirectory =
                temporaryDirectory.resolve(
                        ".task_outputs/stream-outputs/test-session/parent"
                );
        List<Path> traceFiles;
        try (var paths =
                     Files.list(
                             traceDirectory
                     )) {
            traceFiles =
                    paths.toList();
        }
        assertEquals(
                1,
                traceFiles.size()
        );
        String trace =
                Files.readString(
                        traceFiles.getFirst(),
                        StandardCharsets.UTF_8
                );

        // 工具结果必须在最终请求边界之前，证明关闭动作确实被延迟。
        int toolCallIndex =
                trace.indexOf(
                        "\"record_type\":\"tool_call_completed\""
                );
        int toolResultIndex =
                trace.indexOf(
                        "\"record_type\":\"tool_result\""
                );
        int requestFinishedIndex =
                trace.indexOf(
                        "\"record_type\":\"request_finished\""
                );
        assertTrue(
                toolCallIndex >= 0
                        && toolCallIndex < toolResultIndex
                        && toolResultIndex < requestFinishedIndex
        );
    }
}
