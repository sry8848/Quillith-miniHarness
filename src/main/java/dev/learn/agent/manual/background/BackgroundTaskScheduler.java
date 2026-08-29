package dev.learn.agent.manual.background;

import dev.learn.agent.manual.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 管理一个 AgentLoop 内部所有后台任务的生命周期。
 *
 * ------------------------------------------------------------
 * 这个类解决的核心问题：
 *
 * Agent 调用一个后台工具时，不希望一直阻塞等待真实命令执行完。
 *
 * 因此后台任务分成两个阶段：
 *
 * 1. “启动”
 *
 *    Agent 只需要等待后台任务成功启动，
 *    例如得到：
 *
 *        Background task started: bg_0001
 *
 *    然后 Agent 就可以继续进行下一轮推理。
 *
 * 2. “后台执行”
 *
 *    真实命令继续在虚拟线程中执行。
 *
 *    命令结束后，结果不会直接返回给原来的工具调用，
 *    而是被放进 completedTasks 队列。
 *
 *    之后由 AgentLoop 中的后台任务 Hook 调用 collectCompleted()，
 *    把这些结果再次交给模型。
 *
 *
 * 一个后台任务的生命周期大致是：
 *
 *      start()
 *         |
 *         v
 *   runningTasks
 *         |
 *         | 后台命令执行
 *         v
 *   completedTasks
 *         |
 *         | collectCompleted()
 *         v
 *       Model
 *
 *
 * 注意：
 *
 * “后台命令已经执行完成”
 *
 * 和
 *
 * “后台任务生命周期已经结束”
 *
 * 是两个概念。
 *
 * 即使真实命令已经结束，只要 completedTasks 中的结果
 * 还没有交付给模型，这个任务的生命周期仍然没有结束。
 */
public final class BackgroundTaskScheduler implements AutoCloseable {

    /**
     * 执行后台任务使用的 ExecutorService。
     *
     * newVirtualThreadPerTaskExecutor() 的语义大致是：
     *
     * 每提交一个任务，就让一个新的虚拟线程执行它。
     *
     * 例如：
     *
     * executor.submit(taskA)
     * executor.submit(taskB)
     *
     * 可以看成：
     *
     * VirtualThread-A -> taskA
     * VirtualThread-B -> taskB
     *
     * 后台 Bash 命令、网络请求、IO 等任务往往会长时间阻塞，
     * 因此使用虚拟线程比较合适。
     *
     * 这个 executor 同时负责两类动作：
     *
     * 1. submitLaunch() 提交的“启动动作”
     * 2. start() 提交的“真实后台命令”
     */
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 保存当前“仍然正在执行”的后台任务。
     *
     * key:
     *     任务 ID，例如 bg_0001
     *
     * value:
     *     RunningTask，其中保存：
     *
     *     - id
     *     - 原始 command
     *     - completion 完成屏障
     *
     *
     * 为什么使用 ConcurrentHashMap？
     *
     * 因为多个虚拟线程可能同时：
     *
     * - 新增任务
     * - 删除任务
     *
     * 同时 AgentLoop 又可能：
     *
     * - 查询是否为空
     * - 遍历所有运行任务
     *
     * 因此这里需要线程安全的 Map。
     *
     *
     * 注意：
     *
     * 这里只保存“尚未执行完成”的任务。
     *
     * 一个任务执行完之后，会：
     *
     * runningTasks.remove(taskId)
     */
    private final ConcurrentMap<String, RunningTask> runningTasks =
            new ConcurrentHashMap<>();

    /**
     * 保存：
     *
     * “真实命令已经执行完成，但是结果还没有交付给模型”
     *
     * 的任务。
     *
     * 后台线程是生产者：
     *
     *     completedTasks.add(...)
     *
     * AgentLoop Hook 是消费者：
     *
     *     completedTasks.poll()
     *
     *
     * 使用 ConcurrentLinkedQueue 的原因：
     *
     * 多个后台任务可能同时完成并向队列写入结果，
     * AgentLoop 也可能同时读取结果，
     * 所以这里需要线程安全的并发队列。
     *
     *
     * 队列顺序是“完成顺序”，并不是任务 ID 顺序。
     *
     * 比如：
     *
     * bg_0001 执行 10 秒
     * bg_0002 执行 2 秒
     *
     * 那么队列里很可能是：
     *
     * bg_0002
     * bg_0001
     */
    private final Queue<CompletedTask> completedTasks =
            new ConcurrentLinkedQueue<>();

