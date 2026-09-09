package dev.learn.agent.manual.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 在单一后台线程中依次提取记忆并按条件整理。 */
public final class MemoryBackgroundProcessor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryBackgroundProcessor.class);
    private static final int CONSOLIDATE_AFTER_NEW_FILES = 5;
    private static final Duration MAINTENANCE_INTERVAL = Duration.ofHours(2);

    private final MemoryWorkStore workStore;
    private final MemoryExtractor extractor;
    private final MemoryRepository repository;
    private final MemoryConsolidator consolidator;
    private final BooleanSupplier memoryEnabled;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-background");
        thread.setDaemon(true);
        return thread;
    });

    /** 创建后台处理器。 */
    public MemoryBackgroundProcessor(
            MemoryWorkStore workStore,
            MemoryExtractor extractor,
            MemoryRepository repository,
            MemoryConsolidator consolidator,
            BooleanSupplier memoryEnabled
    ) {
        this.workStore = Objects.requireNonNull(workStore, "workStore 不能为空");
        this.extractor = Objects.requireNonNull(extractor, "extractor 不能为空");
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.consolidator = Objects.requireNonNull(consolidator, "consolidator 不能为空");
        this.memoryEnabled = Objects.requireNonNull(memoryEnabled, "memoryEnabled 不能为空");
    }

    /** 启动恢复处理和两小时维护时钟。 */
    public void start() {
        // 1. 进程重启后立即尝试恢复尚未删除的工作。
        wake();

        // 定时检查从本进程启动两小时后开始；恢复工作由上面的 wake() 立即处理。
        executor.scheduleWithFixedDelay(this::runTimedMaintenance, MAINTENANCE_INTERVAL.toMillis(),
                MAINTENANCE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 在新工作入队或重新启用记忆后请求一次后台处理。 */
    public void wake() {
        executor.execute(this::runPendingAndThreshold);
    }

    private void runPendingAndThreshold() {
        if (!memoryEnabled.getAsBoolean()) {
            return;
        }
        drainPendingTasks();
        consolidateWhenThresholdReached();
    }

    private void runTimedMaintenance() {
        if (!memoryEnabled.getAsBoolean()) {
            return;
        }
        // 1. 先排空已持久化工作，再决定是否需要低频全量整理。
        drainPendingTasks();
        if (workStore.maintenanceState().unconsolidatedCount() > 0) {
            consolidate();
        }
    }

    private void drainPendingTasks() {
        List<MemoryExtractionTask> tasks = workStore.pendingTasks();
        for (MemoryExtractionTask task : tasks) {
            try {
                // 1. 提取器只返回候选，仓库在此处只创建新的主题文件。
                List<MemoryEntry> created = repository.createNew(extractor.extract(task));
                workStore.completeTaskAndIncrement(task.taskId(), created.size());
            } catch (Exception exception) {
                // 工作不删除，下一次唤醒会按至少一次语义重试。
                LOGGER.warn("后台记忆提取失败，保留工作等待重试。task_id={}", task.taskId(), exception);
            }
        }
    }

    private void consolidateWhenThresholdReached() {
        if (workStore.maintenanceState().unconsolidatedCount() >= CONSOLIDATE_AFTER_NEW_FILES) {
            consolidate();
        }
    }

    private void consolidate() {
        try {
            // 1. 只有整理成功后才清除计数，失败时保留下一次触发机会。
            consolidator.consolidate();
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
