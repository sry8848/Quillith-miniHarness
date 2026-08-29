package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按 UTF-8 读取可访问路径中的文本文件。
 */
public final class ReadFileTool implements AgentTool {

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "read_file",
                    "Read a UTF-8 text file from an accessible path. "
                            + "Reading does not require approval.",
                    Map.of(
                            "path",
                            ToolDefinitionFactory.stringProperty(
                                    "Path relative to the workspace or an accessible external path."
                            ),
                            "limit",
                            ToolDefinitionFactory.positiveIntegerProperty(
                                    "Optional maximum number of lines to return."
                            )
                    ),
                    List.of("path")
            );

    private final WorkspacePathResolver paths;

    public ReadFileTool(
            WorkspacePathResolver paths
    ) {
        this.paths =
                Objects.requireNonNull(
                        paths,
                        "WorkspacePathResolver 不能为空"
                );
    }

    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /**
     * 判断文件读取是否可以进入并发批次。
     *
     * @return 固定返回 true，因为该工具不会修改工作区或内部状态
     */
    @Override
    public boolean isConcurrencySafe() {
        // 该工具只读取文件，写工具的顺序边界由调度器负责建立。
        return true;
    }

    @Override
    public ToolExecutionResult execute(
            JsonNode input
    ) {
        JsonNode pathNode =
                input.get("path");

        if (pathNode == null
                || !pathNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: path must be a string"
            );
        }

        Integer limit = null;
        JsonNode limitNode =
                input.get("limit");

        if (limitNode != null) {
            if (!limitNode.isIntegralNumber()
                    || !limitNode.canConvertToInt()
                    || limitNode.intValue() <= 0) {
                return ToolExecutionResult.failure(
                        "Error: limit must be a positive integer"
                );
            }

            limit =
                    limitNode.intValue();
        }

        try {
            Path file =
                    paths.resolveExistingAnywhere(
                            pathNode.textValue()
                    );

            if (!Files.isRegularFile(file)) {
                return ToolExecutionResult.failure(
                        "Error: path is not a regular file"
                );
            }

            return ToolExecutionResult.success(
                    readLines(
                            file,
                            limit
                    )
            );
        } catch (IOException exception) {
            return ToolExecutionResult.failure(
                    "Error: " + exception.getMessage()
            );
        }
    }

    /**
     * 只保存需要返回的行，但继续计数，以便告诉模型还有多少行。
     */
    private String readLines(
            Path file,
            Integer limit
    ) throws IOException {
        StringBuilder output =
                new StringBuilder();

        int totalLines = 0;
        int returnedLines = 0;

        try (BufferedReader reader =
                     Files.newBufferedReader(
                             file,
                             StandardCharsets.UTF_8
                     )) {
            String line;

            while ((line = reader.readLine()) != null) {
                totalLines++;

                if (limit == null
                        || returnedLines < limit) {
                    if (returnedLines > 0) {
                        output.append(
                                System.lineSeparator()
                        );
                    }

                    output.append(line);
                    returnedLines++;
                }
            }
        }

        if (totalLines > returnedLines) {
            if (!output.isEmpty()) {
                output.append(
                        System.lineSeparator()
                );
            }

            output.append("... (")
                    .append(
                            totalLines
                                    - returnedLines
                    )
                    .append(" more lines)");
        }

        return totalLines == 0
                ? "(empty file)"
                : output.toString();
    }
}
