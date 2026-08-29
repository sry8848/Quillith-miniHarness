// 声明工具审批 Gate 测试所属的包。
package dev.learn.agent.manual.tool.approval;

// 引入工具调用和测试注解。
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.learn.agent.manual.tool.ToolCall;
import org.junit.jupiter.api.Test;

// 引入测试使用的输入、并发计数和断言类型。
import java.io.StringReader;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证审批 Gate 只组合 Policy、Mode 和用户询问，不执行工具。
 */
class ToolApprovalGateTest {

    /**
     * Given Policy 返回 NOT_REQUIRED，when Mode 为 ASK，then 直接放行且不读取 Scanner。
     */
    @Test
    void askModeDoesNotPromptForNotRequiredCall() {
        try (Scanner scanner =
                     new Scanner(
                             new StringReader("")
                     )) {
            ToolApprovalGate gate =
                    new ToolApprovalGate(
                            toolCall ->
                                    ToolApprovalRequirement.NOT_REQUIRED,
                            ToolApprovalMode.ASK,
                            scanner
                    );

            assertTrue(
                    gate.approve(
                            toolCall("read_file")
                    )
            );
        }
    }

    /**
     * Given Policy 返回 REQUIRED，when ASK 模式输入 y 或 yes，then 允许工具继续执行。
     */
    @Test
    void askModeAllowsExplicitApproval() {
        try (Scanner yesScanner =
                     new Scanner(
                             new StringReader("y\n")
                     );
             Scanner fullWordScanner =
                     new Scanner(
                             new StringReader("yes\n")
                     )) {
            ToolApprovalGate yesGate =
                    requiredAskGate(
                            yesScanner
                    );
            ToolApprovalGate fullWordGate =
                    requiredAskGate(
                            fullWordScanner
                    );

            assertTrue(
                    yesGate.approve(
                            toolCall("bash")
                    )
            );
            assertTrue(
                    fullWordGate.approve(
                            toolCall("bash")
                    )
            );
        }
    }

    /**
     * Given Policy 返回 REQUIRED，when ASK 模式输入非 y，then 阻止本次工具调用。
     */
    @Test
    void askModeRejectsNonApprovalInput() {
        try (Scanner scanner =
                     new Scanner(
                             new StringReader("n\n")
                     )) {
            ToolApprovalGate gate =
                    requiredAskGate(
                            scanner
                    );

            assertFalse(
                    gate.approve(
                            toolCall("bash")
                    )
            );
        }
    }

    /**
     * Given Policy 返回 REQUIRED，when Mode 为 BYPASS，then 不读取 Scanner 也直接放行。
     */
    @Test
    void bypassModeAllowsRequiredCallWithoutScanner() {
        ToolApprovalGate gate =
                new ToolApprovalGate(
                        toolCall ->
                                ToolApprovalRequirement.REQUIRED,
                        ToolApprovalMode.BYPASS,
                        null
                );

        assertTrue(
                gate.approve(
                        toolCall("mcp__git__git_status")
                )
        );
    }

    /**
     * Given BYPASS 模式，when 审批 Gate 处理 REQUIRED，then Policy 仍然被调用一次。
     */
    @Test
    void bypassModeDoesNotChangePolicyResponsibility() {
        AtomicInteger policyCalls =
                new AtomicInteger();
        ToolApprovalGate gate =
                new ToolApprovalGate(
                        toolCall -> {
                            policyCalls.incrementAndGet();
                            return ToolApprovalRequirement.REQUIRED;
                        },
                        ToolApprovalMode.BYPASS,
                        null
                );

        assertTrue(
                gate.approve(
                        toolCall("bash")
                )
        );
        assertEquals(
                1,
                policyCalls.get()
        );
    }

    /**
     * Given ASK 模式缺少 Scanner 或基础依赖，when 创建 Gate，then 立即报告装配错误。
     */
    @Test
    void validatesModeDependenciesAtConstruction() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new ToolApprovalGate(
                                toolCall ->
                                        ToolApprovalRequirement.NOT_REQUIRED,
                                ToolApprovalMode.ASK,
                                null
                        )
        );
        assertThrows(
                NullPointerException.class,
                () ->
                        new ToolApprovalGate(
                                null,
                                ToolApprovalMode.BYPASS,
                                null
                        )
        );
        assertThrows(
                NullPointerException.class,
                () ->
                        new ToolApprovalGate(
                                toolCall ->
                                        ToolApprovalRequirement.NOT_REQUIRED,
                                null,
                                null
                        )
        );
    }

    /** 创建一个固定返回 REQUIRED 的 ASK Gate。 */
    private static ToolApprovalGate requiredAskGate(
            Scanner scanner
    ) {
        return new ToolApprovalGate(
                toolCall ->
                        ToolApprovalRequirement.REQUIRED,
                ToolApprovalMode.ASK,
                scanner
        );
    }

    /** 创建不带参数的工具调用。 */
    private static ToolCall toolCall(
            String name
    ) {
        return new ToolCall(
                name + "-id",
                name,
                JsonNodeFactory.instance.objectNode()
        );
    }
}
