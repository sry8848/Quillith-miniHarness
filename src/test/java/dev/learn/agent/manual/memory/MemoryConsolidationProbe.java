package dev.learn.agent.manual.memory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.utils.WorkspacePathResolver;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 使用归档 Memory 执行一次真实 consolidation 请求，定位网络或 Provider 错误。
 *
 * <p>该类是显式运行的诊断入口，不是默认测试，也不负责修复或重试请求。</p>
 */
public final class MemoryConsolidationProbe {

    private MemoryConsolidationProbe() {
    }

    /**
     * 复制目录由调用脚本准备，本方法只读取环境配置并执行一次整理。
     *
     * @param args workspace、模型名称和 Anthropic-compatible Base URL
     * @throws Exception 路径、DNS、Memory 或 Provider 请求失败
     */
    public static void main(
            String[] args
    ) throws Exception {
        // 1. 拒绝缺少参数的调用，避免 Probe 误用生产工作区或默认 Provider。
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "用法：MemoryConsolidationProbe <workspace> <model> <base-url>"
            );
        }

        Path workspace = Path.of(args[0])
                .toRealPath();
        String model = args[1];
        String baseUrl = args[2];

        // 2. 复用 Quillith 已支持的 API Key 优先级，密钥只从进程环境读取。
        String apiKey = System.getenv("QUILLITH_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("ANTHROPIC_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少 QUILLITH_API_KEY、ANTHROPIC_API_KEY 或 DASHSCOPE_API_KEY"
            );
        }

        // 3. 打印网络目标和非秘密配置，使两次 A/B 运行可以直接比较。
        String resolvedAddresses = Arrays.stream(
                        InetAddress.getAllByName(
                                java.net.URI.create(baseUrl)
                                        .getHost()
                        )
                )
                .map(InetAddress::getHostAddress)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        System.out.println("PROBE_DNS addresses=" + resolvedAddresses);
        System.out.println("PROBE_CONFIG model=" + model + " max_retries=0");

        // 4. 用复制工作区装配真实 MemoryRepository，原始 Job 证据不会被修改。
        SessionState sessionState = new SessionState(
                true,
                ToolApprovalMode.BYPASS,
                workspace,
                workspace,
                List.of(workspace),
                null
        );
        MemoryRepository repository = new MemoryRepository(
                new WorkspacePathResolver(sessionState)
        );
        int beforeCount = repository.list().size();
        System.out.println("PROBE_MEMORY before_count=" + beforeCount);

        /*
         * 当前生产阈值是 10。数量不足时不会发请求，不能作为网络复现证据。
         */
        if (beforeCount < 10) {
            throw new IllegalStateException(
                    "Memory 数量未达到 consolidation 阈值：" + beforeCount
            );
        }

        // 5. 关闭 SDK 自动重试，只观察一次底层请求的真实耗时和异常。
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .maxRetries(0)
                .build();
        long startedAt = System.nanoTime();

        try {
            List<MemoryEntry> consolidated = new MemoryConsolidator(
                    client,
                    model,
                    repository
            ).consolidateIfNeeded();
            double elapsedSeconds = (
                    System.nanoTime() - startedAt
            ) / 1_000_000_000.0d;

            System.out.printf(
                    Locale.ROOT,
                    "PROBE_SUCCESS elapsed_seconds=%.3f returned_count=%d stored_count=%d%n",
                    elapsedSeconds,
                    consolidated.size(),
                    repository.list().size()
            );
        } catch (Exception exception) {
            double elapsedSeconds = (
                    System.nanoTime() - startedAt
            ) / 1_000_000_000.0d;
            System.out.printf(
                    Locale.ROOT,
                    "PROBE_FAILURE elapsed_seconds=%.3f%n",
                    elapsedSeconds
            );

            // 6. 输出完整异常类型链，但不打印请求头、请求正文或 API Key。
            Throwable current = exception;
            int causeIndex = 0;
            while (current != null) {
                System.out.println(
                        "PROBE_CAUSE index="
                                + causeIndex
                                + " type="
                                + current.getClass().getName()
                                + " message="
                                + current.getMessage()
                );
                current = current.getCause();
                causeIndex++;
            }
            throw exception;
        } finally {
            client.close();
        }
    }
}
