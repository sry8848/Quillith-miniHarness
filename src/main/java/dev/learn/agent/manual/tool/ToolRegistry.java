package dev.learn.agent.manual.tool;

import com.anthropic.models.messages.ToolUnion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 保存 Agent 可以使用的所有工具。
 */
public final class ToolRegistry {

    // 记录工具实现抛出的完整异常，模型只接收精简错误。
    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    ToolRegistry.class
            );

    /*
     * key 是模型使用的工具名称，
     * value 是对应的 Java 工具实现。
     *
     * LinkedHashMap 会保持工具注册顺序，
     * 发送给模型时顺序更加稳定。
     */
    private final Map<String, AgentTool> tools =
            new LinkedHashMap<>();

    // 保存当前 Harness 统一使用的重试预算和退避参数。
    private final ToolRetryPolicy retryPolicy;

    /**
     * 使用应用默认重试策略创建工具注册表。
     */
    public ToolRegistry() {
        this(
                ToolRetryPolicy.defaults()
        );
    }

    /**
     * 使用指定重试策略创建工具注册表。
     *
     * @param retryPolicy 当前 Harness 的 Tool 重试策略
     */
    public ToolRegistry(
            ToolRetryPolicy retryPolicy
    ) {
        this.retryPolicy =
                Objects.requireNonNull(
                        retryPolicy,
                        "ToolRetryPolicy 不能为空"
                );
    }

    /**
     * 注册一个工具。
     */
    public void register(AgentTool tool) {
        Objects.requireNonNull(
                tool,
                "AgentTool 不能为空"
        );

        String toolName =
                tool.definition()
                        .name();

        /*
         * putIfAbsent 只在名称不存在时加入工具。
         *
         * 如果已经存在同名工具，
         * previous 会保存原来的工具对象。
         */
        AgentTool previous =
                tools.putIfAbsent(
                        toolName,
                        tool
                );

        /*
         * 两个同名工具会导致模型请求无法确定应该执行哪一个，
         * 因此在程序启动时立即报错。
         */
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Duplicate tool name: "
                            + toolName
            );
        }
    }

    /**
     * 按参数顺序注册一组工具。
     *
     * 每个工具仍然通过 {@link #register(AgentTool)} 完成校验，
     * 因此批量注册不会绕过空值和重复名称检查。
     *
     * @param tools 需要注册的工具
     */
    public void registerAll(
            AgentTool... tools
    ) {
        Objects.requireNonNull(
                tools,
                "工具数组不能为空"
        );

        for (AgentTool tool : tools) {
            register(tool);
        }
    }

    /**
     * 返回需要发送给模型的全部工具定义。
     */
    public List<ToolUnion> definitions() {
        return tools.values()
                .stream()
                .map(AgentTool::definition)
                .map(ToolUnion::ofTool)
                .toList();
    }

    /**
     * 判断指定工具是否明确声明为并发安全。
     *
     * 工具名称来自已经完成协议解析的 tool_use 块；未知名称按不安全处理，
     * 具体的 unknown tool 错误仍由 {@link #execute(ToolCall)} 返回。
     *
     * @param toolName 模型请求调用的工具名称
     * @return 已注册且明确声明并发安全时返回 true，否则返回 false
     */
    public boolean isConcurrencySafe(
            String toolName
    ) {
        Objects.requireNonNull(
                toolName,
                "工具名称不能为空"
        );

        // 只有注册工具主动声明安全时才允许进入并发批次。
        AgentTool tool =
                tools.get(
                        toolName
                );

        // 未知工具采用默认独占策略，避免错误名称绕过副作用边界。
        return tool != null
                && tool.isConcurrencySafe();
    }

    /**
     * 根据工具调用选择前台或后台调度路径。
     *
     * @param toolCall 已通过协议解析的工具调用
     * @return 已注册工具声明的执行模式；未知工具固定进入前台
     */
    public ToolExecutionMode executionMode(
            ToolCall toolCall
    ) {
        Objects.requireNonNull(
                toolCall,
                "ToolCall 不能为空"
        );

        AgentTool tool =
                tools.get(
                        toolCall.name()
                );

        // 未知工具不能借助错误名称绕过前台工具安全边界。
        return tool == null
                ? ToolExecutionMode.FOREGROUND
                : tool.executionMode(
                        toolCall.input()
                );
    }

    /**
     * 根据模型返回的工具名称执行对应工具。
     *
     * @param toolCall 已通过协议解析的工具调用
     * @return 可以继续回传模型的成功或失败结果
     */
    public ToolExecutionResult execute(
            ToolCall toolCall
    ) {
        Objects.requireNonNull(
                toolCall,
                "ToolCall 不能为空"
        );

        AgentTool tool =
                tools.get(
                        toolCall.name()
                );

        /*
         * 模型输出属于外部输入。
         *
         * 即使发送给模型的工具列表中不存在这个名字，
         * 模型仍可能生成错误的工具名称，因此必须检查。
         */
        if (tool == null) {
            return ToolExecutionResult.failure(
                    "Error: unknown tool: "
                            + toolCall.name()
            );
        }

        // 1. 同一 Tool Call 的所有可重试错误共享这一轮固定 attempts 预算。
        for (int attempt = 1;
             attempt <= retryPolicy.maxAttempts();
             attempt++) {
            try {
                // 2. Tool 只负责业务执行和异常语义转换，Harness 不读取内部错误类型。
                return Objects.requireNonNull(
                        tool.execute(
                                toolCall.input()
                        ),
                        "AgentTool.execute 不能返回 null"
                );
            } catch (RetryableToolException exception) {
                if (attempt == retryPolicy.maxAttempts()) {
                    return finalFailure(
                            toolCall,
                            exception,
                            attempt,
                            true
                    );
                }

                Duration delay =
                        retryDelayAfter(
                                attempt
                        );

                LOGGER.warn(
                        "工具 {} 第 {}/{} 次执行失败，将在 {} ms 后重试：{}",
                        toolCall.name(),
                        attempt,
                        retryPolicy.maxAttempts(),
                        delay.toMillis(),
                        exception.getMessage()
                );

                // 3. 等待只发生在同一调度 action 内，不会越过现有顺序与并发边界。
                if (!sleepBeforeRetry(
                        delay
                )) {
                    return finalFailure(
                            toolCall,
                            new NonRetryableToolException(
                                    "Retry wait was interrupted",
                                    exception
                            ),
                            attempt,
                            false
                    );
                }
            } catch (NonRetryableToolException exception) {
                return finalFailure(
                        toolCall,
                        exception,
                        attempt,
                        false
                );
            } catch (RuntimeException exception) {
                // 未分类异常默认停止，避免程序 Bug 或副作用不确定时重复调用。
                return finalFailure(
                        toolCall,
                        exception,
                        attempt,
                        false
                );
            }
        }

        throw new IllegalStateException(
                "Tool 重试循环未返回结果"
        );
    }

    /**
     * 计算第几次可重试失败后的等待时间。
     *
     * @param failedAttempt 刚刚失败的 attempts，从 1 开始
     * @return 已应用指数退避、抖动和最大值限制后的等待时间
     */
    Duration retryDelayAfter(
            int failedAttempt
    ) {
        if (failedAttempt <= 0) {
            throw new IllegalArgumentException(
                    "failedAttempt 必须大于 0"
            );
        }

        long baseDelayMillis =
                retryPolicy.initialDelay()
                        .toMillis();
        long maxDelayMillis =
                retryPolicy.maxDelay()
                        .toMillis();

        // 1. 每多一次连续失败，基础等待翻倍；先封顶避免整数溢出。
        for (int index = 1;
             index < failedAttempt
                     && baseDelayMillis < maxDelayMillis;
             index++) {
            baseDelayMillis =
                    Math.min(
                            baseDelayMillis * 2,
                            maxDelayMillis
                    );
        }

        // 2. 在 Tool Call 之间加入随机扰动，避免同时失败的调用整齐重试。
        double multiplier =
                retryPolicy.jitterRatio() == 0.0d
                        ? 1.0d
                        : ThreadLocalRandom.current()
                                .nextDouble(
                                        1.0d - retryPolicy.jitterRatio(),
                                        1.0d + retryPolicy.jitterRatio()
                                );
        long jitteredDelayMillis =
                Math.round(
                        baseDelayMillis * multiplier
                );

        // 3. 最终再次限制范围，保证 jitter 不会突破配置的最大等待时间。
        return Duration.ofMillis(
                Math.min(
                        Math.max(
                                jitteredDelayMillis,
                                0L
                        ),
                        maxDelayMillis
                )
        );
    }

    /**
     * 在下一次 Tool 尝试前等待指定时间。
     *
     * @param delay 本次重试前需要等待的时间
     * @return 等待完成时返回 true；线程被中断时返回 false
     */
    private static boolean sleepBeforeRetry(
            Duration delay
    ) {
        try {
            Thread.sleep(
                    delay
            );
            return true;
        } catch (InterruptedException exception) {
            // 中断代表当前执行应尽快结束，不能吞掉信号后继续执行下一次 Tool。
            Thread.currentThread()
                    .interrupt();
            return false;
        }
    }

    /**
     * 记录最终失败并构造可回传给 Agent 的稳定错误结果。
     *
     * @param toolCall 当前完整 Tool Call
     * @param exception 本次最终失败的异常
     * @param attempts 实际已执行次数
     * @param retryExhausted 是否因可重试预算耗尽停止
     * @return 保留最终失败字段的 Tool Result
     */
    private static ToolExecutionResult finalFailure(
            ToolCall toolCall,
            RuntimeException exception,
            int attempts,
            boolean retryExhausted
    ) {
        LOGGER.error(
                "工具 {} 在第 {} 次执行后失败",
                toolCall.name(),
                attempts,
                exception
        );

        String message =
                exception.getMessage();
        String detail =
                message == null || message.isBlank()
                        ? exception.getClass()
                                .getSimpleName()
                        : message;

        return ToolExecutionResult.failure(
                "Tool call failed\n"
                        + "tool: " + toolCall.name() + "\n"
                        + "exception_type: "
                        + exception.getClass()
                        .getSimpleName() + "\n"
                        + "message: " + detail + "\n"
                        + "attempts: " + attempts + "\n"
                        + "retry_exhausted: " + retryExhausted
        );
    }
}
