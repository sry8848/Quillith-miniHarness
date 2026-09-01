package dev.learn.agent.manual;

import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CLI Session 状态的所有权、可变性和多根目录集合语义。
 */
class AgentStateTest {

    /**
     * 验证状态保存所有字段，并且 allowedRoots 不受调用方集合修改影响。
     */
    @Test
    void storesSessionStateAndCopiesAllowedRoots() {
        Path agentHome = Path.of("agent-home");
        Path workspace = Path.of("workspace");
        Path firstRoot = Path.of("first-root");
        Path secondRoot = Path.of("second-root");
        List<Path> configuredRoots =
                new ArrayList<>(
                        List.of(firstRoot)
                );

        AgentState state =
                new AgentState(
                        true,
                        ToolApprovalMode.ASK,
                        agentHome,
                        workspace,
                        configuredRoots,
                        Path.of("git-root")
                );

        configuredRoots.add(secondRoot);

        assertTrue(state.memoryEnabled());
        assertEquals(ToolApprovalMode.ASK, state.approvalMode());
        assertEquals(agentHome, state.agentHome());
        assertEquals(workspace, state.workspace());
        assertEquals(List.of(firstRoot), state.allowedRoots());
        assertEquals(Path.of("git-root"), state.gitRoot());
        assertThrows(
                UnsupportedOperationException.class,
                () -> state.allowedRoots().add(secondRoot)
        );
    }

    /**
     * 验证可变字段可以独立修改，不限制 allowedRoots 的元素数量。
     */
    @Test
    void updatesOnlyRuntimeFlagsAndAcceptsMultipleRoots() {
        Path firstRoot = Path.of("first-root");
        Path secondRoot = Path.of("second-root");
        AgentState state =
                new AgentState(
                        true,
                        ToolApprovalMode.ASK,
                        Path.of("agent-home"),
                        Path.of("workspace"),
                        List.of(firstRoot, secondRoot),
                        null
                );

        state.setMemoryEnabled(false);
        state.setApprovalMode(ToolApprovalMode.BYPASS);

        assertFalse(state.memoryEnabled());
        assertEquals(ToolApprovalMode.BYPASS, state.approvalMode());
        assertEquals(
                List.of(firstRoot, secondRoot),
                state.allowedRoots()
        );
        assertEquals(
                Path.of("workspace"),
                state.workspace()
        );
    }
}
