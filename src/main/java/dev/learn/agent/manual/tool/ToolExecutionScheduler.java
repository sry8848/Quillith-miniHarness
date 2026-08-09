package dev.learn.agent.manual.tool;

// 引入调度状态、并发原语和工具执行回调所需的 JDK 类型。
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 按模型调用顺序调度一轮响应中的工具执行。
 *
 * 连续的并发安全工具可以同时执行；独占工具等待此前工具完成，
 * 后续工具也必须等待该独占工具完成，从而保持副作用顺序。
 */
public final class ToolExecutionScheduler implements AutoCloseable {

    // 限制单轮模型响应中同时占用外部资源的工具数量。
    // 这是资源保护软阈值，不代表虚拟线程本身只能并发十个任务。
    private static final int MAX_CONCURRENT_TOOLS =
            10;

    // 为每个工具调用创建独立虚拟线程，阻塞等待不会占用平台线程。
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    // 使用公平信号量限制实际进入工具管线的并发数量。
    private final Semaphore permits =
            new Semaphore(
                    MAX_CONCURRENT_TOOLS,
                    true
            );

    // 保存最近一个独占工具的完成屏障，后续安全工具必须等待它。
    private CompletableFuture<Void> lastExclusiveBarrier =
            CompletableFuture.completedFuture(
                    null
            );

    // 保存当前连续安全批次的完成屏障，下一个独占工具必须等待整批完成。
    private final List<CompletableFuture<Void>> currentSafeBarriers =
            new ArrayList<>();

    // 保存全部任务的完成屏障，关闭调度器前必须等待已经接收的调用结束。
    private final List<CompletableFuture<Void>> submittedBarriers =
            new ArrayList<>();

    /**
     * 提交一个工具任务并建立它与此前工具的顺序关系。
     *
     * @param concurrencySafe 是否允许与同一安全批次中的工具并发
     * @param action 完整的工具执行管线
     * @return 可以按模型调用顺序收集的异步执行结果
     */
    public CompletableFuture<ToolExecutionResult> submit(
            boolean concurrencySafe,
            Supplier<ToolExecutionResult> action
    ) {
        Objects.requireNonNull(
                action,
                "工具执行动作不能为空"
        );

        // 安全工具只等待最近的独占边界，连续安全工具因此可以并发。
        CompletableFuture<Void> dependency =
                concurrencySafe
                        ? lastExclusiveBarrier
                        : exclusiveDependency();

        // 任务立即提交到虚拟线程，并在线程内等待依赖。
        // 设计意图：关闭执行器时所有依赖任务都已提交，不会因异步续接遇到拒绝提交。
        CompletableFuture<ToolExecutionResult> resultFuture =
                CompletableFuture.supplyAsync(
                        () -> executeAfter(
                                dependency,
                                action
                        ),
                        executor
                );

        // 把结果转换为只表示“已经结束”的非异常屏障。
        // 设计意图：一个工具意外失败不能阻止后续工具生成与 tool_use 配对的结果。
        CompletableFuture<Void> completionBarrier =
                resultFuture.handle(
                        (result, error) -> null
                );

        // 根据工具类型更新下一次提交所依赖的顺序边界。
        if (concurrencySafe) {
            currentSafeBarriers.add(
                    completionBarrier
            );
        } else {
            lastExclusiveBarrier =
                    completionBarrier;
            currentSafeBarriers.clear();
        }

        // 记录任务，确保 try-with-resources 退出前不会遗留后台执行。
        submittedBarriers.add(
                completionBarrier
        );

        // 返回保留工具真实成功、失败或异常状态的 Future。
        return resultFuture;
    }

    /**
     * 等待已建立的顺序依赖后执行工具动作。
     *
     * @param dependency 当前工具必须等待的完成屏障
     * @param action 完整的工具执行管线
     * @return 工具执行结果
     */
    private ToolExecutionResult executeAfter(
            CompletableFuture<Void> dependency,
            Supplier<ToolExecutionResult> action
    ) {
        // 先等待模型顺序要求的边界，等待期间不占用并发许可。
        dependency.join();

        // 取得资源许可后才进入 Hook 和工具执行管线。
        permits.acquireUninterruptibly();

        // 无论工具正常返回还是抛出异常，都必须归还并发许可。
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    /**
     * 构造下一个独占工具必须等待的完成屏障。
     *
     * @return 当前安全批次的联合屏障；没有安全任务时返回最近独占屏障
     */
    private CompletableFuture<Void> exclusiveDependency() {
        // 没有新的安全工具时，独占工具直接等待前一个独占工具。
        if (currentSafeBarriers.isEmpty()) {
            return lastExclusiveBarrier;
        }

        // 当前安全批次都依赖最近的独占边界，因此等待整批即可覆盖此前顺序。
        return CompletableFuture.allOf(
                currentSafeBarriers.toArray(
                        CompletableFuture[]::new
                )
        );
    }

    /**
     * 等待全部已提交工具结束并关闭虚拟线程执行器。
     */
    @Override
    public void close() {
        // 等待全部非异常完成屏障，保证不存在仍在访问共享工作区的后台工具。
        CompletableFuture.allOf(
                submittedBarriers.toArray(
                        CompletableFuture[]::new
                )
        ).join();

        // 所有任务结束后关闭执行器，不再接收新的工具调用。
        executor.close();
    }
}