    /**
     * 当前 AgentLoop 内后台任务的自增序号。
     *
     * 初始值是 1。
     *
     * 依次生成：
     *
     * 1
     * 2
     * 3
     * ...
     *
     * 再通过：
     *
     * String.format("bg_%04d", number)
     *
     * 转成：
     *
     * bg_0001
     * bg_0002
     * bg_0003
     *
     *
     * 使用 AtomicLong 而不是普通 long，
     * 是因为 start() 可能被多个线程并发调用。
     *
     * getAndIncrement() 可以原子地完成：
     *
     * “获取当前值 + 自增”
     *
     * 防止不同任务拿到相同 ID。
     */
    private final AtomicLong nextTaskNumber =
            new AtomicLong(1);

    /**
     * 异步执行一个“后台任务启动动作”。
     *
     * ------------------------------------------------------------
     * 需要特别注意：
     *
     * submitLaunch() 执行的不是最终那个耗时的真实后台命令，
     * 而是“启动后台任务”这一层动作。
     *
     * 可以把流程理解成：
     *
     * Agent
     *   |
     *   | submitLaunch()
     *   v
     * 启动动作
     *   |
     *   | 内部可能调用 start()
     *   v
     * 真实后台命令
     *
     *
     * Agent 当前这一轮工具调用只需要等待：
     *
     * “后台任务是否成功启动”
     *
     * 而不需要等待：
     *
     * “真实命令什么时候执行结束”
     *
     *
     * @param action
     *        后台工具的启动动作。
     *
     *        Supplier<ToolExecutionResult> 表示：
     *
     *            不需要参数，
     *            调用 get() 后得到 ToolExecutionResult。
     *
     *        审批已经由 AgentLoop 的公共工具管线在提交 action 前完成，
     *        这个 action 通常会负责：
     *
     *        - 参数检查
     *        - 调用 start() 创建真实后台任务
     *        - 返回“任务已经启动”的 ToolExecutionResult
     *
     * @return
     *        一个 CompletableFuture。
     *
     *        它只代表“启动动作”什么时候完成，
     *        并不代表真实后台命令什么时候执行完成。
     */
    public CompletableFuture<ToolExecutionResult> submitLaunch(
            Supplier<ToolExecutionResult> action
    ) {

        /**
         * CompletableFuture.supplyAsync(action, executor)
         *
         * 意思是：
         *
         * 不在当前线程直接执行：
         *
         *     action.get()
         *
         * 而是把 action 提交给 executor。
         *
         * executor 会让虚拟线程执行这个 action。
         *
         * action 最终返回的 ToolExecutionResult
         * 会成为这个 CompletableFuture 的结果。
         */
        return CompletableFuture.supplyAsync(
                action,
                executor
        );
    }

