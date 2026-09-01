package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在 allowedRoots 内创建或覆盖 UTF-8 文本文件。
 */
public final class WriteFileTool implements AgentTool {

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "write_file",
                    "Create or overwrite a UTF-8 text file at an accessible path. "
                            + "The host approval policy controls external writes.",
                    Map.of(
                            "path",
                            ToolDefinitionFactory.stringProperty(
                            "Path relative to the workspace or an allowed root path."
                            ),
                            "content",
                            ToolDefinitionFactory.stringProperty(
                                    "Complete text to write to the file."
                            )
                    ),
                    List.of(
                            "path",
                            "content"
                    )
            );

    private final WorkspacePathResolver paths;

    public WriteFileTool(
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

    @Override
    public ToolExecutionResult execute(
            JsonNode input
    ) {
        JsonNode pathNode =
                input.get("path");
        JsonNode contentNode =
                input.get("content");

        if (pathNode == null
                || !pathNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: path must be a string"
            );
        }

        if (contentNode == null
                || !contentNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: content must be a string"
            );
        }

        String pathText =
                pathNode.textValue();
        String content =
                contentNode.textValue();

        try {
            Path file =
                    paths.resolveForWrite(
                            pathText
                    );

            Files.createDirectories(
                    file.getParent()
            );

            Files.writeString(
                    file,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            int byteCount =
                    content.getBytes(
                            StandardCharsets.UTF_8
                    ).length;

            return ToolExecutionResult.success(
                    "Wrote "
                            + byteCount
                            + " bytes to "
                            + pathText
            );
        } catch (IOException exception) {
            return ToolExecutionResult.failure(
                    "Error: " + exception.getMessage()
            );
        }
    }
}
