package dev.learn.agent.manual;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.manual.skill.SkillRegistry;
import dev.learn.agent.manual.tool.ToolExecutionResult;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import dev.learn.agent.manual.tool.tools.LoadSkillTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Agent 自身的 Skill 和终端 transcript 从 agentHome 派生。
 */
class AgentHomeResourcePathTest {

    @TempDir
    Path temporaryRoot;

    /** agentHome 的 Skill 可以加载，workspace 中独有的 Skill 不进入注册表。 */
    @Test
    void loadsSkillsOnlyFromAgentHome()
            throws IOException {
        Path workspace =
                Files.createDirectories(
                        temporaryRoot.resolve("workspace")
                );
        Path agentHome =
                Files.createDirectories(
                        temporaryRoot.resolve("agent-home")
                );
        createSkill(
                agentHome,
                "agent-skill",
                "Agent home instructions."
        );
        createSkill(
                workspace,
                "workspace-skill",
                "Workspace instructions."
        );
        SessionState sessionState =
                state(
                        agentHome,
                        workspace
                );

        SkillRegistry registry =
                new SkillRegistry(
                        sessionState.agentHome()
                                .resolve("skills")
                );
        LoadSkillTool loadSkillTool =
                new LoadSkillTool(
                        registry
                );

        ToolExecutionResult agentSkill =
                loadSkillTool.execute(
                        skillInput("agent-skill")
                );
        ToolExecutionResult workspaceSkill =
                loadSkillTool.execute(
                        skillInput("workspace-skill")
                );

        assertFalse(
                agentSkill.error(),
                agentSkill.content()
        );
        assertTrue(
                agentSkill.content()
                        .contains("Agent home instructions.")
        );
        assertTrue(workspaceSkill.error());
    }

    /** 空 Skill 目录表示正常的零技能状态。 */
    @Test
    void emptySkillsDirectoryHasNoRegisteredSkills()
            throws IOException {
        Path agentHome =
                Files.createDirectories(
                        temporaryRoot.resolve("agent-home")
                );
        Files.createDirectories(
                agentHome.resolve("skills")
        );

        SkillRegistry registry =
                new SkillRegistry(
                        agentHome.resolve("skills")
                );

        assertTrue(
                registry.isEmpty()
        );
    }

    /** Interactive transcript 目录只在 agentHome 下创建。 */
    @Test
    void createsInteractiveTranscriptDirectoryUnderAgentHome()
            throws IOException {
        Path workspace =
                Files.createDirectories(
                        temporaryRoot.resolve("workspace")
                );
        Path agentHome =
                Files.createDirectories(
                        temporaryRoot.resolve("agent-home")
                );
        SessionState sessionState =
                state(
                        agentHome,
                        workspace
                );

        Path transcriptDirectory =
                ManualAgentApplication
                        .createTerminalTranscriptDirectory(
                                sessionState
                        );

        assertEquals(
                agentHome.resolve(".task_outputs")
                        .resolve("transcripts"),
                transcriptDirectory
        );
        assertTrue(
                Files.isDirectory(
                        transcriptDirectory
                )
        );
        assertFalse(
                Files.exists(
                        workspace.resolve(".task_outputs")
                                .resolve("transcripts")
                )
        );
        assertEquals(
                List.of(workspace),
                sessionState.allowedRoots()
        );
    }

    /** 创建保持 workspace-only allowedRoots 的测试 Session。 */
    private static SessionState state(
            Path agentHome,
            Path workspace
    ) {
        return new SessionState(
                true,
                ToolApprovalMode.ASK,
                agentHome,
                workspace,
                List.of(workspace),
                null
        );
    }

    /** 创建一个保持现有格式的 SKILL.md。 */
    private static void createSkill(
            Path root,
            String name,
            String instructions
    ) throws IOException {
        Path skillDirectory =
                Files.createDirectories(
                        root.resolve("skills")
                                .resolve(name)
                );
        Files.writeString(
                skillDirectory.resolve("SKILL.md"),
                """
                        ---
                        name: %s
                        description: Test skill %s
                        ---
                        %s
                        """.formatted(
                        name,
                        name,
                        instructions
                )
        );
    }

    /** 创建 load_skill 的名称输入。 */
    private static ObjectNode skillInput(
            String name
    ) {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();
        input.put(
                "name",
                name
        );
        return input;
    }
}