    /**
     * 创建并启动一个真正的后台命令。
     *
     * 这是整个 Scheduler 最核心的方法。
     *
     * 完整流程：
     *
     * 1. 生成 taskId
     * 2. 创建 completion 完成屏障
     * 3. 创建 RunningTask
     * 4. 先登记到 runningTasks
     * 5. 再把真实命令提交给 executor
     * 6. 后台命令执行完成
     * 7. 创建 CompletedTask
     * 8. 先放进 completedTasks
     * 9. 再从 runningTasks 删除
     * 10. 最后完成 completion
     *
     *
     * @param command
     *        原始命令。
     *
     *        例如：
     *
     *            "python train.py"
     *
     *        保存它的原因是，任务完成后还需要告诉模型：
     *
     *            “刚才完成的是哪个命令？”
     *
     *
     * @param commandAction
     *        真正执行后台命令的动作。
     *
     *        Supplier<ToolExecutionResult> 表示：
     *
     *            调用 commandAction.get()
     *
     *        就真正开始执行命令，
     *        并最终返回 ToolExecutionResult。
     *
     *
     * @return
     *        当前 AgentLoop 内唯一的后台任务 ID，
     *        例如：
     *
     *            bg_0001
     */
    public String start(
            String command,
            Supplier<ToolExecutionResult> commandAction
    ) {

        /**
         * 原子地获取任务序号并自增。
         *
         * 假设当前值为 1：
         *
         * getAndIncrement()
         *
         * 返回：
         *
         *     1
         *
         * 然后 nextTaskNumber 内部变成：
         *
         *     2
         */
        long taskNumber =
                nextTaskNumber.getAndIncrement();

        /**
         * %04d 表示：
         *
         * 十进制整数，总宽度至少 4 位，
         * 不足的部分在左边补 0。
         *
         * 例如：
         *
         * 1   -> 0001
         * 12  -> 0012
         * 123 -> 0123
         */
        String taskId =
                String.format(
                        "bg_%04d",
                        taskNumber
                );

        /**
         * completion 是这个后台任务的“完成信号”。
         *
         * 注意：
         *
         * 它不负责保存后台命令结果。
         *
         * 真正的命令结果之后会保存在：
         *
         *     completedTasks
         *
         *
         * completion 只负责表达：
         *
         *     “这个真实后台命令什么时候结束？”
         *
         * 初始状态：
         *
         *     未完成
         *
         * 后面任务结束时：
         *
         *     completion.complete(null)
         *
         * 将它标记为完成。
         *
         * awaitAllCompleted() 就是靠这些 Future
         * 等待所有真实后台任务结束。
         */
        CompletableFuture<Void> completion =
                new CompletableFuture<>();

        /**
         * 创建运行中的任务记录。
         */
        RunningTask runningTask =
                new RunningTask(
                        taskId,
                        command,
                        completion
                );

        /**
         * 非常关键的并发顺序：
         *
         * 一定先登记：
         *
         *     runningTasks.put(...)
         *
         * 再真正：
         *
         *     executor.submit(...)
         *
         *
         * 为什么？
         *
         * 因为后台命令可能执行得非常快。
         *
         * 如果反过来：
         *
         *     executor.submit(task);
         *     runningTasks.put(...);
         *
         * 那么可能出现：
         *
         * Thread A：
         *     submit task
         *
         * VirtualThread：
         *     task 立刻执行完成
         *
         * Thread A：
         *     这时才登记 runningTasks
         *
         * Scheduler 的状态就可能产生错误。
         *
         *
         * 所以原则是：
         *
         *     先登记状态，
         *     再允许异步执行。
         */
        runningTasks.put(
                taskId,
                runningTask
        );

        /**
         * 将真正的后台命令提交到虚拟线程执行器。
         *
         * start() 本身不会等待这个 lambda 执行结束。
         *
         * executor.submit(...) 提交之后，
         * start() 很快就会返回 taskId。
         */
        executor.submit(
                () -> {

                    /**
                     * 保存真实后台命令最终得到的工具执行结果。
                     */
                    ToolExecutionResult result;

                    try {

                        /**
                         * 真正执行后台命令。
                         *
                         * 比如 commandAction 可能最终执行：
                         *
                         *     bashTool.execute("python train.py")
                         */
                        result = commandAction.get();

                    } catch (RuntimeException exception) {

                        /**
                         * 如果真实命令执行过程中出现未预期的 RuntimeException，
                         * 不允许整个生命周期直接断掉。
                         *
                         * 否则后面的：
                         *
                         * - completedTasks.add()
                         * - runningTasks.remove()
                         * - completion.complete()
                         *
                         * 都可能没有机会执行。
                         *
                         * 那么 Scheduler 会永久认为这个任务仍然在运行。
                         *
                         *
                         * 所以这里把异常转换成一个普通的失败结果，
                         * 让生命周期可以继续正常收尾。
                         */
                        result = ToolExecutionResult.failure(
                                "Error: background task failed unexpectedly: "
                                        + exception.getMessage()
                        );
                    }

                    /**
                     * 将真实命令的最终结果包装成 CompletedTask。
                     *
                     * status：
                     *
                     * 如果 ToolExecutionResult 表示错误：
                     *
                     *     failed
                     *
                     * 否则：
                     *
                     *     completed
                     */
                    CompletedTask completedTask =
                            new CompletedTask(
                                    runningTask.id(),
                                    runningTask.command(),
                                    result.error()
                                            ? "failed"
                                            : "completed",
                                    result.content()
                            );

                    /**
                     * 又一个非常关键的并发顺序：
                     *
                     * 一定：
                     *
                     * 先加入 completedTasks
                     *
                     * 再从 runningTasks 删除。
                     *
                     *
                     * 原因：
                     *
                     * Scheduler 使用：
                     *
                     *     !runningTasks.isEmpty()
                     *       ||
                     *     !completedTasks.isEmpty()
                     *
                     * 判断是否还有未结束的生命周期。
                     *
                     *
                     * 如果反过来：
                     *
                     * 1. runningTasks.remove()
                     * 2. completedTasks.add()
                     *
                     * 那么两行之间可能短暂出现：
                     *
                     * runningTasks   = empty
                     * completedTasks = empty
                     *
                     * 此时另一个线程调用：
                     *
                     *     hasOutstandingTasks()
                     *
                     * 会错误得到 false。
                     *
                     *
                     * 当前顺序：
                     *
                     *     completedTasks.add()
                     *     runningTasks.remove()
                     *
                     * 会产生一个安全的短暂重叠状态：
                     *
                     * runningTasks：
                     *     有 bg_0001
                     *
                     * completedTasks：
                     *     也有 bg_0001 的结果
                     *
                     *
                     * 两边暂时都有是安全的。
                     *
                     * 两边暂时都没有才危险。
                     */
                    completedTasks.add(
                            completedTask
                    );

                    /**
                     * 结果已经安全进入完成队列，
                     * 现在可以解除“正在运行”状态。
                     */
                    runningTasks.remove(
                            runningTask.id()
                    );

                    /**
                     * 最后再完成 completion。
                     *
                     * 这保证：
                     *
                     * 一旦 awaitAllCompleted() 被解除阻塞，
                     * Scheduler 已经完成：
                     *
                     * 1. completedTasks.add(...)
                     * 2. runningTasks.remove(...)
                     *
                     *
                     * 因此：
                     *
                     * awaitAllCompleted();
                     * collectCompleted();
                     *
                     * 是一个合理的调用顺序。
                     */
                    completion.complete(null);
                }
        );

        /**
         * 注意：
         *
         * 返回 taskId 的时候，
         * 真正的后台命令通常还没有执行完成。
         *
         * 这正是“后台任务”的意义。
         */
        return taskId;
    }

