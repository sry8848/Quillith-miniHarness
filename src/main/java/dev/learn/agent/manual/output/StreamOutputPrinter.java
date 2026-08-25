// 声明终端输出打印器所在的包。
package dev.learn.agent.manual.output;

// 引入 Anthropic SDK 类型和项目内部工具结果类型。
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ToolUseBlock;
import dev.learn.agent.manual.tool.ToolExecutionResult;

// 引入终端输出和 JSON 格式化所需的 JDK 类型。
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 把 Agent 的协议输出转换为可读的终端文本。
 *
 * <p>该类只负责展示，不保存原始模型事件，也不管理请求生命周期。
 * 上下文预算需要的工具结果落盘仍由 {@code ContextManager} 独立负责。</p>
 */
public final class StreamOutputPrinter {

    // 定义终端允许展示的工具结果字符上限，不限制模型实际收到的结果。
    private static final int TOOL_RESULT_PREVIEW_CHARACTERS =
            2_000;

    // 复用 SDK 的 JSON 映射规则，确保工具调用字段与协议语义一致。
    private static final ObjectMapper JSON_MAPPER =
            ObjectMappers.jsonMapper();

    // 保存当前 Agent 的终端输出目标；父 Agent 可见，子 Agent 可以使用静默流。
    private final PrintStream terminal;

    /**
     * 创建一个终端输出打印器。
     *
     * @param terminal 输出目标
     * @throws NullPointerException 输出目标为空时抛出
     */
    public StreamOutputPrinter(
            PrintStream terminal
    ) {
        // 保存终端边界，避免后续每个输出方法重复判断目标是否存在。
        this.terminal =
                Objects.requireNonNull(
                        terminal,
                        "终端输出目标不能为空"
                );
    }

    /**
     * 创建子 Agent 使用的静默终端输出目标。
     *
     * @return 不向父终端写入内容的 UTF-8 输出流
     */
    public static PrintStream silentTerminal() {
        // 使用 JDK 的空输出流保留原有“子 Agent 不显示中间过程”契约。
        return new PrintStream(
                OutputStream.nullOutputStream(),
                true,
                StandardCharsets.UTF_8
        );
    }

    /**
     * 输出 thinking 内容块的标题。
     *
     * <p>标题在内容块开始时输出，而不是在每个增量前重复输出，
     * 这样思考内容和后续 Agent 正文之间始终有清晰边界。</p>
     */
    public void startThinkingBlock() {
        // 为 thinking 内容建立独立可读区域。
        terminal.println();
        terminal.println(
                "[thinking]"
        );
        terminal.flush();
    }

    /**
     * 输出 Agent 正文内容块的标题。
     */
    public void startTextBlock() {
        // 将普通正文与 thinking 或工具信息分隔开，避免不同内容直接粘连。
        terminal.println();
        terminal.println(
                "[Agent]"
        );
        terminal.flush();
    }

    /**
     * 输出一段普通文本增量。
     *
     * @param text 模型返回的文本增量
     * @throws NullPointerException 文本为空时抛出
     */
    public void printTextDelta(
            String text
    ) {
        // 文本增量原样输出并立即刷新，保持流式响应实时可见。
        terminal.print(
                Objects.requireNonNull(
                        text,
                        "文本增量不能为空"
                )
        );
        terminal.flush();
    }

    /**
     * 输出一段 thinking 增量。
     *
     * @param text 模型返回的 thinking 增量
     * @throws NullPointerException thinking 文本为空时抛出
     */
    public void printThinkingDelta(
            String text
    ) {
        // thinking 增量与普通文本一样原样输出，不使用工具结果的 2000 字符限制。
        terminal.print(
                Objects.requireNonNull(
                        text,
                        "thinking 增量不能为空"
                )
        );
        terminal.flush();
    }

