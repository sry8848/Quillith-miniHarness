// 声明流式输出打印器所在的包。
package dev.learn.agent.manual.output;

// 引入 SDK 原始事件、JSON 序列化和项目工具结果类型。
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

// 引入终端、文件、编码和随机标识所需的 JDK 类型。
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * 根据解析事件输出模型过程，并保存本次模型请求的完整追踪信息。
 *
 * <p>父 Agent 可以把终端设置为 {@link System#out}，子 Agent 则传入空输出流。
 * 两者都会写入原始 JSONL 和完整的大工具结果文件。</p>
 */
public final class StreamOutputPrinter {

    // 定义终端工具结果的软展示上限，不限制原始追踪和完整结果文件。
    private static final int TOOL_RESULT_PREVIEW_CHARACTERS =
            2_000;

    // 复用 Anthropic SDK 的 JSON 映射规则，保证 SDK 类型和追踪文件使用相同语义。
    private static final ObjectMapper JSON_MAPPER =
            ObjectMappers.jsonMapper();

    // 保存工作区边界，所有追踪文件必须通过它解析后才能写入。
    private final WorkspacePathResolver paths;

    // 保存当前 Agent 的终端输出目标，父 Agent 可见、子 Agent 使用空输出流。
    private final PrintStream terminal;

    // 保存会话级目录标识，使父 Agent 和子 Agent 的文件属于同一次运行。
    private final String sessionId;

    // 保存 Agent 名称，仅使用应用装配阶段提供的固定值作为目录和记录标记。
    private final String agentName;

    // 保存当前模型请求的原始事件文件；没有开始请求时保持 null。
    private BufferedWriter rawTraceWriter;

    // 保存当前请求的 UUID，用于关联同一请求产生的所有本地追踪记录。
    private String requestId;

    // 保存当前请求文件的工作区相对路径，供大工具结果展示其可读取位置。
    private String requestDirectory;

    // 标记当前请求是否已经输出 thinking 入口标签，避免每个 delta 重复打印标签。
    private boolean thinkingLabelPrinted;

    // 记录当前请求已经展示但尚未记录结果的工具调用数量。
    private int pendingToolResultCount;

    // 标记模型响应流已经结束，最后一个工具结果写入后即可关闭请求追踪。
    private boolean finishRequested;

    /**
     * 创建一个流式输出打印器。
     *
     * @param paths 工作区路径解析器
     * @param terminal 终端输出目标
     * @param sessionId 当前应用会话标识
     * @param agentName Agent 名称，例如 {@code parent} 或 {@code subagent}
     */
    public StreamOutputPrinter(
            WorkspacePathResolver paths,
            PrintStream terminal,
            String sessionId,
            String agentName
    ) {
        // 保存并校验打印器所依赖的工作区边界。
        this.paths =
                Objects.requireNonNull(
                        paths,
                        "WorkspacePathResolver 不能为空"
                );

        // 保存终端输出目标；空输出流是子 Agent 静默行为的明确实现。
        this.terminal =
                Objects.requireNonNull(
                        terminal,
                        "终端输出目标不能为空"
                );

        // 会话标识和 Agent 名称来自内部装配代码，不接受模型输入。
        this.sessionId =
                requireNonBlank(
                        sessionId,
                        "流式输出会话标识不能为空"
                );
        this.agentName =
                requireNonBlank(
                        agentName,
                        "流式输出 Agent 名称不能为空"
                );
    }

    /**
     * 创建子 Agent 使用的静默终端输出目标。
     *
     * @return 不向父终端输出内容的 UTF-8 PrintStream
     */
    public static PrintStream silentTerminal() {
        return new PrintStream(
                OutputStream.nullOutputStream(),
                true,
                StandardCharsets.UTF_8
        );
    }

    /**
     * 开始记录一次真实模型请求。
     *
     * <p>一次 AgentLoop 运行可能包含多次模型请求，因此每次调用都创建新的 JSONL 文件。</p>
     *
     * @throws IllegalStateException 当前仍有未结束的模型请求时抛出
     * @throws UncheckedIOException 追踪文件无法创建时抛出
     */
    public synchronized void beginRequest() {
        // 一个打印器同一时刻只负责一个模型请求，防止不同请求混入同一文件。
        if (rawTraceWriter != null) {
            throw new IllegalStateException(
                    "上一次模型请求的流式追踪尚未结束"
            );
        }

        // 为当前请求生成不可预测的文件标识，不使用模型输入组成物理文件名。
        requestId =
                UUID.randomUUID()
                        .toString();
        requestDirectory =
                ".task_outputs/stream-outputs/"
                        + sessionId
                        + "/"
                        + agentName;
        String requestPath =
                requestDirectory
                        + "/request-"
                        + requestId
                        + ".jsonl";

        // 通过工作区解析器建立安全路径，再创建本次请求的目录。
        try {
            Path resolvedRequestPath =
                    paths.resolveForWrite(
                            requestPath
                    );
            Files.createDirectories(
                    resolvedRequestPath.getParent()
            );
            rawTraceWriter =
                    Files.newBufferedWriter(
                            resolvedRequestPath,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "无法创建流式原始追踪文件："
                            + requestPath,
                    exception
            );
        }

        // 每次真实请求重新允许 thinking 首段输出标签。
        thinkingLabelPrinted =
                false;

        // 新请求尚未产生工具调用，也尚未收到结束请求。
        pendingToolResultCount =
                0;
        finishRequested =
                false;

        // 记录请求边界，便于文件脱离终端后仍能判断其所属 Agent 和请求。
        writeTraceRecord(
                "request_started",
                null,
                null
        );
    }

    /**
     * 保存一条 SDK 原始模型事件。
     *
     * @param event SDK 原始流式事件
     */
    public synchronized void recordRawEvent(
            RawMessageStreamEvent event
    ) {
        // 原始事件必须在解析前保存，保证解析失败时仍有完整排查材料。
        Objects.requireNonNull(
                event,
                "原始模型事件不能为空"
        );
        writeTraceRecord(
                "model_event",
                event,
                null
        );
    }

    /**
     * 根据事件类型选择终端和追踪策略。
     *
     * @param event 已经由 AgentLoop 解析完成的打印事件
     */
    public synchronized void print(
            ParsedEvent event
    ) {
        // 统一从事件类型分发，禁止打印器根据字符串内容猜测业务含义。
        Objects.requireNonNull(
                event,
                "解析打印事件不能为空"
        );
        switch (event.type()) {
            case TEXT_DELTA -> printText(
                    event.text()
            );
            case THINKING_DELTA -> printThinking(
                    event.text()
            );
            case TOOL_CALL_COMPLETED -> printToolCall(
                    event.toolUse()
            );
            case TOOL_RESULT -> printToolResult(
                    event.toolUse(),
                    event.toolResult()
            );
            case MESSAGE_STOPPED -> printMessageStopped();
        }
    }

    /**
     * 输出不属于模型协议事件的本地诊断信息。
     *
     * <p>错误和恢复提示不伪装成五类模型解析事件，避免把本地消息误认为模型文本。
     * 这类消息只影响终端，不进入模型消息历史。</p>
     *
     * @param message 本地诊断信息
     */
    public synchronized void printLocalMessage(
            String message
    ) {
        // 本地提示只负责保留现有错误可见性，不参与 ParsedEvent 分发。
        terminal.print(
                Objects.requireNonNull(
                        message,
                        "本地诊断信息不能为空"
                )
        );
        terminal.flush();
    }

    /**
     * 请求结束关闭当前追踪；仍有工具结果时延迟到最后一个结果写入后关闭。
     *
     * @throws UncheckedIOException 追踪文件关闭失败时抛出
     */
    public synchronized void finishRequest() {
        // 没有开始请求时允许调用方在 finally 中安全收尾。
        if (rawTraceWriter == null) {
            return;
        }

        // 模型流可能先于本地工具完成，记录结束意图并保留结果所需的请求上下文。
        finishRequested =
                true;
        if (pendingToolResultCount > 0) {
            return;
        }

        // 没有待记录工具结果时立即完成请求。
        closeRequest();
    }

    /**
     * 写入请求结束边界并释放当前追踪状态。
     *
     * @throws UncheckedIOException 追踪文件关闭失败时抛出
     */
    private void closeRequest() {
        // 先记录请求边界，再关闭文件，保证完整文件包含收尾记录。
        writeTraceRecord(
                "request_finished",
                null,
                null
        );
        try {
            rawTraceWriter.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "无法关闭流式原始追踪文件",
                    exception
            );
        } finally {
            rawTraceWriter =
                    null;
            requestId =
                    null;
            requestDirectory =
                    null;
            thinkingLabelPrinted =
                    false;
            pendingToolResultCount =
                    0;
            finishRequested =
                    false;
        }
    }

    /**
     * 输出普通文本增量。
     *
     * @param text 模型返回的普通文本增量
     */
    private void printText(
            String text
    ) {
        // 文本增量原样输出并立即刷新，保持模型流式响应的实时性。
        terminal.print(
                text
        );
        terminal.flush();
    }

    /**
     * 输出 thinking 增量。
     *
     * @param text 模型返回的 thinking 增量
     */
    private void printThinking(
            String text
    ) {
        // 只在一次请求的首个 thinking 增量前添加标签，后续内容保持原始增量形态。
        if (!thinkingLabelPrinted) {
            terminal.print(
                    "[thinking] "
            );
            thinkingLabelPrinted =
                    true;
        }
        terminal.print(
                text
        );
        terminal.flush();
    }

    /**
     * 输出完整工具调用，并把重建后的调用保存到追踪文件。
     *
     * @param toolUse 完整工具调用
     */
    private void printToolCall(
            ToolUseBlock toolUse
    ) {
        // 保存跨多个原始事件重建后的完整工具调用，便于人工直接查看。
        writeTraceRecord(
                "tool_call_completed",
                toolUse,
                null
        );

        // 每个已展示的完整工具调用都必须等待一个对应结果后才能关闭请求追踪。
        pendingToolResultCount++;

        // 使用 SDK JSON 映射器输出完整工具调用，而不是手工拼接 JSON。
        terminal.println();
        terminal.println(
                "[工具调用] "
                        + toolUse.name()
        );
        terminal.println(
                prettyJson(
                        toolUse
                )
        );
        terminal.flush();
    }

    /**
     * 输出工具结果，并在结果过大时保存完整文本。
     *
     * @param toolUse 对应的工具调用
     * @param result 工具执行结果
     */
    private void printToolResult(
            ToolUseBlock toolUse,
            ToolExecutionResult result
    ) {
        // 先保存未经上下文预算处理的原始本地工具结果。
        writeTraceRecord(
                "tool_result",
                toolUse,
                result
        );

        // 计算终端预览和是否需要完整结果文件。
        String content =
                result.content();
        boolean truncated =
                content.length()
                        > TOOL_RESULT_PREVIEW_CHARACTERS;
        String preview =
                truncated
                        ? truncatePreview(
                        content
                )
                        : content;
        String fullResultPath =
                truncated
                        ? writeFullToolResult(
                        toolUse,
                        result
                )
                        : null;

        // 终端只输出已确定的预览和文件位置，完整内容始终由追踪文件保留。
        terminal.println();
        terminal.println(
                "[工具结果] "
                        + toolUse.name()
        );
        terminal.println(
                "状态："
                        + (result.error() ? "失败" : "成功")
        );
        terminal.println(
                "结果字符数："
                        + content.length()
        );
        if (truncated) {
            terminal.println(
                    "终端仅显示前 "
                            + TOOL_RESULT_PREVIEW_CHARACTERS
                            + " 字符，完整结果："
                            + fullResultPath
            );
        }
        terminal.println(
                preview
        );
        terminal.flush();

        // 当前工具结果已经完整落盘和展示，更新请求内尚未完成的配对数量。
        pendingToolResultCount--;

        // 模型流已结束且全部工具结果到齐时，才写入最终请求边界并关闭文件。
        if (finishRequested
                && pendingToolResultCount == 0) {
            closeRequest();
        }
    }

    /**
     * 处理 message_stop 的终端提示和追踪记录。
     */
    private void printMessageStopped() {
        // 记录已收到消息结束信号，终端提示用于区分本次模型请求和下一次请求。
        writeTraceRecord(
                "message_stopped",
                null,
                null
        );
        terminal.println();
        terminal.println(
                "[消息结束]"
        );
        terminal.flush();
    }

    /**
     * 写入完整的大工具结果文件。
     *
     * @param toolUse 对应的工具调用
     * @param result 工具执行结果
     * @return 工作区相对路径
     */
    private String writeFullToolResult(
            ToolUseBlock toolUse,
            ToolExecutionResult result
    ) {
        // 使用程序生成的 UUID，避免模型提供的工具参数影响物理文件名。
        String relativePath =
                requestDirectory
                        + "/tool-result-"
                        + UUID.randomUUID()
                        + ".txt";

        // 通过工作区边界解析并写入完整原始结果。
        try {
            Path resultPath =
                    paths.resolveForWrite(
                            relativePath
                    );
            Files.createDirectories(
                    resultPath.getParent()
            );
            String header =
                    "工具名称："
                            + toolUse.name()
                            + System.lineSeparator()
                            + "工具调用 ID："
                            + toolUse.id()
                            + System.lineSeparator()
                            + "是否报错："
                            + (result.error() ? "是" : "否")
                            + System.lineSeparator()
                            + "原始结果字符数："
                            + result.content().length()
                            + System.lineSeparator()
                            + System.lineSeparator()
                            + "==================== 完整工具结果 ===================="
                            + System.lineSeparator()
                            + System.lineSeparator();
            Files.writeString(
                    resultPath,
                    header + result.content(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            return relativePath;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "无法保存完整工具结果："
                            + relativePath,
                    exception
            );
        }
    }

    /**
     * 生成不切断 UTF-16 代理对的终端预览。
     *
     * @param content 完整工具结果
     * @return 不超过展示上限的预览
     */
    private static String truncatePreview(
            String content
    ) {
        // 先按 Java 字符串长度取得字符切口。
        int previewEnd =
                Math.min(
                        content.length(),
                        TOOL_RESULT_PREVIEW_CHARACTERS
                );

        // 避免 emoji 等代理对被切成半个 Unicode 字符。
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
        return content.substring(
                0,
                previewEnd
        );
    }

    /**
     * 把追踪记录序列化为一行 JSONL。
     *
     * @param recordType 记录类型
     * @param firstPayload 第一个可选载荷，例如原始模型事件或工具调用
     * @param secondPayload 第二个可选载荷，例如工具结果
     */
    private void writeTraceRecord(
            String recordType,
            Object firstPayload,
            Object secondPayload
    ) {
        // 所有派生记录都必须发生在 beginRequest 和 finishRequest 之间。
        if (rawTraceWriter == null) {
            throw new IllegalStateException(
                    "尚未开始模型请求，不能写入流式追踪记录"
            );
        }

        // 组装稳定的 JSONL 外层结构，具体载荷仍由 SDK 映射器序列化。
        ObjectNode record =
                JSON_MAPPER.createObjectNode();
        record.put(
                "record_type",
                recordType
        );
        record.put(
                "agent",
                agentName
        );
        record.put(
                "request_id",
                requestId
        );
        if (firstPayload != null) {
            record.set(
                    firstPayload instanceof RawMessageStreamEvent
                            ? "event"
                            : "tool_use",
                    JSON_MAPPER.valueToTree(
                            firstPayload
                    )
            );
        }
        if (secondPayload != null) {
            // 项目内部 record 不一定被 SDK 映射器识别为 JavaBean，结果字段在这里显式写入。
            if (secondPayload instanceof ToolExecutionResult toolResult) {
                ObjectNode resultNode =
                        JSON_MAPPER.createObjectNode();
                resultNode.put(
                        "content",
                        toolResult.content()
                );
                resultNode.put(
                        "error",
                        toolResult.error()
                );
                record.set(
                        "result",
                        resultNode
                );
            } else {
                record.set(
                        "result",
                        JSON_MAPPER.valueToTree(
                                secondPayload
                        )
                );
            }
        }

        // 每条记录立即刷新，保证流中断时已经收到的原始信息仍然可读取。
        try {
            rawTraceWriter.write(
                    JSON_MAPPER.writeValueAsString(
                            record
                    )
            );
            rawTraceWriter.newLine();
            rawTraceWriter.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "无法写入流式追踪记录："
                            + recordType,
                    exception
            );
        }
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

    /**
     * 校验内部装配阶段提供的非空字符串。
     *
     * @param value 待校验值
     * @param message 失败信息
     * @return 原始非空字符串
     */
    private static String requireNonBlank(
            String value,
            String message
    ) {
        Objects.requireNonNull(
                value,
                message
        );
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    message
            );
        }
        return value;
    }
}
