package dev.learn.agent.manual.cli;

import dev.learn.agent.manual.AgentSession;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Objects;

/**
 * 通过 Harbor 的机器输入通道，把多个 Turn 提交给同一个 AgentSession。
 */
public final class HarborRunner {

    private static final Path REQUEST_PIPE =
            Path.of(
                    "/tmp/quillith-harbor-session/request.pipe"
            );
    private static final Path RESPONSE_PIPE =
            Path.of(
                    "/tmp/quillith-harbor-session/response.pipe"
            );
    private static final String TURN_PREFIX =
            "TURN ";
    private static final String RECORDED_TURN_PREFIX =
            "RECORDED_TURN ";
    private static final String ANSWER_PREFIX =
            "ANSWER ";
    private static final String NEW_SESSION =
            "NEW_SESSION";
    private static final String WAIT_MEMORY_IDLE =
            "WAIT_MEMORY_IDLE";
    private static final String OK =
            "OK";
    private static final String RESULT_PREFIX =
            "RESULT ";
    private static final String MEMORY_READY =
            "MEMORY_READY";
    private static final String MEMORY_PENDING_PREFIX =
            "MEMORY_PENDING ";

    /**
     * 持续接收 Harbor instruction，并顺序提交给当前 Trial 唯一的父 Agent。
     *
     * @param agentSession 当前 Harbor Trial 唯一的父 Agent
     * @throws IOException FIFO 通信或 Turn 记忆读写失败
     */
    public void run(
            AgentSession agentSession
    ) throws IOException {
        Objects.requireNonNull(
                agentSession,
                "AgentSession 不能为空"
        );

        int turnNumber = 0;

        // 1. 同一个进程持续等待 Harbor 的顺序 Turn。
        while (true) {
            String request =
                    readRequest();
            Request decodedRequest =
                    decodeRequest(
                            request
                    );

            // 2. Session 控制消息不属于用户 Turn，也不触发模型或记忆生命周期。
            if (decodedRequest.type()
                    == RequestType.NEW_SESSION) {
                agentSession.startNewSession();
                writeSuccess();
                continue;
            }

            // 3. 评测收尾只等待 Memory，不创建用户 Turn 或切换 Session。
            if (decodedRequest.type()
                    == RequestType.WAIT_MEMORY_IDLE) {
                int pendingCount =
                        agentSession.waitForMemoryIdle();
                System.out.println(
                        "[Harbor Memory Wait Complete, pending="
                                + pendingCount
                                + "]"
                );
                writeResponse(
                        encodeMemoryStatus(
                                pendingCount
                        )
                );
                continue;
            }

            turnNumber++;

            // PID 只用于验证多个 step 复用了同一个进程，不承担生命周期管理。
            long processId =
                    ProcessHandle.current()
                            .pid();
            System.out.println(
                    "[Harbor Turn "
                            + turnNumber
                            + " Start, pid="
                            + processId
                            + "]"
            );

            // 4. 根据机器请求选择普通、固定历史或需要返回文本的真实 Turn。
            String output =
                    switch (decodedRequest.type()) {
                        case TURN -> {
                            agentSession.submit(
                                    decodedRequest.userText()
                            );
                            yield null;
                        }
                        case RECORDED_TURN -> {
                            agentSession.submitRecorded(
                                    decodedRequest.userText(),
                                    decodedRequest.assistantText()
                            );
                            yield null;
                        }
                        case ANSWER ->
                                agentSession.submit(
                                        decodedRequest.userText()
                                );
                        case NEW_SESSION ->
                                throw new IllegalStateException(
                                        "NEW_SESSION 已在 Turn 分支前处理"
                                );
                        case WAIT_MEMORY_IDLE ->
                                throw new IllegalStateException(
                                        "WAIT_MEMORY_IDLE 已在 Turn 分支前处理"
                                );
                    };

            // 5. submit() 完整返回后，Harbor 才能继续执行本轮 verifier。
            System.out.println(
                    "[Harbor Turn "
                            + turnNumber
                            + " Success, pid="
                            + processId
                            + "]"
            );
            if (decodedRequest.type()
                    == RequestType.ANSWER) {
                writeResult(
                        output
                );
            } else {
                writeSuccess();
            }
        }
    }

    /**
     * 从请求 FIFO 读取当前 Turn 的一行协议消息。
     *
     * @return 一条完整协议消息
     * @throws IOException FIFO 打开、读取或关闭失败
     */
    private static String readRequest()
            throws IOException {
        // 1. Adapter 每轮关闭 writer，因此每个 Turn 重新打开 FIFO reader。
        try (BufferedReader reader =
                     Files.newBufferedReader(
                             REQUEST_PIPE,
                             StandardCharsets.UTF_8
                     )) {
            String request =
                    reader.readLine();
            if (request == null) {
                throw new IOException(
                        "Harbor 请求管道未收到完整请求"
                );
            }
            return request;
        }
    }

