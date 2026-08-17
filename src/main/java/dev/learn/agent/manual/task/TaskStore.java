package dev.learn.agent.manual.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 任务存储。
 *
 * 主要职责：
 *
 * 1. 把任务保存到工作区的 .tasks/*.json
 * 2. 创建、读取、列举任务
 * 3. 判断任务依赖是否完成
 * 4. 管理任务状态：
 *
 *    PENDING → IN_PROGRESS → COMPLETED
 */
public final class TaskStore {

    /**
     * 所有任务统一保存在：
     *
     * .tasks/
     */
    private static final String TASK_DIRECTORY = ".tasks";

    /**
     * 创建任务时使用随机 ID。
     *
     * 如果随机 ID 正好和旧任务冲突，
     * 最多重新生成 100 次。
     */
    private static final int MAX_ID_ATTEMPTS = 100;

    /**
     * 用于产生随机任务 ID。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Jackson JSON 映射器。
     *
     * 用于：
     *
     * TaskRecord → JSON
     * JSON → TaskRecord
     */
    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    /**
     * 工作区路径解析器。
     *
     * 任务文件必须限制在当前工作区内部，
     * 具体安全路径检查交给 WorkspacePathResolver。
     */
    private final WorkspacePathResolver paths;

    /**
     * 创建当前工作区对应的 TaskStore。
     */
    public TaskStore(WorkspacePathResolver paths) {
        this.paths = Objects.requireNonNull(
                paths,
                "工作区路径解析器不能为空"
        );
    }


    // =========================================================
    // 创建任务
    // =========================================================

    /**
     * 创建一个新的 PENDING 任务。
     *
     * blockedBy 表示该任务依赖哪些其他任务。
     */
    public TaskRecord create(
            String subject,
            String description,
            List<String> blockedBy
    ) {

        /*
         * 先校验 subject / description。
         *
         * trim() 去除前后空格。
         */
        String normalizedSubject =
                Objects.requireNonNull(
                        subject,
                        "任务主题不能为空"
                ).trim();

        String normalizedDescription =
                Objects.requireNonNull(
                        description,
                        "任务说明不能为空引用"
                ).trim();

        if (normalizedSubject.isEmpty()) {
            throw new IllegalArgumentException(
                    "任务主题不能为空"
            );
        }


        /*
         * LinkedHashSet：
         *
         * 1. 去除重复依赖
         * 2. 保持原本传入的顺序
         *
         * 例如：
         *
         * [A, B, A]
         *
         * 变成：
         *
         * [A, B]
         */
        LinkedHashSet<String> dependencies =
                new LinkedHashSet<>(
                        Objects.requireNonNull(
                                blockedBy,
                                "任务依赖不能为空"
                        )
                );


        /*
         * 确认每一个依赖任务都真实存在。
         *
         * this::get
         *
         * 相当于：
         *
         * for (String id : dependencies) {
         *     this.get(id);
         * }
         *
         * 某个任务不存在时 get() 会抛异常，
         * 从而阻止创建一个带错误依赖的任务。
         */
        dependencies.forEach(this::get);


        try {

            /*
             * 获取 .tasks 目录。
             *
             * 如果目录不存在就创建。
             */
            Path requestedDirectory =
                    paths.resolveForWrite(TASK_DIRECTORY);

            Files.createDirectories(requestedDirectory);

            /*
             * 目录存在以后，再取得其真实路径。
             */
            Path directory =
                    paths.resolveExisting(TASK_DIRECTORY);


            /*
             * 随机生成 task ID。
             *
             * 最多尝试 MAX_ID_ATTEMPTS 次。
             */
            for (
                    int attempt = 0;
                    attempt < MAX_ID_ATTEMPTS;
                    attempt++
            ) {

                /*
                 * 例如：
                 *
                 * task_a8f317ac
                 */
                String taskId =
                        "task_"
                                + String.format(
                                "%08x",
                                RANDOM.nextInt()
                        );


                /*
                 * 新创建的任务默认：
                 *
                 * status = PENDING
                 * owner = null
                 */
                TaskRecord task =
                        new TaskRecord(
                                taskId,
                                normalizedSubject,
                                normalizedDescription,
                                TaskStatus.PENDING,
                                null,
                                List.copyOf(dependencies)
                        );


                /*
                 * 对应文件：
                 *
                 * .tasks/task_xxxxxxxx.json
                 */
                Path target =
                        directory.resolve(
                                taskId + ".json"
                        );


                try {

                    /*
                     * 把任务序列化成 JSON 后写入文件。
                     *
                     * CREATE_NEW 很重要：
                     *
                     * 如果文件已经存在，不允许覆盖，
                     * 而是直接抛 FileAlreadyExistsException。
                     */
                    Files.writeString(
                            target,
                            serialize(task),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    );

                    // 创建成功，返回任务
                    return task;

                } catch (FileAlreadyExistsException ignored) {

                    /*
                     * 极低概率生成了重复 ID。
                     *
                     * 不覆盖原任务，
                     * 回到 for 循环重新生成一个 ID。
                     */
                }
            }

        } catch (IOException exception) {

            /*
             * 把 Java 的 IOException
             * 包装成运行时异常。
             */
            throw new UncheckedIOException(
                    "无法创建持久化任务",
                    exception
            );
        }


        /*
         * 连续 100 次都产生重复 ID 时才会到这里。
         */
        throw new IllegalStateException(
                "无法分配唯一任务 ID"
        );
    }


