package dev.learn.agent.manual.tool;

import dev.learn.agent.manual.background.BackgroundTaskScheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 将一轮模型响应中的完整工具调用分流到前台或后台调度器。
 *
 * 本类只决定调度路径，具体 Hook、权限和工具执行仍由调用方提供的公共管线完成。
 */
public final class ToolCallDispatcher implements AutoCloseable {

    // 根据工具名称和输入读取执行模式。
    private final ToolRegistry toolRegistry;

    // 当前模型响应专用的前台顺序调度器。
    private final ToolExecutionScheduler foregroundScheduler;

    // 当前 AgentLoop 共享的后台生命周期调度器。
    private final BackgroundTaskScheduler backgroundScheduler;

    /**
     * 创建一次模型响应的工具分流器。
     *
     * @param toolRegistry 工具注册表
     * @param backgroundScheduler 当前 AgentLoop 的后台调度器
     */
    public ToolCallDispatcher(
            ToolRegistry toolRegistry,
            BackgroundTaskScheduler backgroundScheduler
    ) {
        this.toolRegistry =
                Objects.requireNonNull(
                        toolRegistry,
                        "ToolRegistry 不能为空"
                );
        this.foregroundScheduler =
                new ToolExecutionScheduler();
        this.backgroundScheduler =
                Objects.requireNonNull(
                        backgroundScheduler,
                        "BackgroundTaskScheduler 不能为空"
                );
    }

    /**
     * 按工具声明的执行模式提交公共工具执行管线。
     *
     * @param toolCall 已解析的工具调用
     * @param action 完整工具执行管线
     * @return 按本轮工具结果交付语义完成的 Future
     */
    public CompletableFuture<ToolExecutionResult> submit(
            ToolCall toolCall,
            Supplier<ToolExecutionResult> action
    ) {
        // 后台调用只等待登记启动动作，普通调用继续遵守前台顺序依赖。
        if (toolRegistry.executionMode(toolCall)
                == ToolExecutionMode.BACKGROUND) {
            return backgroundScheduler.submitLaunch(
                    action
            );
        }

        return foregroundScheduler.submit(
                toolRegistry.isConcurrencySafe(
                        toolCall.name()
                ),
                action
        );
    }

    /**
     * 关闭当前响应的前台调度器，不影响 AgentLoop 的后台任务。
     */
    @Override
    public void close() {
        foregroundScheduler.close();
    }
}