    /**
     * 解码一条 Harbor Turn 请求。
     *
     * @param request 单行 TURN 协议消息
     * @return 恢复换行和 Unicode 内容后的原始 instruction
     */
    static Request decodeRequest(
            String request
    ) {
        Objects.requireNonNull(
                request,
                "request 不能为空"
        );

        // 1. NEW_SESSION 不携带文本载荷。
        if (NEW_SESSION.equals(
                request
        )) {
            return new Request(
                    RequestType.NEW_SESSION,
                    null,
                    null
            );
        }

        // 2. Memory 收尾命令不携带文本载荷。
        if (WAIT_MEMORY_IDLE.equals(
                request
        )) {
            return new Request(
                    RequestType.WAIT_MEMORY_IDLE,
                    null,
                    null
            );
        }

        // 3. 保持现有 TURN 的单文本协议。
        if (request.startsWith(
                TURN_PREFIX
        )) {
            return new Request(
                    RequestType.TURN,
                    decodeText(
                            request.substring(
                                    TURN_PREFIX.length()
                            )
                    ),
                    null
            );
        }

        // 4. ANSWER 与 TURN 都提交真实模型，只额外要求返回最终文本。
        if (request.startsWith(
                ANSWER_PREFIX
        )) {
            return new Request(
                    RequestType.ANSWER,
                    decodeText(
                            request.substring(
                                    ANSWER_PREFIX.length()
                            )
                    ),
                    null
            );
        }

        // 5. 固定历史用两个独立 Base64 字段承载 user 和 assistant。
        if (request.startsWith(
                RECORDED_TURN_PREFIX
        )) {
            String payload =
                    request.substring(
                            RECORDED_TURN_PREFIX.length()
                    );
            String[] messages =
                    payload.split(
                            " ",
                            -1
                    );
            if (messages.length != 2) {
                throw new IllegalArgumentException(
                        "RECORDED_TURN 必须包含 user 和 assistant"
                );
            }
            return new Request(
                    RequestType.RECORDED_TURN,
                    decodeText(
                            messages[0]
                    ),
                    decodeText(
                            messages[1]
                    )
            );
        }

        throw new IllegalArgumentException(
                "未知 Harbor 请求类型"
        );
    }

    /**
     * 解码一个 Base64 UTF-8 文本字段。
     *
     * @param encodedText 单行协议中的 Base64 字段
     * @return 原始 UTF-8 文本
     */
    private static String decodeText(
            String encodedText
    ) {
        // 1. Base64 只承担单行传输边界，解码结果原样交还调用方。
        byte[] instructionBytes =
                Base64.getDecoder()
                        .decode(
                                encodedText
                        );
        return new String(
                instructionBytes,
                StandardCharsets.UTF_8
        );
    }

    /**
     * 向响应 FIFO 写入当前 Turn 的正常完成信号。
     *
     * @throws IOException FIFO 打开、写入或关闭失败
     */
    private static void writeSuccess()
            throws IOException {
        writeResponse(
                OK
        );
    }

    /**
     * 向响应 FIFO 写入需要评分的最终 assistant 文本。
     *
     * @param output 最终 assistant 文本
     * @throws IOException FIFO 打开、写入或关闭失败
     */
    private static void writeResult(
            String output
    ) throws IOException {
        writeResponse(
                encodeResult(
                        output
                )
        );
    }

    /**
     * 把最终 assistant 文本编码为单行 RESULT 响应。
     *
     * @param output 最终 assistant 文本
     * @return 可直接写入 FIFO 的单行响应
     */
    static String encodeResult(
            String output
    ) {
        Objects.requireNonNull(
                output,
                "output 不能为空"
        );
        String encodedOutput =
                Base64.getEncoder()
                        .encodeToString(
                                output.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        );
        return RESULT_PREFIX
                + encodedOutput;
    }

    /**
     * 编码一次 Memory 收尾批次的机器状态。
     *
     * @param pendingCount 批次结束后仍待处理的 task 数量
     * @return MEMORY_READY 或携带数量的 MEMORY_PENDING 响应
     */
    static String encodeMemoryStatus(
            int pendingCount
    ) {
        return pendingCount == 0
                ? MEMORY_READY
                : MEMORY_PENDING_PREFIX + pendingCount;
    }

    /**
     * 向响应 FIFO 写入一条完整的单行协议响应。
     *
     * @param response 响应文本
     * @throws IOException FIFO 打开、写入或关闭失败
     */
    private static void writeResponse(
            String response
    ) throws IOException {
        // 1. 一次响应对应一个 Turn，写完立即关闭并交还 Harbor。
        try (BufferedWriter writer =
                     Files.newBufferedWriter(
                             RESPONSE_PIPE,
                             StandardCharsets.UTF_8,
                             StandardOpenOption.WRITE
                     )) {
            writer.write(
                    response
            );
            writer.newLine();
        }
    }

    /** Harbor 机器请求类型。 */
    enum RequestType {
        TURN,
        RECORDED_TURN,
        NEW_SESSION,
        WAIT_MEMORY_IDLE,
        ANSWER
    }

    /**
     * 一条已经完成边界解码的 Harbor 机器请求。
     *
     * @param type 请求类型
     * @param userText TURN、RECORDED_TURN 或 ANSWER 的用户文本
     * @param assistantText RECORDED_TURN 的固定 assistant 文本
     */
    record Request(
            RequestType type,
            String userText,
            String assistantText
    ) {
    }
}