    // =========================================================
    // 查询单个任务
    // =========================================================

    /**
     * 根据 taskId 读取一个任务。
     */
    public TaskRecord get(String taskId) {

        /*
         * 先校验任务 ID 格式。
         */
        String id = TaskRecord.requireId(taskId);

        /*
         * 构造路径，例如：
         *
         * .tasks/task_12345678.json
         */
        String taskPath =
                TASK_DIRECTORY
                        + "/"
                        + id
                        + ".json";

        try {

            /*
             * 先获取一个安全的候选路径，
             * 并检查文件是否存在。
             */
            Path candidate =
                    paths.resolveForWrite(taskPath);

            if (Files.notExists(candidate)) {
                throw new IllegalArgumentException(
                        "任务不存在：" + id
                );
            }


            /*
             * 获取已经存在的真实文件路径。
             */
            Path file =
                    paths.resolveExisting(taskPath);


            /*
             * JSON 文件
             *    ↓
             * TaskRecord
             */
            TaskRecord task =
                    JSON.readValue(
                            file.toFile(),
                            TaskRecord.class
                    );


            /*
             * 防止：
             *
             * 文件名是 task_A.json
             *
             * 但 JSON 内容中写：
             *
             * {
             *   "id": "task_B"
             * }
             *
             * 两者不一致说明数据可能损坏。
             */
            if (!id.equals(task.id())) {
                throw new IllegalStateException(
                        "任务文件 ID 与内容不一致："
                                + id
                                + " != "
                                + task.id()
                );
            }

            return task;

        } catch (JsonProcessingException exception) {

            // JSON 无法解析
            throw new IllegalStateException(
                    "任务文件损坏：" + id,
                    exception
            );

        } catch (IOException exception) {

            // 普通文件读取错误
            throw new UncheckedIOException(
                    "无法读取任务：" + id,
                    exception
            );
        }
    }


    // =========================================================
    // 查询所有任务
    // =========================================================

    /**
     * 获取当前工作区中的全部任务。
     */
    public List<TaskRecord> list() {

        try {

            /*
             * 如果 .tasks 目录还不存在，
             * 说明一个任务都没有。
             */
            Path candidate =
                    paths.resolveForWrite(TASK_DIRECTORY);

            if (Files.notExists(candidate)) {
                return List.of();
            }


            Path directory =
                    paths.resolveExisting(TASK_DIRECTORY);

            List<String> taskIds =
                    new ArrayList<>();


            /*
             * 找到 .tasks 目录下面所有 *.json 文件。
             */
            try (
                    DirectoryStream<Path> files =
                            Files.newDirectoryStream(
                                    directory,
                                    "*.json"
                            )
            ) {

                for (Path file : files) {

                    String filename =
                            file.getFileName().toString();

                    /*
                     * task_123.json
                     *
                     * 去掉：
                     *
                     * .json
                     *
                     * 得到：
                     *
                     * task_123
                     */
                    taskIds.add(
                            filename.substring(
                                    0,
                                    filename.length()
                                            - ".json".length()
                            )
                    );
                }
            }


            /*
             * 文件系统返回文件的顺序不一定固定。
             *
             * 所以主动按照 taskId 排序，
             * 保证每次输出顺序稳定。
             */
            taskIds.sort(
                    Comparator.naturalOrder()
            );


            /*
             * taskId
             *    ↓ get()
             * TaskRecord
             */
            return taskIds
                    .stream()
                    .map(this::get)
                    .toList();

        } catch (IOException exception) {

            throw new UncheckedIOException(
                    "无法列举持久化任务",
                    exception
            );
        }
    }


    // =========================================================
    // 判断任务能不能开始
    // =========================================================