    /**
     * 判断 Scheduler 中是否还有尚未彻底完成生命周期的后台任务。
     *
     * 这里需要检查两种状态：
     *
     * 1. runningTasks 不为空
     *
     *    表示还有真实命令正在执行。
     *
     * 2. completedTasks 不为空
     *
     *    表示虽然真实命令已经执行完，
     *    但是结果还没有被 AgentLoop Hook 取走并交付给模型。
     *
     *
     * 只有：
     *
     * runningTasks 为空
     *
     * 并且
     *
     * completedTasks 也为空
     *
     * 才能认为整个后台任务生命周期已经清空。
     */
    public boolean hasOutstandingTasks() {
        return !runningTasks.isEmpty()
                || !completedTasks.isEmpty();
    }

    /**
     * 等待当前已经登记在 runningTasks 中的后台命令全部结束。
     *
     * 注意：
     *
     * 这里等待的是：
     *
     *     “真实命令执行结束”
     *
     * 不是：
     *
     *     “完成结果已经被模型消费”
     *
     *
     * 所以这个方法返回之后，
     * completedTasks 很可能仍然非空。
     */
    public void awaitAllCompleted() {

        /**
         * runningTasks.values()
         *
         * 得到所有当前正在运行的 RunningTask。
         *
         *
         * .map(RunningTask::completion)
         *
         * 相当于：
         *
         * .map(task -> task.completion())
         *
         * 把每个 RunningTask 映射为它自己的完成 Future。
         *
         *
         * 假设当前有：
         *
         * bg_0001 -> completion1
         * bg_0002 -> completion2
         * bg_0003 -> completion3
         *
         * 最后得到数组：
         *
         * [
         *   completion1,
         *   completion2,
         *   completion3
         * ]
         */
        CompletableFuture<?>[] completions =
                runningTasks.values()
                        .stream()
                        .map(RunningTask::completion)
                        .toArray(
                                CompletableFuture[]::new
                        );

        /**
         * CompletableFuture.allOf(...)
         *
         * 创建一个组合 Future。
         *
         * 只有：
         *
         * completion1
         * completion2
         * completion3
         *
         * 全部完成，
         *
         * allOf(...) 返回的 Future 才会完成。
         *
         *
         * join()
         *
         * 会等待这个组合 Future 完成。
         */
        CompletableFuture.allOf(
                completions
        ).join();
    }

