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
 * 在可访问文件中替换第一次出现的指定文本。
 */
public final class EditFileTool implements AgentTool {

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "edit_file",
                    "Replace the first exact text occurrence in an accessible file. "
                            + "The host approval policy controls external writes.",
                    Map.of(
                            "path",
                            ToolDefinitionFactory.stringProperty(
                                    "Path relative to the workspace or an accessible external path."
                            ),
                            "old_text",
                            ToolDefinitionFactory.stringProperty(
                                    "Exact text to find."
                            ),
                            "new_text",
                            ToolDefinitionFactory.stringProperty(
                                    "Replacement text."
                            )
                    ),
                    List.of(
                            "path",
                            "old_text",
                            "new_text"
                    )
            );

    private final WorkspacePathResolver paths;

    public EditFileTool(
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
        JsonNode oldTextNode =
                input.get("old_text");
        JsonNode newTextNode =
                input.get("new_text");

        if (pathNode == null
                || !pathNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: path must be a string"
            );
        }

        if (oldTextNode == null
                || !oldTextNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: old_text must be a string"
            );
        }

        if (newTextNode == null
                || !newTextNode.isTextual()) {
            return ToolExecutionResult.failure(
                    "Error: new_text must be a string"
            );
        }

        String pathText =
                pathNode.textValue();
        String oldText =
                oldTextNode.textValue();
        String newText =
                newTextNode.textValue();

        if (oldText.isEmpty()) {
            return ToolExecutionResult.failure(
                    "Error: old_text must not be empty"
            );
        }

        try {
            Path file =
                    paths.resolveExistingAnywhere(
                            pathText
                    );

            if (!Files.isRegularFile(file)) {
                return ToolExecutionResult.failure(
                        "Error: path is not a regular file"
                );
            }

            String original =
                    Files.readString(
                            file,
                            StandardCharsets.UTF_8
                    );

            int matchIndex =
                    original.indexOf(
                            oldText
                    );

            if (matchIndex < 0) {
                return ToolExecutionResult.failure(
                        "Error: text not found in "
                                + pathText
                );
            }

            String edited =
                    original.substring(
                            0,
                            matchIndex
                    )
                            + newText
                            + original.substring(
                                    matchIndex
                                            + oldText.length()
                            );

            Files.writeString(
                    file,
                    edited,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            return ToolExecutionResult.success(
                    "Edited " + pathText
            );
        } catch (IOException exception) {
            return ToolExecutionResult.failure(
                    "Error: " + exception.getMessage()
            );
        }
    }
}