    /**
     * 所有依赖任务全部完成，
     * 当前任务才可以开始。
     */
    public boolean canStart(String taskId) {

        /*
         * incompleteDependencies(...)
         *
         * 返回还没有完成的依赖。
         *
         * 如果为空：
         *
         * []
         *
         * 就代表所有依赖都完成了。
         */
        return incompleteDependencies(
                get(taskId)
        ).isEmpty();
    }


    // =========================================================
    // 认领任务
    // =========================================================

    /**
     * 把：
     *
     * PENDING
     *
     * 转换成：
     *
     * IN_PROGRESS
     *
     * 并绑定 owner。
     */
    public TaskRecord claim(
            String taskId,
            String owner
    ) {

        // 校验 owner
        String normalizedOwner =
                requireOwner(owner);

        // 获取任务当前状态
        TaskRecord current =
                get(taskId);


        /*
         * 只有 PENDING 的任务才能认领。
         */
        if (
                current.status()
                        != TaskStatus.PENDING
        ) {
            throw new IllegalStateException(
                    "任务不是 pending，不能认领："
                            + current.id()
            );
        }


        /*
         * 检查是否还有未完成依赖。
         */
        List<String> incomplete =
                incompleteDependencies(current);

        if (!incomplete.isEmpty()) {

            /*
             * 例如：
             *
             * task_B 仍然依赖 task_A，
             * 而 task_A 没完成。
             *
             * 那么 B 不能被认领。
             */
            throw new IllegalStateException(
                    "任务仍被依赖阻塞："
                            + String.join(
                            ", ",
                            incomplete
                    )
            );
        }


        /*
         * TaskRecord 看起来是不可变对象。
         *
         * 因此这里不是：
         *
         * current.setStatus(...)
         *
         * 而是创建一个新的 TaskRecord。
         */
        TaskRecord claimed =
                new TaskRecord(
                        current.id(),
                        current.subject(),
                        current.description(),

                        // PENDING → IN_PROGRESS
                        TaskStatus.IN_PROGRESS,

                        // 绑定执行者
                        normalizedOwner,

                        current.blockedBy()
                );


        // 覆盖保存新的任务状态
        save(claimed);

        return claimed;
    }


    // =========================================================
    // 完成任务
    // =========================================================

    /**
     * 当前 owner 完成自己已经认领的任务。
     *
     * IN_PROGRESS → COMPLETED
     */
    public CompletionResult complete(
            String taskId,
            String owner
    ) {

        // 校验 owner
        String normalizedOwner =
                requireOwner(owner);

        // 获取当前任务
        TaskRecord current =
                get(taskId);


        /*
         * 必须处于 IN_PROGRESS。
         */
        if (
                current.status()
                        != TaskStatus.IN_PROGRESS
        ) {
            throw new IllegalStateException(
                    "任务不是 in_progress，不能完成："
                            + current.id()
            );
        }


        /*
         * 必须由认领这个任务的人完成。
         *
         * 张三认领的任务，
         * 李四不能调用 complete 完成它。
         */
        if (
                !normalizedOwner.equals(
                        current.owner()
                )
        ) {
            throw new IllegalStateException(
                    "任务属于 "
                            + current.owner()
                            + "，不是 "
                            + normalizedOwner
            );
        }


        /*
         * 创建 COMPLETED 状态的新 TaskRecord。
         */
        TaskRecord completed =
                new TaskRecord(
                        current.id(),
                        current.subject(),
                        current.description(),

                        // IN_PROGRESS → COMPLETED
                        TaskStatus.COMPLETED,

                        // owner 保留
                        current.owner(),

                        current.blockedBy()
                );


        // 保存完成状态
        save(completed);


        /*
         * 找出因为当前任务完成，
         * 而“刚刚解除阻塞”的下游任务。
         */
        List<TaskRecord> unblocked =
                list()
                        .stream()

                        // 还没有被认领
                        .filter(task ->
                                task.status()
                                        == TaskStatus.PENDING
                        )

                        /*
                         * 必须直接依赖本次完成的任务。
                         *
                         * 例如：
                         *
                         * B.blockedBy = [A]
                         *
                         * 此时完成 A，
                         * 才考虑 B。
                         */
                        .filter(task ->
                                task.blockedBy()
                                        .contains(
                                                completed.id()
                                        )
                        )

                        /*
                         * 不仅 A 完成了，
                         * B 的其他依赖也必须全部完成。
                         */
                        .filter(task ->
                                incompleteDependencies(task)
                                        .isEmpty()
                        )

                        .toList();


        /*
         * 同时返回：
         *
         * 1. 本次完成的任务
         * 2. 本次解除阻塞的任务
         */
        return new CompletionResult(
                completed,
                unblocked
        );
    }


    // =========================================================
    // 检查未完成依赖
    // =========================================================

