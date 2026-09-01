package dev.learn.agent.manual.tool.tools;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.manual.tool.AgentTool;
import dev.learn.agent.manual.tool.ToolDefinitionFactory;
import dev.learn.agent.manual.tool.ToolExecutionResult;
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
 * 在一个 allowed root 中查找符合 Glob 模式的路径。
 */
public final class GlobTool implements AgentTool {

    private static final int MAX_RESULTS = 1_000;

    private static final Tool DEFINITION =
            ToolDefinitionFactory.create(
                    "glob",
                    "Find paths matching a glob pattern within the workspace or one allowed root.",
                    Map.of(
                            "pattern",
                            ToolDefinitionFactory.stringProperty(
                                    "Glob pattern such as **/*.java."
                            ),
                            "root",
                            ToolDefinitionFactory.stringProperty(
                                    "Optional allowed root path; defaults to the workspace."
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

    /**
     * 判断只读路径扫描是否可以进入并发批次。
     *
     * @return 固定返回 true，因为该工具不会修改工作区或内部状态
     */
    @Override
    public boolean isConcurrencySafe() {
        // Files.walk 只读取目录结构，写操作由独占工具阻止并发。
        return true;
    }

    @Override
    public ToolExecutionResult execute(
            JsonNode input
    ) {
        JsonNode patternNode =
                input.get("pattern");

        if (patternNode == null
                || !patternNode.isTextual()
                || patternNode.textValue().isBlank()) {
            return ToolExecutionResult.failure(
                    "Error: pattern must be a non-blank string"
            );
        }

        String pattern =
                patternNode.textValue();

        Path configuredRoot;
        JsonNode rootNode =
                input.get("root");
        if (rootNode == null) {
            configuredRoot = paths.workspace();
        } else if (!rootNode.isTextual()
                || rootNode.textValue().isBlank()) {
            return ToolExecutionResult.failure(
                    "Error: root must be a non-blank allowed directory path"
            );
        } else {
            try {
                configuredRoot =
                        paths.resolveExisting(
                                rootNode.textValue()
                        );
            } catch (IOException exception) {
                return ToolExecutionResult.failure(
                        "Error: root is not an allowed directory: "
                                + exception.getMessage()
                );
            }
        }

        final Path searchRoot = configuredRoot;

        if (!Files.isDirectory(searchRoot)) {
            return ToolExecutionResult.failure(
                    "Error: root is not a directory"
            );
        }

        final PathMatcher matcher;

        try {
            matcher =
                    FileSystems.getDefault()
                            .getPathMatcher(
                                    "glob:" + pattern
                            );
        } catch (IllegalArgumentException exception) {
            return ToolExecutionResult.failure(
                    "Error: invalid glob pattern: "
                            + exception.getMessage()
            );
        }

        try (Stream<Path> stream =
                     Files.walk(searchRoot)) {
            /*
             * Files.walk 默认不跟随目录符号链接，
             * 但仍过滤真实目标，避免把指向 allowed roots 外的链接暴露给模型。
             */
            List<String> matches =
                    stream.skip(1)
                            .map(
                                    searchRoot::relativize
                            )
                            .filter(
                                    matcher::matches
                            )
                            .filter(
                                    path -> isInsideAllowedRoots(
                                            searchRoot.resolve(path)
                                    )
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
                return ToolExecutionResult.success(
                        "(no matches)"
                );
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

            return ToolExecutionResult.success(
                    truncated
                            ? output
                                    + System.lineSeparator()
                                    + "... results truncated"
                            : output
            );
        } catch (IOException
                 | UncheckedIOException exception) {
            return ToolExecutionResult.failure(
                    "Error: " + exception.getMessage()
            );
        }
    }

    /** 检查候选路径的真实目标仍位于 allowed roots 内。 */
    private boolean isInsideAllowedRoots(
            Path candidate
    ) {
        try {
            return paths.isInsideAllowedRoots(
                    candidate.toRealPath()
            );
        } catch (IOException exception) {
            return false;
        }
    }
}
