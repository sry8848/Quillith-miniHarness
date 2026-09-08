package dev.learn.agent.manual.context;

import com.anthropic.models.messages.*;
import dev.learn.agent.manual.utils.WorkspacePathResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 控制单轮 ToolResult 进入上下文的字符预算及完整结果落盘。 */
public final class ContextManager {
    // 同一批次的字符预算，以及文件引用中保留的预览长度。
    private static final long TOOL_RESULT_BUDGET_CHARACTERS = 200_000L;
    private static final int TOOL_RESULT_PREVIEW_CHARACTERS = 2_000;
    private static final String PERSISTED_TOOL_RESULT_PREFIX = "[[agent:persisted-tool-result]]";
    private final WorkspacePathResolver paths;
    private final String sessionToolResultDirectory;

    /** @param paths 工具结果文件共用的工作区路径边界 */
    public ContextManager(WorkspacePathResolver paths) {
        // 1. 每次启动使用独立结果目录，沿用既有预算文件布局。
        this.paths = paths;
        this.sessionToolResultDirectory = ".task_outputs/tool-results/" + UUID.randomUUID();
    }

    /**
     * 限制同一轮工具结果进入模型上下文的总字符数。
     *
     * 超出预算时，优先把最大的结果写入工作区内的会话目录，
     * 再用文件引用和短预览替换原始内容。
     *
     * 输入列表和原始内容块都不会被直接修改。
     * 只有全部持久化计划能够满足预算时，才开始写文件。
     *
     * @param toolResultBlocks 当前模型回复产生的全部工具结果
     * @return 可以安全加入消息历史的不可变内容块列表
     */
    public List<ContentBlockParam> applyToolResultBudget(
            List<ContentBlockParam> toolResultBlocks
    ) {

        // 校验调用方必须提供本轮工具结果批次。
        Objects.requireNonNull(
                toolResultBlocks,
                "工具结果列表不能为空"
        );

        // 收集后续排序、持久化和原位置替换所需的信息。
        List<ToolResultCandidate> candidates =
                new ArrayList<>();

        // 累加的是 Java 字符数，不冒充模型的实际 Token 数。
        long totalCharacters =
                0L;

        /*
         * 第一阶段：建立预算视图。
         *
         * candidates 保存后续排序和替换共同需要的三项信息：
         * 原列表位置、原协议块和完整文本。
         * 这一阶段只读取输入，不修改消息，也不写文件。
         */
        for (int index = 0;
             index < toolResultBlocks.size();
             index++) {
            // 读取当前位置的内容块，并尽早暴露内部 null 数据。
            ContentBlockParam contentBlock =
                    Objects.requireNonNull(
                            toolResultBlocks.get(index),
                            "工具结果列表不能包含 null，索引："
                                    + index
                    );

            /*
             * 这是 AgentLoop 内部传入的可信契约：
             * 该列表只能包含 tool_result。
             *
             * 出现其他类型说明调用方组装错误，应立即失败，
             * 不能静默跳过后继续计算一个错误预算。
             */
            if (!contentBlock.isToolResult()) {
                throw new IllegalArgumentException(
                        "工具结果批次包含非 tool_result 内容块，索引："
                                + index
                );
            }

            // 从 SDK 联合类型中取得工具结果协议块。
            ToolResultBlockParam toolResult =
                    contentBlock.asToolResult();

            // 当前 AgentLoop 约定每个工具结果都必须携带 content。
            ToolResultBlockParam.Content content =
                    toolResult.content()
                            .orElseThrow(
                                    () -> new IllegalStateException(
                                            "tool_result 缺少 content，toolUseId："
                                                    + toolResult.toolUseId()
                                    )
                            );

            /*
             * 当前 AgentLoop 明确只生成字符串工具结果。
             *
             * 如果未来接入图片或文档结果，应重新设计预算单位；
             * 这里不为尚不存在的数据类型做静默兼容。
             */
            if (!content.isString()) {
                throw new IllegalStateException(
                        "当前上下文管理器只支持字符串 tool_result，toolUseId："
                                + toolResult.toolUseId()
                );
            }

            // 取得参与预算计算和文件持久化的完整字符串。
            String output =
                    content.asString();

            // 保存原索引，排序后仍能回到正确位置替换结果。
            candidates.add(
                    new ToolResultCandidate(
                            index,
                            toolResult,
                            output
                    )
            );

            // 累加本轮所有工具结果的活跃上下文占用。
            totalCharacters +=
                    output.length();
        }

        // 预算内保持完整结果，不进行有损处理。
        if (totalCharacters
                <= TOOL_RESULT_BUDGET_CHARACTERS) {
            return List.copyOf(
                    toolResultBlocks
            );
        }

        /*
         * 优先处理最大结果，可以用最少的文件引用释放最多上下文。
         */
        candidates.sort(
                Comparator.comparingInt(
                                (ToolResultCandidate candidate) ->
                                        candidate.content()
                                                .length()
                        )
                        .reversed()
        );

        // 用预计值模拟每次替换对上下文预算的影响。
        long projectedCharacters =
                totalCharacters;

        // 暂存已经确认有效、但尚未产生文件副作用的替换计划。
        List<PersistencePlan> persistencePlans =
                new ArrayList<>();

        /*
         * 第二阶段：选择需要落盘的结果。
         *
         * 这里只计算替换后的预计字符数，不执行文件写入。
         * 从最大的结果开始，通常只需落盘少量文件就能回到预算内。
         *
         * 如果所有有效文件引用仍不能满足预算，方法会在产生副作用前失败，
         * 而不是写完文件后才发现模型上下文仍然过大。
         */
        for (ToolResultCandidate candidate
                : candidates) {
            // 达到预算后停止选择，避免继续写出模型用不到的文件。
            if (projectedCharacters
                    <= TOOL_RESULT_BUDGET_CHARACTERS) {
                break;
            }

            // 使用应用生成的 UUID 文件名，不让模型提供的 ID 进入路径。
            String relativePath =
                    sessionToolResultDirectory
                            + "/"
                            + UUID.randomUUID()
                            + ".txt";

            // 预先构造模型最终看到的短引用，用于计算真实替换收益。
            String reference =
                    buildPersistedReference(
                            relativePath,
                            candidate.content()
                    );

            /*
             * 大量极短结果可能使总量超限，
             * 但把短内容替换成更长的文件引用只会让问题恶化。
             */
            if (reference.length()
                    >= candidate.content()
                    .length()) {
                continue;
            }

            // 当前候选确实可以释放上下文，加入待执行计划。
            persistencePlans.add(
                    new PersistencePlan(
                            candidate,
                            relativePath,
                            reference
                    )
            );

            // 先扣除将要移出上下文的完整结果。
            projectedCharacters -=
                    candidate.content()
                            .length();

            // 再计入文件引用和短预览占用的字符数。
            projectedCharacters +=
                    reference.length();
        }

        // 所有有效替换仍无法满足预算时，明确终止而不是返回超限结果。
        if (projectedCharacters
                > TOOL_RESULT_BUDGET_CHARACTERS) {
            throw new IllegalStateException(
                    "工具结果总长度超过预算，"
                            + "但文件引用无法继续缩短上下文"
            );
        }

        /*
         * 第三阶段：保存完整输出。
         *
         * 到这里已经确认整组替换可以满足预算，因此才允许产生文件副作用。
         * 任一写入失败都会抛出异常；调用方原来的内容块没有被修改，
         * AgentLoop 不会收到一份只有部分引用生效的消息历史。
         */
        for (PersistencePlan plan
                : persistencePlans) {
            writePersistedToolResult(
                    plan
            );
        }

        /*
         * 第四阶段：生成新的模型上下文。
         *
         * 先复制原列表，后续只替换计划命中的位置；
         * 调用方持有的原列表及其内容块保持不变。
         */
        List<ContentBlockParam> limitedBlocks =
                new ArrayList<>(
                        toolResultBlocks
                );

        // 按持久化计划逐个替换新列表中的原始结果块。
        for (PersistencePlan plan
                : persistencePlans) {
            /*
             * 使用 toBuilder 保留 toolUseId、isError、
             * cacheControl 和 SDK 可能携带的附加属性。
             *
             * 如果重新从零构造 ToolResultBlockParam，
             * 很容易只复制 content 而丢失协议元数据。
             */
            ToolResultBlockParam updatedResult =
                    plan.candidate()
                            .block()
                            .toBuilder()
                            .content(
                                    plan.reference()
                            )
                            .build();

            // 在原索引放回新的协议块，保持多工具结果的顺序不变。
            limitedBlocks.set(
                    plan.candidate()
                            .blockIndex(),
                    ContentBlockParam.ofToolResult(
                            updatedResult
                    )
            );
        }

        // 返回不可变结果，作为 AgentLoop 即将写入历史的正式批次。
        return List.copyOf(
                limitedBlocks
        );
    }

