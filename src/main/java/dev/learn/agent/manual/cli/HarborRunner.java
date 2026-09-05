package dev.learn.agent.manual.cli;

import dev.learn.agent.manual.ManualAgent;

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
 * 通过 Harbor 的机器输入通道，把多个 Turn 提交给同一个 ManualAgent。
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
    private static final String OK =
            "OK";

    /**
     * 持续接收 Harbor instruction，并顺序提交给当前 Trial 唯一的父 Agent。
     *
     * @param manualAgent 当前 Harbor Trial 唯一的父 Agent
     * @throws IOException FIFO 通信或 Turn 记忆读写失败
     */
    public void run(
            ManualAgent manualAgent
    ) throws IOException {
        Objects.requireNonNull(
                manualAgent,
                "ManualAgent 不能为空"
        );

        int turnNumber = 0;

        // 1. 同一个进程持续等待 Harbor 的顺序 Turn。
        while (true) {
            String request =
                    readRequest();
            String instruction =
                    decodeInstruction(
                            request
                    );
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

            // 2. 一条 Harbor instruction 只调用一次现有 submit()。
            manualAgent.submit(
                    instruction
            );

            // 3. submit() 完整返回后，Harbor 才能继续执行本轮 verifier。
            System.out.println(
                    "[Harbor Turn "
                            + turnNumber
                            + " Success, pid="
                            + processId
                            + "]"
            );
            writeSuccess();
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
    static String decodeInstruction(
            String request
    ) {
        // 1. 第一版只接受 TURN，不提前扩展控制消息或协议状态。
        if (!request.startsWith(
                TURN_PREFIX
        )) {
            throw new IllegalArgumentException(
                    "未知 Harbor 请求类型"
            );
        }

        // 2. Base64 只承担单行传输边界，解码结果原样提交给模型。
        byte[] instructionBytes =
                Base64.getDecoder()
                        .decode(
                                request.substring(
                                        TURN_PREFIX.length()
                                )
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
        // 1. 一次响应对应一个 Turn，写完立即关闭并交还 Harbor。
        try (BufferedWriter writer =
                     Files.newBufferedWriter(
                             RESPONSE_PIPE,
                             StandardCharsets.UTF_8,
                             StandardOpenOption.WRITE
                     )) {
            writer.write(
                    OK
            );
            writer.newLine();
        }
    }
}