    /**
     * 输出已经完整重建的工具调用。
     *
     * @param toolUse 完整工具调用
     * @throws NullPointerException 工具调用为空时抛出
     * @throws UncheckedIOException 工具调用无法序列化时抛出
     */
    public void printToolCall(
            ToolUseBlock toolUse
    ) {
        // 读取完整工具调用后再输出，避免把半截 input_json_delta 当作 JSON 展示。
        ToolUseBlock nonNullToolUse =
                Objects.requireNonNull(
                        toolUse,
                        "工具调用不能为空"
                );
        terminal.println();
        terminal.println(
                "[工具调用] "
                        + nonNullToolUse.name()
        );
        terminal.println(
                prettyJson(
                        nonNullToolUse
                )
        );
        terminal.flush();
    }

    /**
     * 输出工具执行结果，超过上限时只展示前 2000 字符。
     *
     * @param toolUse 对应的工具调用
     * @param result 工具执行结果
     * @throws NullPointerException 参数为空时抛出
     */
    public void printToolResult(
            ToolUseBlock toolUse,
            ToolExecutionResult result
    ) {
        // 校验调用关系，避免终端把一个结果显示成另一个工具的结果。
        ToolUseBlock nonNullToolUse =
                Objects.requireNonNull(
                        toolUse,
                        "工具调用不能为空"
                );
        ToolExecutionResult nonNullResult =
                Objects.requireNonNull(
                        result,
                        "工具执行结果不能为空"
                );

        // 只在终端计算预览，不修改传回模型和 ContextManager 的原始结果。
        String content =
                nonNullResult.content();
        boolean truncated =
                content.length()
                        > TOOL_RESULT_PREVIEW_CHARACTERS;
        String preview =
                truncated
                        ? truncatePreview(
                        content
                )
                        : content;

        // 输出工具状态、原始长度和必要的截断提示。
        terminal.println();
        terminal.println(
                "[工具结果] "
                        + nonNullToolUse.name()
        );
        terminal.println(
                "状态："
                        + (nonNullResult.error() ? "失败" : "成功")
        );
        terminal.println(
                "结果字符数："
                        + content.length()
        );
        if (truncated) {
            terminal.println(
                    "终端仅显示前 "
                            + TOOL_RESULT_PREVIEW_CHARACTERS
                            + " 字符，结果已截断。"
            );
        }
        terminal.println(
                preview
        );
        terminal.flush();
    }

    /**
     * 输出不属于模型协议的本地诊断信息。
     *
     * @param message 本地诊断信息
     * @throws NullPointerException 诊断信息为空时抛出
     */
    public void printLocalMessage(
            String message
    ) {
        // 本地错误和恢复提示只影响终端，不进入模型消息历史。
        terminal.print(
                Objects.requireNonNull(
                        message,
                        "本地诊断信息不能为空"
                )
        );
        terminal.flush();
    }

    /**
     * 生成不切断 UTF-16 代理对的终端预览。
     *
     * @param content 完整工具结果
     * @return 不超过展示上限且不包含半个代理对的文本
     */
    private static String truncatePreview(
            String content
    ) {
        // 先按 Java 字符串长度确定预览切口。
        int previewEnd =
                Math.min(
                        content.length(),
                        TOOL_RESULT_PREVIEW_CHARACTERS
                );

        // 切口落在 emoji 等代理对中间时向前移动一位，避免输出半个字符。
        if (previewEnd > 0
                && previewEnd < content.length()
                && Character.isHighSurrogate(
                content.charAt(
                        previewEnd - 1
                )
        )
                && Character.isLowSurrogate(
                content.charAt(
                        previewEnd
                )
        )) {
            previewEnd--;
        }

        // 返回只用于终端展示的副本，不改变原始结果对象。
        return content.substring(
                0,
                previewEnd
        );
    }

    /**
     * 使用 SDK JSON 映射器生成适合终端阅读的 JSON。
     *
     * @param value 待序列化的 SDK 类型
     * @return 格式化后的 JSON
     */
    private static String prettyJson(
            Object value
    ) {
        // 统一使用 SDK 映射器，避免手工拼接导致字段遗漏或转义错误。
        try {
            return JSON_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(
                            value
                    );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "无法格式化工具调用 JSON",
                    exception
            );
        }
    }
}
