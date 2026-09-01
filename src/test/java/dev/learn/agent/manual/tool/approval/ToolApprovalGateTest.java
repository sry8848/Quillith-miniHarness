// 声明工具审批 Gate 测试所属的包。
package dev.learn.agent.manual.tool.approval;

// 引入工具调用和测试注解。
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.learn.agent.manual.AgentState;
import dev.learn.agent.manual.tool.ToolCall;
import org.junit.jupiter.api.Test;

// 引入测试使用的输入、并发计数和断言类型。
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

// 引入当前测试使用的断言。
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证审批 Gate 只组合 Policy、AgentState 和用户询问，不执行工具。
 */
class ToolApprovalGateTest {

    /**
     * Given Policy 返回 NOT_REQUIRED，when AgentState 为 ASK，then 直接放行且不读取 Scanner。
     */
    @Test
    void askModeDoesNotPromptForNotRequiredCall() {
        try (Scanner scanner =
                     new Scanner(
                             new StringReader("")
                     )) {
            AgentState state =
                    state(ToolApprovalMode.ASK);
            ToolApprovalGate gate =
                    new ToolApprovalGate(
                            toolCall ->
                                    ToolApprovalRequirement.NOT_REQUIRED,
                            state,
                            scanner
                    );

            assertTrue(
                    gate.approve(
                            toolCall("read_file")
                    )
            );

            state.setApprovalMode(
                    ToolApprovalMode.BYPASS
            );
            assertTrue(
                    gate.approve(
                            toolCall("read_file")
                    )
            );
        }
    }

    /**
     * Given Policy 返回 REQUIRED，when AgentState 为 ASK 且输入 y 或 yes，then 允许工具继续执行。
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
     * Given Policy 返回 REQUIRED，when AgentState 为 ASK 且输入非 y，then 阻止本次工具调用。
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
     * Given Policy 返回 REQUIRED，when AgentState 为 BYPASS，then 不读取 Scanner 也直接放行。
     */
    @Test
    void bypassModeAllowsRequiredCallWithoutScanner() {
        ToolApprovalGate gate =
                new ToolApprovalGate(
                        toolCall ->
                                ToolApprovalRequirement.REQUIRED,
                        state(ToolApprovalMode.BYPASS),
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
                        state(ToolApprovalMode.BYPASS),
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
     * Given ASK 状态缺少 Scanner 或基础依赖，when 创建 Gate，then 立即报告装配错误。
     */
    @Test
    void validatesModeDependenciesAtConstruction() {
        assertThrows(
                NullPointerException.class,
                () ->
                new ToolApprovalGate(
                        toolCall ->
                                ToolApprovalRequirement.NOT_REQUIRED,
                                state(ToolApprovalMode.ASK),
                                null
                        )
        );
        assertThrows(
                NullPointerException.class,
                () ->
                new ToolApprovalGate(
                        null,
                                state(ToolApprovalMode.BYPASS),
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
                state(ToolApprovalMode.ASK),
                scanner
        );
    }

    /**
     * Given 同一个 Gate，when AgentState 在运行中切换模式，then 后续调用读取新模式。
     */
    @Test
    void readsApprovalModeFromSharedStateForEveryCall() {
        try (Scanner scanner =
                     new Scanner(
                             new StringReader("n\ny\n")
                     )) {
            AgentState state =
                    state(ToolApprovalMode.ASK);
            ToolApprovalGate gate =
                    new ToolApprovalGate(
                            toolCall ->
                                    ToolApprovalRequirement.REQUIRED,
                            state,
                            scanner
                    );

            assertFalse(
                    gate.approve(
                            toolCall("bash")
                    )
            );

            state.setApprovalMode(
                    ToolApprovalMode.BYPASS
            );
            assertTrue(
                    gate.approve(
                            toolCall("bash")
                    )
            );

            state.setApprovalMode(
                    ToolApprovalMode.ASK
            );
            assertTrue(
                    gate.approve(
                            toolCall("bash")
                    )
            );
        }
    }

    /**
     * Given BYPASS Gate 没有 Scanner，when 状态切换到 ASK，then 明确报告交互输入缺失。
     */
    @Test
    void reportsMissingScannerWhenStateChangesToAsk() {
        AgentState state =
                state(ToolApprovalMode.BYPASS);
        ToolApprovalGate gate =
                new ToolApprovalGate(
                        toolCall ->
                                ToolApprovalRequirement.REQUIRED,
                        state,
                        null
                );

        state.setApprovalMode(
                ToolApprovalMode.ASK
        );

        assertThrows(
                IllegalStateException.class,
                () -> gate.approve(toolCall("bash"))
        );
    }

    /** 创建测试用 Session 状态；路径值在 Gate 测试中不参与判断。 */
    private static AgentState state(
            ToolApprovalMode mode
    ) {
        Path workspace =
                Path.of("workspace");
        return new AgentState(
                false,
                mode,
                Path.of("agent-home"),
                workspace,
                List.of(workspace),
                null
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