    /**
     * 构造返回给模型的持久化结果引用。
     *
     * @param relativePath 模型可以通过 read_file 重新读取的工作区相对路径
     * @param output       原始完整工具输出
     * @return 文件引用、长度和短预览
     */
    private String buildPersistedReference(
            String relativePath,
            String output
    ) {
        // 计算预览切口；短结果不会为了满足固定长度而补充无效字符。
        int previewEnd =
                Math.min(
                        output.length(),
                        TOOL_RESULT_PREVIEW_CHARACTERS
                );

        /*
         * Java 的 char 是 UTF-16 单元。
         * 如果切口正好位于 emoji 等代理对中间，需要向前移动一位，
         * 避免生成包含半个 Unicode 字符的预览。
         */
        if (previewEnd > 0
                && previewEnd < output.length()
                && Character.isHighSurrogate(
                output.charAt(
                        previewEnd - 1
                )
        )
                && Character.isLowSurrogate(
                output.charAt(
                        previewEnd
                )
        )) {
            previewEnd--;
        }

        // 只截取模型需要立即看到的开头，完整内容仍保存在文件中。
        String preview =
                output.substring(
                        0,
                        previewEnd
                );

        // 使用固定格式向模型同时暴露恢复路径、原始规模和有限预览。
        return """
                %s
                Full output path: %s
                Original characters: %d
                Preview:
                %s
                [End persisted tool result preview]
                """.formatted(
                PERSISTED_TOOL_RESULT_PREFIX,
                relativePath,
                output.length(),
                preview
        );
    }

