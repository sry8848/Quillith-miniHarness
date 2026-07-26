package dev.learn.agent.manual.hook.hooks;

import dev.learn.agent.manual.hook.AgentHook;
import dev.learn.agent.manual.hook.HookEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 用户提交消息时，记录 Agent 当前所在的工作区。
 */
public final class WorkspaceLoggingHook implements AgentHook {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    WorkspaceLoggingHook.class
            );

    private final Path workspace;

    public WorkspaceLoggingHook(Path workspace) {
        this.workspace =
                Objects.requireNonNull(
                                workspace,
                                "Workspace 不能为空"
                        )
                        .toAbsolutePath()
                        .normalize();
    }

    /**
     * 这里只记录上下文，不修改用户消息。
     */
    @Override
    public HookEffect onUserPromptSubmit(
            String userPrompt
    ) {
        LOGGER.debug(
                "UserPromptSubmit：当前工作区为 {}",
                workspace
        );

        return HookEffect.proceed();
    }
}
