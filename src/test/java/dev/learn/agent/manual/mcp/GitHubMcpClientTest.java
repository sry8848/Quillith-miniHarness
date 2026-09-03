package dev.learn.agent.manual.mcp;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 GitHub MCP 只识别明确的瞬时传输失败。
 */
class GitHubMcpClientTest {

    /** 连接超时根因允许只读 GitHub MCP 调用重试。 */
    @Test
    void recognizesTimeoutCauseAsRetryable() {
        assertTrue(
                GitHubMcpClient.isRetryableTransportFailure(
                        new IllegalStateException(
                                "MCP call failed",
                                new HttpTimeoutException(
                                        "timeout"
                                )
                        )
                )
        );
    }

    /** 连接失败根因允许只读 GitHub MCP 调用重试。 */
    @Test
    void recognizesConnectionFailureAsRetryable() {
        assertTrue(
                GitHubMcpClient.isRetryableTransportFailure(
                        new IllegalStateException(
                                "MCP call failed",
                                new ConnectException(
                                        "connection refused"
                                )
                        )
                )
        );
    }

    /** 没有明确瞬时根因的异常保持不可重试。 */
    @Test
    void keepsUnknownTransportFailureNonRetryable() {
        assertFalse(
                GitHubMcpClient.isRetryableTransportFailure(
                        new IllegalStateException(
                                "MCP call failed"
                        )
                )
        );
    }
}
