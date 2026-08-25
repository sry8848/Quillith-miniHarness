// 声明终端输出打印器测试所在的包。
package dev.learn.agent.manual.output;

// 引入 Anthropic 工具调用类型和项目内部工具结果类型。
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.ToolUseBlock;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

// 引入测试使用的内存终端和集合类型。
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证流式终端输出的四项最小行为。
 */
class StreamOutputPrinterTest {

    /**
     * Given thinking 和正文各自收到多个增量，
     * when 打印器按内容块顺序输出，
     * then 两段内容都完整保留且拥有清晰边界。
     */
    @Test
    void printsThinkingAndTextDeltasWithReadableBoundaries() {
        // 创建只写入内存的终端，验证打印器不会等待完整模型响应。
        ByteArrayOutputStream terminalBytes =
                new ByteArrayOutputStream();
        StreamOutputPrinter printer =
                newPrinter(
                        terminalBytes
                );

        // 模拟 thinking 和普通文本的分块到达顺序。
        printer.startThinkingBlock();
        printer.printThinkingDelta("先分析");
        printer.printThinkingDelta("工具选择");
        printer.startTextBlock();
        printer.printTextDelta("最终");
        printer.printTextDelta("答案");

        // 验证增量没有丢失，并且正文标签出现在 thinking 之后。
        String output =
                terminalBytes.toString(
                        StandardCharsets.UTF_8
                );
        assertTrue(
                output.contains(
                        "[thinking]"
                )
        );
        assertTrue(
                output.contains(
                        "先分析工具选择"
                )
        );
        assertTrue(
                output.contains(
                        "[Agent]"
                )
        );
        assertTrue(
                output.contains(
                        "最终答案"
                )
        );
        assertTrue(
                output.indexOf(
                        "[thinking]"
                )
                        < output.indexOf(
                        "[Agent]"
                )
        );
    }

    /**
     * Given 一个完整工具调用，
     * when 打印器输出调用，
     * then 终端应包含工具名称和完整输入 JSON。
     */
    @Test
    void printsCompleteToolCallJson() {
        // 创建内存终端和完整工具调用对象。
        ByteArrayOutputStream terminalBytes =
                new ByteArrayOutputStream();
        StreamOutputPrinter printer =
                newPrinter(
                        terminalBytes
                );
        ToolUseBlock toolUse =
                toolUse();

        // 输出已经由 AgentLoop 拼接完成的工具调用。
        printer.printToolCall(
                toolUse
        );

        // 验证展示包含工具名称、输入字段和具体参数值。
        String output =
                terminalBytes.toString(
                        StandardCharsets.UTF_8
                );
        assertTrue(
                output.contains(
                        "[工具调用] glob"
                )
        );
        assertTrue(
                output.contains(
                        "\"input\""
                )
        );
        assertTrue(
                output.contains(
                        "\"pattern\""
                )
        );
        assertTrue(
                output.contains(
                        "**/*.java"
                )
        );
    }

    /**
     * Given 不超过 2000 字符的工具结果，
     * when 打印工具结果，
     * then 终端应完整显示结果且不提示截断。
     */
    @Test
    void printsShortToolResultCompletely() {
        // 创建内存终端并准备短工具结果。
        ByteArrayOutputStream terminalBytes =
                new ByteArrayOutputStream();
        StreamOutputPrinter printer =
                newPrinter(
                        terminalBytes
                );
        String content =
                "short-result";

        // 输出短结果。
        printer.printToolResult(
                toolUse(),
                ToolExecutionResult.success(
                        content
                )
        );

        // 验证结果完整出现，且没有虚假的截断提示。
        String output =
                terminalBytes.toString(
                        StandardCharsets.UTF_8
                );
        assertTrue(
                output.contains(
                        content
                )
        );
        assertFalse(
                output.contains(
                        "结果已截断"
                )
        );
    }

