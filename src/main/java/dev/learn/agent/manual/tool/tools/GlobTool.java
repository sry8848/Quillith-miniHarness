package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 在工作区中查找符合 Glob 模式的路径。
 */
public final class GlobTool implements AgentTool {

    private static final int MAX_RESULTS = 1_000;

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "glob",
                    "Find workspace paths matching a glob pattern.",
                    Map.of(
                            "pattern",
                            ToolDefinitionFactory.stringProperty(
                                    "Glob pattern such as **/*.java."
                            )
                    ),
                    List.of("pattern")
            );

    private final WorkspacePathResolver paths;

    public GlobTool(
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
    public String execute(
            JsonNode input
    ) {
        JsonNode patternNode =
                input.get("pattern");

        if (patternNode == null
                || !patternNode.isTextual()
                || patternNode.textValue().isBlank()) {
            return "Error: pattern must be a non-blank string";
        }

        String pattern =
                patternNode.textValue();

        final PathMatcher matcher;

        try {
            matcher =
                    FileSystems.getDefault()
                            .getPathMatcher(
                                    "glob:" + pattern
                            );
        } catch (IllegalArgumentException exception) {
            return "Error: invalid glob pattern: "
                    + exception.getMessage();
        }

        Path workspace =
                paths.workspace();

        try (Stream<Path> stream =
                     Files.walk(workspace)) {
            /*
             * Files.walk 默认不跟随目录符号链接，
             * 因而不会借助链接遍历到工作区外。
             */
            List<String> matches =
                    stream.skip(1)
                            .map(
                                    workspace::relativize
                            )
                            .filter(
                                    matcher::matches
                            )
                            .map(
                                    path -> path.toString()
                                            .replace(
                                                    '\\',
                                                    '/'
                                            )
                            )
                            .sorted()
                            .limit(
                                    MAX_RESULTS + 1L
                            )
                            .toList();

            if (matches.isEmpty()) {
                return "(no matches)";
            }

            boolean truncated =
                    matches.size() > MAX_RESULTS;

            List<String> visibleMatches =
                    truncated
                            ? new ArrayList<>(
                                    matches.subList(
                                            0,
                                            MAX_RESULTS
                                    )
                            )
                            : matches;

            String output =
                    String.join(
                            System.lineSeparator(),
                            visibleMatches
                    );

            return truncated
                    ? output
                            + System.lineSeparator()
                            + "... results truncated"
                    : output;
        } catch (IOException
                 | UncheckedIOException exception) {
            return "Error: " + exception.getMessage();
        }
    }
}
