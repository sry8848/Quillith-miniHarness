package dev.learn.agent.manual.hook.hooks;

import com.anthropic.models.messages.MessageParam;
import dev.learn.agent.manual.background.BackgroundTaskScheduler;
import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 将一个 AgentLoop 的后台任务结果接入模型调用前和停止前生命周期。
 */
public final class BackgroundTaskHook implements AgentHook {

    // 当前 AgentLoop 独有的后台任务状态和完成通知队列。
    private final BackgroundTaskScheduler backgroundScheduler;

    /**
     * 创建后台任务生命周期 Hook。
     *
     * @param backgroundScheduler 当前 AgentLoop 的后台调度器
     */
    public BackgroundTaskHook(
            BackgroundTaskScheduler backgroundScheduler
    ) {
        this.backgroundScheduler =
                Objects.requireNonNull(
                        backgroundScheduler,
                        "BackgroundTaskScheduler 不能为空"
                );
    }

    /**
     * 在模型请求前交付已经完成且尚未消费的后台结果。
     */
    @Override
    public HookEffect beforeModelCall(
            List<MessageParam> messages
    ) {
        // 已完成结果只从当前 AgentLoop 的队列中取出一次。
        List<BackgroundTaskScheduler.CompletedTask> completedTasks =
                backgroundScheduler.collectCompleted();

        if (completedTasks.isEmpty()) {
            return HookEffect.proceed();
        }

        return HookEffect.addContext(
                formatNotifications(
                        completedTasks
                )
        );
    }

    /**
     * 停止前等待当前 AgentLoop 的全部后台任务，并阻止本次停止。
     */
    @Override
    public HookEffect onStop(
            List<MessageParam> messages
    ) {
        // 已完成但还未交付的任务同样必须阻止最终回答。
        if (!backgroundScheduler.hasOutstandingTasks()) {
            return HookEffect.proceed();
        }

        // 等待真实命令结束，再把结果作为下一轮模型上下文交付。
        backgroundScheduler.awaitAllCompleted();
        List<BackgroundTaskScheduler.CompletedTask> completedTasks =
                backgroundScheduler.collectCompleted();

        return new HookEffect(
                HookEffect.Decision.BLOCK,
                "后台命令已经全部结束，请根据执行结果继续处理。",
                null,
                null,
                List.of(
                        formatNotifications(
                                completedTasks
                        )
                )
        );
    }

    /**
     * 把后台结果格式化为模型可读取的任务通知。
     *
     * @param tasks 已完成后台任务
     * @return 合并后的通知文本
     */
    private static String formatNotifications(
            List<BackgroundTaskScheduler.CompletedTask> tasks
    ) {
        return tasks.stream()
                .map(
                        task -> "<task_notification>\n"
                                + "  <task_id>"
                                + task.id()
                                + "</task_id>\n"
                                + "  <status>"
                                + task.status()
                                + "</status>\n"
                                + "  <command>"
                                + task.command()
                                + "</command>\n"
                                + "  <result>"
                                + task.output()
                                + "</result>\n"
                                + "</task_notification>"
                )
                .collect(
                        Collectors.joining(
                                "\n\n"
                        )
                );
    }
}