    /**
     * Given 超过 2000 字符的工具结果，
     * when 打印工具结果，
     * then 终端只显示前 2000 字符并明确提示截断。
     */
    @Test
    void truncatesLongToolResultWithoutWritingAnArchive() {
        // 创建内存终端并准备长度为 2001 的结果。
        ByteArrayOutputStream terminalBytes =
                new ByteArrayOutputStream();
        StreamOutputPrinter printer =
                newPrinter(
                        terminalBytes
                );
        String prefix =
                "a".repeat(
                        2_000
                );
        String content =
                prefix
                        + "tail-not-shown";

        // 输出长结果；打印器只负责终端预览，不创建文件。
        printer.printToolResult(
                toolUse(),
                ToolExecutionResult.success(
                        content
                )
        );

        // 验证前缀保留、尾部隐藏且没有归档路径提示。
        String output =
                terminalBytes.toString(
                        StandardCharsets.UTF_8
                );
        assertTrue(
                output.contains(
                        prefix
                )
        );
        assertFalse(
                output.contains(
                        "tail-not-shown"
                )
        );
        assertTrue(
                output.contains(
                        "结果已截断"
                )
        );
        assertFalse(
                output.contains(
                        "完整结果："
                )
        );
    }

    /**
     * Given 截断位置正好落在 UTF-16 代理对附近，
     * when 打印工具结果，
     * then 终端不应出现半个 emoji 或替换字符。
     */
    @Test
    void doesNotSplitSurrogatePairWhenTruncating() {
        // 创建让 emoji 横跨第 2000 个 UTF-16 单元的结果。
        ByteArrayOutputStream terminalBytes =
                new ByteArrayOutputStream();
        StreamOutputPrinter printer =
                newPrinter(
                        terminalBytes
                );
        String content =
                "a".repeat(
                        1_999
                )
                        + "😀"
                        + "tail";

        // 输出结果并取得 UTF-8 解码后的终端内容。
        printer.printToolResult(
                toolUse(),
                ToolExecutionResult.success(
                        content
                )
        );
        String output =
                terminalBytes.toString(
                        StandardCharsets.UTF_8
                );

        // 代理对被整体省略，既不会出现 emoji，也不会产生替换字符。
        assertFalse(
                output.contains(
                        "😀"
                )
        );
        assertFalse(
                output.contains(
                        "�"
                )
        );
    }

    /**
     * Given 子 Agent 使用静默输出目标，
     * when 打印完整输出，
     * then 不应把中间内容发送到父终端。
     */
    @Test
    void supportsSilentSubagentTerminal() {
        // 创建静默打印器，验证子 Agent 的输出策略仍然存在。
        StreamOutputPrinter printer =
                new StreamOutputPrinter(
                        StreamOutputPrinter.silentTerminal()
                );

        // 所有输出都应安全执行且不会泄露到当前终端。
        printer.startThinkingBlock();
        printer.printThinkingDelta("hidden");
        printer.startTextBlock();
        printer.printTextDelta("hidden");
        printer.printToolCall(
                toolUse()
        );
        printer.printToolResult(
                toolUse(),
                ToolExecutionResult.success(
                        "hidden"
                )
        );
    }

    /**
     * 创建写入内存的打印器。
     *
     * @param terminalBytes 终端捕获缓冲区
     * @return 测试用打印器
     */
    private static StreamOutputPrinter newPrinter(
            ByteArrayOutputStream terminalBytes
    ) {
        // 使用 UTF-8 内存终端，避免测试依赖操作系统默认编码。
        return new StreamOutputPrinter(
                new PrintStream(
                        terminalBytes,
                        true,
                        StandardCharsets.UTF_8
                )
        );
    }

    /**
     * 创建测试使用的完整 glob 工具调用。
     *
     * @return 包含 JSON 输入的工具调用
     */
    private static ToolUseBlock toolUse() {
        // 使用 SDK 类型构造与 AgentLoop 最终输出相同的工具调用形态。
        return ToolUseBlock.builder()
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
                                        "**/*.java"
                                )
                        )
                )
                .build();
    }
}