    /**
     * 取走当前所有已经完成、但是尚未交付的后台任务结果。
     *
     * 返回结果按照 completedTasks 队列顺序排列，
     * 也就是大体按照“任务完成顺序”排列。
     *
     *
     * 最关键的语义：
     *
     * collectCompleted() 不只是“查看”结果，
     * 而是：
     *
     *     取出 + 删除
     *
     * 因此某个 CompletedTask 不会被后续
     * collectCompleted() 再次返回。
     */
    public List<CompletedTask> collectCompleted() {

        /**
         * 临时列表，用来保存本次从并发队列中取出的结果。
         */
        List<CompletedTask> completed =
                new ArrayList<>();

        CompletedTask task;

        /**
         * Queue.poll()：
         *
         * - 如果队列非空：
         *      取出队头并从队列删除
         *
         * - 如果队列为空：
         *      返回 null
         *
         *
         * 所以：
         *
         * while ((task = completedTasks.poll()) != null)
         *
         * 会持续把当前队列里的任务全部取走。
         *
         *
         * 例如：
         *
         * completedTasks：
         *
         * [A, B, C]
         *
         * 第一次 poll：
         *
         * 返回 A
         * 队列变成 [B, C]
         *
         * 第二次：
         *
         * 返回 B
         * 队列变成 [C]
         *
         * 第三次：
         *
         * 返回 C
         * 队列变成 []
         *
         * 第四次：
         *
         * 返回 null
         *
         * 循环结束。
         */
        while ((task = completedTasks.poll()) != null) {
            completed.add(task);
        }

        /**
         * List.copyOf(completed)
         *
         * 创建不可修改的结果列表。
         *
         * 调用者只能读取这批完成结果，
         * 不能通过返回值修改 Scheduler 的状态。
         */
        return List.copyOf(completed);
    }

    /**
     * 关闭 Scheduler。
     *
     * BackgroundTaskScheduler 实现了 AutoCloseable，
     * 因此可以配合 try-with-resources 使用。
     *
     * shutdownNow() 会：
     *
     * 1. 停止接受新任务
     * 2. 尝试中断正在执行的 Java 任务
     *
     *
     * 但需要注意：
     *
     * Java 虚拟线程
     *
     * 和
     *
     * 操作系统中的 Bash / Python / npm 等子进程
     *
     * 并不是同一个生命周期。
     *
     * shutdownNow() 主要处理这里 Executor 中的 Java 任务。
     *
     * 真正操作系统 Process 的终止，
     * 由 BashTool 自己的关闭逻辑负责。
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 表示一个“仍然正在执行”的后台任务。
     *
     * record 很适合这种纯数据对象。
     *
     * Java 会自动生成：
     *
     * - 构造方法
     * - id()
     * - command()
     * - completion()
     * - equals()
     * - hashCode()
     * - toString()
     *
     *
     * 这个 record 是 private，
     * 因为它只是 Scheduler 内部维护并发状态的实现细节。
     *
     * @param id
     *        后台任务 ID，例如 bg_0001
     *
     * @param command
     *        原始命令，例如 python train.py
     *
     * @param completion
     *        表示真实后台命令是否已经执行结束的完成屏障
     */
    private record RunningTask(
            String id,
            String command,
            CompletableFuture<Void> completion
    ) {
    }

    /**
     * 表示一个：
     *
     * “已经执行完成，但还没有交付给模型”
     *
     * 的后台任务。
     *
     * 这个 record 是 public，
     * 因为 AgentLoop / 生命周期 Hook
     * 需要读取这些数据。
     *
     *
     * @param id
     *        后台任务 ID，例如 bg_0001
     *
     * @param command
     *        原始后台命令
     *
     * @param status
     *        当前实现只有两个值：
     *
     *        completed
     *        failed
     *
     * @param output
     *        ToolExecutionResult 中保存的完整命令输出
     */
    public record CompletedTask(
            String id,
            String command,
            String status,
            String output
    ) {
    }
}
