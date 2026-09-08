package dev.learn.agent.manual.tool.tools;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.*;
import dev.learn.agent.manual.SessionState;
import dev.learn.agent.manual.session.SessionStore;
import dev.learn.agent.manual.tool.NonRetryableToolException;
import dev.learn.agent.manual.tool.approval.ToolApprovalMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 Store 的闭区间、工作区边界及工具 JSON 输出。 */
class GetSessionMessagesToolTest {
    @TempDir Path workspace;

    @Test void readsCanonicalProtocolAndRejectsInvalidInputs() throws Exception {
        var json = ObjectMappers.jsonMapper();
        var state = new SessionState(false, ToolApprovalMode.BYPASS, workspace, workspace, List.of(workspace), null);
        try (var store = new SessionStore(workspace)) {
            var user = MessageParam.builder().role(MessageParam.Role.USER).content("first").build();
            var assistant = json.readValue("""
                    {"role":"assistant","content":[{"type":"tool_use","id":"tool-1","name":"read_file","input":{"path":"a"}}]}
                    """, MessageParam.class);
            var result = json.readValue("""
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-1","content":"原始结果"}]}
                    """, MessageParam.class);
            store.createSessionWithFirstMessage("s", workspace.toString(), 0, user);
            store.appendCommitted("s", 0, List.of(assistant, result));
            store.appendCommitted("s", 1, List.of(user));
            var tool = new GetSessionMessagesTool(store, state);
            var input = json.readTree("{\"session_id\":\"s\",\"from_seq\":1,\"to_seq\":2}");
            var output = json.readTree(tool.execute(input).content());
            assertEquals(2, output.size());
            assertEquals(1, output.get(0).get("seq").asInt());
            assertEquals(0, output.get(1).get("turn_seq").asInt());
            assertEquals(assistant, json.treeToValue(output.get(0).get("message"), MessageParam.class));
            assertEquals(result, json.treeToValue(output.get(1).get("message"), MessageParam.class));
            assertEquals(1, store.listSessionMessages("s", workspace.toString()).getLast().turnSeq());
            assertEquals(0, store.getSessionMessages("s", workspace.toString(), 0, 0).getFirst().seq());
            assertThrows(IllegalArgumentException.class, () -> store.getSessionMessages("s", "another-workspace", 0, 1));
            assertThrows(IllegalArgumentException.class, () -> store.getSessionMessages("s", workspace.toString(), 2, 1));
            assertThrows(NonRetryableToolException.class, () -> tool.execute(
                    json.readTree("{\"session_id\":\"s\",\"from_seq\":0.5,\"to_seq\":2}")));
        }
    }
}
