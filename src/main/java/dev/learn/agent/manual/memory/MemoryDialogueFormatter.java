// 声明记忆对话格式化器所属的包。
package dev.learn.agent.manual.memory;

// 引入 Anthropic 消息和内容块协议类型。
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;

// 引入对话集合和参数检查类型。
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将模型消息历史转换为长期记忆提取器使用的对话文本。
 *
 * 只保留用户和助手可见的普通文本，
 * 排除工具协议、隐藏提醒和其他非对话内容。
 */
public final class MemoryDialogueFormatter {

    /*
     * 最多保留最近十条可见对话。
     *
     * 这是教学版的软阈值，用于限制重复分析的历史范围，
     * 不是经过评测得到的最佳窗口。
     */
    private static final int MAX_VISIBLE_MESSAGES = 10;

    // 该类只提供无状态格式化操作，不需要创建实例。
    private MemoryDialogueFormatter() {
    }

    /**
     * 格式化压缩前的消息快照。
     *
     * @param messages 不包含召回记忆的压缩前消息快照
     * @return 带有 user、assistant 角色标识的对话文本
     * @throws NullPointerException messages 为 null
     * @throws IllegalStateException 消息包含 SDK 无法识别的内容类型
     */
    public static String format(
            List<MessageParam> messages
    ) {
        // 提取快照由调用方提供，不能缺失。
        Objects.requireNonNull(
                messages,
                "messages 不能为 null"
        );

        // 收集真正可交给记忆提取器的用户和助手文本。
        List<String> visibleMessages =
                new ArrayList<>();

        // [核心] 从协议历史中筛选并格式化可见对话。
        for (MessageParam message : messages) {
            // 只接受用户和助手角色，系统消息不属于对话事实。
            String role;

            if (MessageParam.Role.USER.equals(
                    message.role()
            )) {
                role = "user";
            } else if (MessageParam.Role.ASSISTANT.equals(
                    message.role()
            )) {
                role = "assistant";
            } else {
                continue;
            }

            // 从字符串或文本块中读取普通文本。
            String text =
                    extractText(
                            message.content()
                    ).strip();

            // 没有普通文本的工具协议消息不进入提取输入。
            if (text.isBlank()) {
                continue;
            }

            /*
             * [边界：Hook 插入的隐藏提醒留在消息历史中 →
             * 提取器可能把临时指令固化为跨会话记忆]
             */
            if (text.startsWith(
                    "<system-reminder>"
            ) && text.endsWith(
                    "</system-reminder>"
            )) {
                continue;
            }

            // 保存角色和正文，避免模型混淆信息来源。
            visibleMessages.add(
                    role
                            + ":\n"
                            + text
            );
        }

        /*
         * 与教程不同：先过滤工具协议，再选最近十条可见对话。
         *
         * 如果先截取原始消息，连续工具调用可能挤掉
         * 当前用户输入，导致本轮没有可靠的提取依据。
         */
        int startIndex =
                Math.max(
                        0,
                        visibleMessages.size()
                                - MAX_VISIBLE_MESSAGES
                );

        // [核心] 按原始时序拼成 MemoryExtractor 的输入文本。
        return String.join(
                "\n\n",
                visibleMessages.subList(
                        startIndex,
                        visibleMessages.size()
                )
        );
    }

    /**
     * 从 Anthropic 消息联合类型中读取普通文本。
     *
     * @param content 字符串或内容块列表
     * @return 字符串正文，或所有文本块按顺序拼接的正文
     * @throws IllegalStateException content 是当前代码不认识的类型
     */
    private static String extractText(
            MessageParam.Content content
    ) {
        // 字符串消息可以直接作为对话正文。
        if (content.isString()) {
            return content.asString();
        }

        // 内容块消息只读取 text，忽略工具调用和工具结果。
        if (content.isBlockParams()) {
            StringBuilder text =
                    new StringBuilder();

            for (
                    ContentBlockParam block
                    : content.asBlockParams()
            ) {
                // 工具、图片和思考块不属于当前提取输入。
                if (!block.isText()) {
                    continue;
                }

                // 多个文本块之间保留换行边界。
                if (!text.isEmpty()) {
                    text.append('\n');
                }

                // [核心] 追加当前普通文本块。
                text.append(
                        block.asText()
                                .text()
                );
            }

            return text.toString();
        }

        /*
         * [边界：SDK 出现未识别的 content 类型 →
         * 静默忽略会把对话缺失伪装成正常的空提取]
         */
        throw new IllegalStateException(
                "消息包含无法识别的 content 类型"
        );
    }
}