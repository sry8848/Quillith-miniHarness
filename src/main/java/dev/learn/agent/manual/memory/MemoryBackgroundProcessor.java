package dev.learn.agent.manual.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 在单一后台线程中依次提取记忆并按条件整理。 */
public final class MemoryBackgroundProcessor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryBackgroundProcessor.class);
    private static final int ORGANIZE_AFTER_NEW_FILES = 5;
    private static final Duration PENDING_RETRY_INTERVAL = Duration.ofMinutes(5);
    private static final Duration MAINTENANCE_INTERVAL = Duration.ofHours(2);

    private final MemoryWorkStore workStore;
    private final MemoryExtractor extractor;
    private final LlmwikiStore llmwikiStore;
    private final MemoryOrganizer organizer;
    private final BooleanSupplier memoryEnabled;
    private final ScheduledExecutorService executor;

    /** 创建生产使用的单线程后台处理器。 */
    public MemoryBackgroundProcessor(
            MemoryWorkStore workStore,
            MemoryExtractor extractor,
            LlmwikiStore llmwikiStore,
            MemoryOrganizer organizer,
            BooleanSupplier memoryEnabled
    ) {
        this(workStore, extractor, llmwikiStore, organizer, memoryEnabled, createExecutor());
    }

    /** 创建允许测试控制调度时钟的后台处理器。 */
    MemoryBackgroundProcessor(
            MemoryWorkStore workStore,
            MemoryExtractor extractor,
            LlmwikiStore llmwikiStore,
            MemoryOrganizer organizer,
            BooleanSupplier memoryEnabled,
            ScheduledExecutorService executor
    ) {
        this.workStore = Objects.requireNonNull(workStore, "workStore 不能为空");
        this.extractor = Objects.requireNonNull(extractor, "extractor 不能为空");
        this.llmwikiStore = Objects.requireNonNull(llmwikiStore, "llmwikiStore 不能为空");
        this.organizer = Objects.requireNonNull(organizer, "organizer 不能为空");
        this.memoryEnabled = Objects.requireNonNull(memoryEnabled, "memoryEnabled 不能为空");
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
    }

    /** 创建生产使用的 daemon 单线程调度器。 */
    private static ScheduledExecutorService createExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-background");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动恢复处理、五分钟兜底和两小时维护时钟。 */
    public void start() {
        // 1. 进程重启后立即尝试恢复尚未删除的工作。
        wake();

        // 2. 没有新 Turn 时仍定期复用正常批次，避免失败任务长期无人唤醒。
        executor.scheduleWithFixedDelay(this::runPendingAndThreshold, PENDING_RETRY_INTERVAL.toMillis(),
                PENDING_RETRY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        // 3. 低增长整理从本进程启动两小时后开始，不改变原有 count > 0 语义。
        executor.scheduleWithFixedDelay(this::runTimedMaintenance, MAINTENANCE_INTERVAL.toMillis(),
                MAINTENANCE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 在新工作入队或重新启用记忆后请求一次后台处理。 */
    public void wake() {
        executor.execute(this::runPendingAndThreshold);
    }

    /**
     * 在同一后台队列中执行一次正常批次，等待完成后返回剩余任务数。
     *
     * @return 本次批次结束后仍待处理的 extraction task 数量
     */
    public int runPendingAndWait() {
        Future<Integer> batch = executor.submit(() -> {
            // 1. 复用普通 wake 的完整批次和阈值整理，不建立第二套收尾逻辑。
            runPendingAndThreshold();
            return workStore.pendingTaskCount();
        });

        try {
            // 显式评测屏障必须等待排在它之前的后台工作，才能判断最终问题是否可开始。
            return batch.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待后台记忆处理时被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("后台记忆处理批次失败", exception.getCause());
        }
    }

    private void runPendingAndThreshold() {
        if (!memoryEnabled.getAsBoolean()) {
            return;
        }
        drainPendingTasks();
        organizeWhenThresholdReached();
    }

    private void runTimedMaintenance() {
        if (!memoryEnabled.getAsBoolean()) {
            return;
        }
        // 1. 先排空已持久化工作，再决定是否需要低频全量整理。
        drainPendingTasks();
        if (workStore.maintenanceState().unconsolidatedCount() > 0) {
            organize();
        }
    }

    private void drainPendingTasks() {
        List<MemoryExtractionTask> tasks = workStore.pendingTasks();
        for (MemoryExtractionTask task : tasks) {
            try {
                // 1. 提取器只返回草稿，Store 负责近期落盘和根索引最小追加。
                List<Path> createdPaths = llmwikiStore.writeRecent(extractor.extract(task));
                workStore.completeTaskAndIncrement(task.taskId(), createdPaths.size());
            } catch (Exception exception) {
                // 工作不删除，下一次唤醒会按至少一次语义重试。
                LOGGER.warn("后台记忆提取失败，保留工作等待重试。task_id={}", task.taskId(), exception);
            }
        }
    }

    private void organizeWhenThresholdReached() {
        if (workStore.maintenanceState().unconsolidatedCount() >= ORGANIZE_AFTER_NEW_FILES) {
            organize();
        }
    }

    private void organize() {
        try {
            // 1. 只有整理成功后才清除计数，失败时保留下一次触发机会。
            organizer.organize();
            workStore.clearUnconsolidatedCount();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("后台记忆整理失败，保留整理计数等待重试", exception);
        }
    }

    /** 停止进程内调度；尚未完成的工作已经在 SQLite 中保留。 */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