    /**
     * 把一个已规划的完整工具结果写入工作区。
     *
     * @param plan 已确认能够减少上下文占用的持久化计划
     */
    private void writePersistedToolResult(
            PersistencePlan plan
    ) {
        try {
            // 通过统一解析器得到工作区内的安全写入目标。
            Path outputPath =
                    paths.resolveForWrite(
                            plan.relativePath()
                    );

            // 会话目录按需创建，不在没有大结果时产生空运行目录。
            Files.createDirectories(
                    outputPath.getParent()
            );

            /*
             * CREATE_NEW 保证不会覆盖已有结果。
             * UUID 即使发生极低概率碰撞，也会明确失败而不是破坏旧文件。
             */
            Files.writeString(
                    outputPath,
                    plan.candidate()
                            .content(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            // 文件持久化失败会破坏“引用一定可读取”的契约，因此立即终止。
            throw new UncheckedIOException(
                    "无法持久化完整工具结果："
                            + plan.relativePath(),
                    exception
            );
        }
    }

    /**
     * 一个等待参与工具结果预算计算的字符串结果。
     *
     * @param blockIndex 原列表中的位置
     * @param block      原始工具结果块
     * @param content    原始完整字符串
     */
    private record ToolResultCandidate(
            int blockIndex,
            ToolResultBlockParam block,
            String content
    ) {
    }

    /**
     * 一个已经确认可以减少上下文占用的文件替换计划。
     *
     * @param candidate   待持久化的工具结果
     * @param relativePath 工作区相对文件路径
     * @param reference    返回给模型的文件引用
     */
    private record PersistencePlan(
            ToolResultCandidate candidate,
            String relativePath,
            String reference
    ) {
    }

}