    /**
     * 返回一个任务目前仍未完成的所有依赖 ID。
     */
    private List<String> incompleteDependencies(
            TaskRecord task
    ) {

        List<String> incomplete =
                new ArrayList<>();


        /*
         * 遍历：
         *
         * blockedBy = [A, B, C]
         */
        for (
                String dependencyId :
                task.blockedBy()
        ) {

            try {

                /*
                 * 读取依赖任务。
                 *
                 * 只要状态不是 COMPLETED，
                 * 就说明仍然阻塞当前任务。
                 */
                if (
                        get(dependencyId).status()
                                != TaskStatus.COMPLETED
                ) {
                    incomplete.add(
                            dependencyId
                    );
                }

            } catch (
                    IllegalArgumentException exception
            ) {

                /*
                 * 一个比较重要的保护逻辑。
                 *
                 * 假设原本：
                 *
                 * B depends on A
                 *
                 * 但用户手动把 A.json 删除了。
                 *
                 * 不能因为“找不到 A”
                 * 就认为 A 已完成。
                 *
                 * 所以仍然把 A 看成未完成依赖。
                 */
                incomplete.add(
                        dependencyId
                );
            }
        }


        /*
         * 返回不可修改的 List 副本。
         */
        return List.copyOf(
                incomplete
        );
    }


    // =========================================================
    // 保存任务
    // =========================================================

    /**
     * 更新已有任务。
     *
     * 使用：
     *
     * 临时文件
     *    ↓
     * 原子替换正式文件
     */
    private void save(TaskRecord task) {

        try {

            /*
             * 找到：
             *
             * .tasks/task_xxx.json
             */
            Path target =
                    paths.resolveExisting(
                            TASK_DIRECTORY
                                    + "/"
                                    + task.id()
                                    + ".json"
                    );


            /*
             * 先在同一个目录创建临时文件。
             *
             * 例如：
             *
             * .task-xxxxx.tmp
             */
            Path temporary =
                    Files.createTempFile(
                            target.getParent(),
                            ".task-",
                            ".tmp"
                    );


            /*
             * 新数据先写进临时文件，
             * 不直接修改正式任务文件。
             */
            Files.writeString(
                    temporary,
                    serialize(task),
                    StandardCharsets.UTF_8
            );


            /*
             * 临时文件写成功以后，
             * 再一次性替换正式文件。
             *
             * ATOMIC_MOVE：
             * 尽可能保证替换操作是原子的。
             *
             * REPLACE_EXISTING：
             * 替换原来的任务文件。
             */
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );

        } catch (IOException exception) {

            throw new UncheckedIOException(
                    "无法更新任务："
                            + task.id(),
                    exception
            );
        }
    }


    // =========================================================
    // JSON 序列化
    // =========================================================

    /**
     * TaskRecord → JSON 字符串。
     */
    private static String serialize(
            TaskRecord task
    ) {

        try {

            /*
             * writerWithDefaultPrettyPrinter()
             *
             * 让 JSON 格式化输出，
             * 方便人直接查看 .json 文件。
             */
            return JSON
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(task)
                    + "\n";

        } catch (
                JsonProcessingException exception
        ) {

            throw new IllegalStateException(
                    "无法序列化任务："
                            + task.id(),
                    exception
            );
        }
    }


    // =========================================================
    // owner 校验
    // =========================================================

    /**
     * 校验应用程序提供的 owner。
     */
    private static String requireOwner(
            String owner
    ) {

        /*
         * null → 抛异常
         *
         * "  Alice  "
         *     ↓
         * "Alice"
         */
        String value =
                Objects.requireNonNull(
                        owner,
                        "任务 owner 不能为空"
                ).trim();


        /*
         * "" 或 "   " 都不允许。
         */
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "任务 owner 不能为空"
            );
        }

        return value;
    }


    // =========================================================
    // 完成任务的返回结果
    // =========================================================

    /**
     * 一次 complete() 的执行结果。
     *
     * completed：
     *     本次完成的任务
     *
     * unblocked：
     *     因为这个任务完成而刚刚解除阻塞的任务
     */
    public record CompletionResult(
            TaskRecord completed,
            List<TaskRecord> unblocked
    ) {

        /*
         * record 的紧凑构造器。
         *
         * 用来保证 CompletionResult 内部的数据合法。
         */
        public CompletionResult {

            completed =
                    Objects.requireNonNull(
                            completed,
                            "完成任务不能为空"
                    );

            /*
             * 同时做：
             *
             * 1. null 校验
             * 2. 创建不可修改 List 副本
             */
            unblocked =
                    List.copyOf(
                            Objects.requireNonNull(
                                    unblocked,
                                    "解除阻塞任务不能为空"
                            )
                    );
        }
    }
}