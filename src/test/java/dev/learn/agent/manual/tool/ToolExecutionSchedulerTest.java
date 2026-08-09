package dev.learn.agent.manual.tool;

// 引入 JUnit 断言和测试注解。
import org.junit.jupiter.api.Test;

// 引入并发测试所需的 JDK 类型。
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// 引入当前测试使用的断言方法。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证工具调度器的并发批次和独占顺序契约。
 */
class ToolExecutionSchedulerTest {

    /**
     * 验证连续的并发安全工具可以在任一工具结束前同时启动。
     */
    @Test
    void shouldRunConsecutiveSafeToolsConcurrently() {
        // 使用闩锁确认两个任务都已进入执行阶段，再统一放行。
        CountDownLatch started =
                new CountDownLatch(
                        2
                );
        CompletableFuture<Void> release =
                new CompletableFuture<>();

        // 在同一轮调度器中提交两个并发安全工具。
        try (ToolExecutionScheduler scheduler =
                     new ToolExecutionScheduler()) {
            CompletableFuture<ToolExecutionResult> first =
                    scheduler.submit(
                            true,
                            () -> {
                                // 标记第一个安全工具已启动，并等待测试统一放行。
                                started.countDown();
                                release.join();
                                return ToolExecutionResult.success(
                                        "first"
                                );
                            }
                    );

            CompletableFuture<ToolExecutionResult> second =
                    scheduler.submit(
                            true,
                            () -> {
                                // 标记第二个安全工具已启动，并等待测试统一放行。
                                started.countDown();
                                release.join();
                                return ToolExecutionResult.success(
                                        "second"
                                );
                            }
                    );

            // 即使断言失败也要释放任务，避免关闭调度器时等待未完成工具。
            try {
                assertTrue(
                        started.await(
                                2,
                                TimeUnit.SECONDS
                        ),
                        "两个并发安全工具应当同时进入执行阶段"
                );
            } catch (InterruptedException exception) {
                // 测试线程被中断时恢复中断标记，并让断言明确失败。
                Thread.currentThread()
                        .interrupt();
                throw new AssertionError(
                        "等待安全工具启动时测试线程被中断",
                        exception
                );
            } finally {
                release.complete(
                        null
                );
            }

            // 两个 Future 应分别保留自己的工具执行结果。
            assertEquals(
                    "first",
                    first.join()
                            .content()
            );
            assertEquals(
                    "second",
                    second.join()
                            .content()
            );
        }
    }

    /**
     * 验证独占工具等待此前安全批次，后续安全工具也等待独占工具。
     */
    @Test
    void shouldCreateOrderingBoundaryAroundExclusiveTool() {
        // 控制独占工具之前的安全批次。
        CountDownLatch safeStarted =
                new CountDownLatch(
                        2
                );
        CompletableFuture<Void> releaseSafeBatch =
                new CompletableFuture<>();
        AtomicInteger safeFinished =
                new AtomicInteger();

        // 控制独占工具本身，并记录它看到的前置完成状态。
        CountDownLatch exclusiveStarted =
                new CountDownLatch(
                        1
                );
        CompletableFuture<Void> releaseExclusive =
                new CompletableFuture<>();
        AtomicBoolean exclusiveObservedSafeCompletion =
                new AtomicBoolean();
        AtomicBoolean exclusiveFinished =
                new AtomicBoolean();

        // 记录独占工具之后的安全工具何时启动及其观察结果。
        CountDownLatch followingSafeStarted =
                new CountDownLatch(
                        1
                );
        AtomicBoolean followingObservedExclusiveCompletion =
                new AtomicBoolean();

        // 按“安全、安全、独占、安全”的模型调用顺序提交任务。
        try (ToolExecutionScheduler scheduler =
                     new ToolExecutionScheduler()) {
            CompletableFuture<ToolExecutionResult> firstSafe =
                    scheduler.submit(
                            true,
                            () -> {
                                // 等待统一放行后结束第一个安全工具。
                                safeStarted.countDown();
                                releaseSafeBatch.join();
                                safeFinished.incrementAndGet();
                                return ToolExecutionResult.success(
                                        "safe-1"
                                );
                            }
                    );

            CompletableFuture<ToolExecutionResult> secondSafe =
                    scheduler.submit(
                            true,
                            () -> {
                                // 等待统一放行后结束第二个安全工具。
                                safeStarted.countDown();
                                releaseSafeBatch.join();
                                safeFinished.incrementAndGet();
                                return ToolExecutionResult.success(
                                        "safe-2"
                                );
                            }
                    );

            CompletableFuture<ToolExecutionResult> exclusive =
                    scheduler.submit(
                            false,
                            () -> {
                                // 独占工具启动时，前面的安全批次必须已经全部结束。
                                exclusiveObservedSafeCompletion.set(
                                        safeFinished.get() == 2
                                );
                                exclusiveStarted.countDown();
                                releaseExclusive.join();
                                exclusiveFinished.set(
                                        true
                                );
                                return ToolExecutionResult.success(
                                        "exclusive"
                                );
                            }
                    );

            CompletableFuture<ToolExecutionResult> followingSafe =
                    scheduler.submit(
                            true,
                            () -> {
                                // 后续安全工具启动时，前面的独占工具必须已经结束。
                                followingObservedExclusiveCompletion.set(
                                        exclusiveFinished.get()
                                );
                                followingSafeStarted.countDown();
                                return ToolExecutionResult.success(
                                        "safe-3"
                                );
                            }
                    );

            // 分阶段放行任务并检查两个独占边界。
            try {
                assertTrue(
                        safeStarted.await(
                                2,
                                TimeUnit.SECONDS
                        ),
                        "独占工具之前的安全批次应当并发启动"
                );
                assertFalse(
                        exclusiveStarted.await(
                                200,
                                TimeUnit.MILLISECONDS
                        ),
                        "独占工具不能越过尚未完成的安全批次"
                );

                // 完成前置安全批次后，独占工具应当启动。
                releaseSafeBatch.complete(
                        null
                );
                assertTrue(
                        exclusiveStarted.await(
                                2,
                                TimeUnit.SECONDS
                        ),
                        "前置安全批次结束后独占工具应当启动"
                );
                assertFalse(
                        followingSafeStarted.await(
                                200,
                                TimeUnit.MILLISECONDS
                        ),
                        "后续安全工具不能越过尚未完成的独占工具"
                );

                // 完成独占工具后，后续安全工具应当启动。
                releaseExclusive.complete(
                        null
                );
                assertTrue(
                        followingSafeStarted.await(
                                2,
                                TimeUnit.SECONDS
                        ),
                        "独占工具结束后后续安全工具应当启动"
                );
            } catch (InterruptedException exception) {
                // 测试线程被中断时恢复中断标记，并让断言明确失败。
                Thread.currentThread()
                        .interrupt();
                throw new AssertionError(
                        "等待工具调度阶段时测试线程被中断",
                        exception
                );
            } finally {
                // 所有失败路径都释放闸门，避免调度器关闭时发生测试死锁。
                releaseSafeBatch.complete(
                        null
                );
                releaseExclusive.complete(
                        null
                );
            }

            // 等待全部结果并核对每个任务实际观察到的顺序状态。
            firstSafe.join();
            secondSafe.join();
            exclusive.join();
            followingSafe.join();
            assertTrue(
                    exclusiveObservedSafeCompletion.get(),
                    "独占工具必须看到前置安全批次全部完成"
            );
            assertTrue(
                    followingObservedExclusiveCompletion.get(),
                    "后续安全工具必须看到独占工具完成"
            );
        }
    }
}
