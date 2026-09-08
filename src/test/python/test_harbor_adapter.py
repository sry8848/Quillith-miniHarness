import base64
import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, call


# 1. 测试只将当前 manual-agent 根目录加入导入路径。
# 这保证加载的是本次修改的 Adapter，不会偷换 Harbor 安装来源。
PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT))

from harbor_adapter import QuillithHarborAgent, QuillithMemoryAgent


class QuillithHarborAgentTest(unittest.IsolatedAsyncioTestCase):
    """验证最小多轮版本的 Session 分支和 instruction 传输。"""

    def create_agent(self, *, started: bool, resume: bool):
        """创建不触发 Harbor 安装和凭据校验的 Adapter 测试对象。"""
        # 1. 这些测试只关心 run() 状态语义，不重复测试基类初始化。
        agent = object.__new__(QuillithHarborAgent)
        agent._session_started = started
        agent._resume = resume
        agent._start_session = AsyncMock()
        agent._submit_turn = AsyncMock()
        return agent

    async def test_install_uploads_quillith_and_otel_agents(self):
        """验证 Trial 安装阶段上传并检查两个运行 JAR。"""
        agent = object.__new__(QuillithHarborAgent)
        agent._jar_path = Path("C:/artifacts/quillith.jar")
        agent._otel_agent_path = Path("C:/tools/opentelemetry-javaagent.jar")
        agent._bash_executable = "/bin/bash"
        agent.exec_as_agent = AsyncMock()
        environment = SimpleNamespace(upload_file=AsyncMock())

        # 1. 安装阶段必须先验证环境，再上传两个宿主机制品并检查可读性。
        await agent.install(environment)

        self.assertEqual(
            [
                call(agent._jar_path, agent._REMOTE_JAR_PATH),
                call(agent._otel_agent_path, agent._REMOTE_OTEL_AGENT_PATH),
            ],
            environment.upload_file.await_args_list,
        )
        verification_command = agent.exec_as_agent.await_args_list[-1].kwargs[
            "command"
        ]
        self.assertIn(agent._REMOTE_JAR_PATH, verification_command)
        self.assertIn(agent._REMOTE_OTEL_AGENT_PATH, verification_command)

    async def test_start_session_enables_structured_trace_logging(self):
        """验证 Harbor JVM 附加 Java Agent 并启用结构化 Trace。"""
        agent = object.__new__(QuillithHarborAgent)
        agent._bash_executable = "/bin/bash"
        agent._github_token = "test-token"
        agent._quillith_model = None
        agent.model_name = None
        agent._resolve_env = lambda *_: None
        agent.exec_as_agent = AsyncMock()
        environment = SimpleNamespace(
            exec=AsyncMock(
                return_value=SimpleNamespace(
                    return_code=0,
                    stdout="/app\n",
                )
            )
        )

        # 1. 启动命令必须让 Trace 在业务请求前已经生效。
        await agent._start_session(environment)

        start_call = agent.exec_as_agent.await_args
        self.assertIn(
            f"-javaagent:{agent._REMOTE_OTEL_AGENT_PATH}",
            start_call.kwargs["command"],
        )
        self.assertEqual(
            "logging-otlp",
            start_call.kwargs["env"]["OTEL_TRACES_EXPORTER"],
        )
        self.assertEqual(
            "quillith-harbor",
            start_call.kwargs["env"]["OTEL_SERVICE_NAME"],
        )
        self.assertEqual(
            "100",
            start_call.kwargs["env"]["OTEL_BSP_SCHEDULE_DELAY"],
        )
        self.assertEqual(
            "false",
            start_call.kwargs["env"]["OTEL_INSTRUMENTATION_JDBC_ENABLED"],
        )

    async def test_first_run_starts_session_then_submits_turn(self):
        """验证第一次普通 run 只启动一次 Session。"""
        agent = self.create_agent(started=False, resume=False)
        environment = object()
        context = object()

        # 1. 首轮先启动常驻进程，再提交当前 instruction。
        await agent.run("turn 1", environment, context)

        agent._start_session.assert_awaited_once_with(environment)
        agent._submit_turn.assert_awaited_once_with(environment, "turn 1")
        self.assertTrue(agent._session_started)

    async def test_resume_reuses_started_session(self):
        """验证 resume Turn 不再启动 Quillith。"""
        agent = self.create_agent(started=True, resume=True)
        environment = object()

        # 1. resume 直接将新 instruction 交给原 Session。
        await agent.run("turn 2", environment, object())

        agent._start_session.assert_not_awaited()
        agent._submit_turn.assert_awaited_once_with(environment, "turn 2")

    async def test_resume_before_first_run_fails(self):
        """验证没有可复用 Session 时 resume 直接失败。"""
        agent = self.create_agent(started=False, resume=True)

        # 1. 不将错误 resume 降级成新 Session，避免伪造多轮成功。
        with self.assertRaisesRegex(RuntimeError, "尚未启动会话"):
            await agent.run("turn 2", object(), object())

        agent._start_session.assert_not_awaited()
        agent._submit_turn.assert_not_awaited()

    async def test_second_non_resume_run_fails(self):
        """验证已有 Session 不接受第二次普通 run。"""
        agent = self.create_agent(started=True, resume=False)

        # 1. 第二次普通 run 不隐式续接历史。
        with self.assertRaisesRegex(RuntimeError, "第二次非 resume run"):
            await agent.run("new session", object(), object())

        agent._start_session.assert_not_awaited()
        agent._submit_turn.assert_not_awaited()

    async def test_submit_turn_encodes_multiline_utf8_instruction(self):
        """验证多行 UTF-8 instruction 以单行 TURN 消息传输。"""
        agent = object.__new__(QuillithHarborAgent)
        agent._exchange = AsyncMock(return_value="OK")
        instruction = "第一行\nsecond line\n最后一行"

        # 1. Adapter 只编码边界，不改写 instruction 内容。
        await agent._submit_turn(object(), instruction)

        request = agent._exchange.await_args.args[1]
        self.assertTrue(request.startswith("TURN "))
        decoded = base64.b64decode(request.removeprefix("TURN ")).decode("utf-8")
        self.assertEqual(instruction, decoded)

    async def test_recorded_turn_encodes_user_and_assistant_separately(self):
        """验证固定历史的两个角色拥有独立协议字段。"""
        agent = object.__new__(QuillithHarborAgent)
        agent._exchange = AsyncMock(return_value="OK")

        # 1. 多行 Unicode 文本不能在 FIFO 边界互相混合。
        await agent._submit_recorded_turn(
            object(),
            "用户第一行\n用户第二行",
            "助手第一行\n助手第二行",
        )

        request = agent._exchange.await_args.args[1]
        encoded_user, encoded_assistant = request.removeprefix(
            "RECORDED_TURN "
        ).split(" ")
        self.assertEqual(
            "用户第一行\n用户第二行",
            base64.b64decode(encoded_user).decode("utf-8"),
        )
        self.assertEqual(
            "助手第一行\n助手第二行",
            base64.b64decode(encoded_assistant).decode("utf-8"),
        )

    async def test_answer_decodes_result(self):
        """验证最终答案从 RESULT 响应无损返回。"""
        agent = object.__new__(QuillithHarborAgent)
        answer = "最终答案第一行\n第二行"
        encoded_answer = base64.b64encode(answer.encode("utf-8")).decode("ascii")
        agent._exchange = AsyncMock(return_value=f"RESULT {encoded_answer}")

        # 1. ANSWER 只增加返回值，不改变问题文本。
        output = await agent._answer(object(), "最终问题")

        self.assertEqual(answer, output)
        request = agent._exchange.await_args.args[1]
        encoded_question = request.removeprefix("ANSWER ")
        self.assertEqual(
            "最终问题",
            base64.b64decode(encoded_question).decode("utf-8"),
        )

    async def test_exchange_reports_exited_java_process(self):
        """验证 Java 异常退出会立即成为 Adapter 错误。"""
        agent = object.__new__(QuillithHarborAgent)
        agent.exec_as_agent = AsyncMock(
            return_value=SimpleNamespace(
                stdout="PROCESS_EXIT 1",
            )
        )

        # 1. 进程退出状态不能作为普通协议响应交给上层继续处理。
        with self.assertRaisesRegex(RuntimeError, "进程已退出"):
            await agent._exchange(object(), "TURN ZmFjdA==")

        # 正常请求仍按原协议关闭 writer，不能让 Java 下一轮读到 EOF。
        command = agent.exec_as_agent.await_args.kwargs["command"]
        self.assertIn(QuillithHarborAgent._REQUEST_PIPE, command)
        self.assertNotIn("exec 3<>", command)

    async def test_cross_session_case_replays_in_confirmed_order(self):
        """验证每个官方 Session 和最终问题之间都建立新 Session。"""
        agent = object.__new__(QuillithMemoryAgent)
        agent._resume = False
        agent._session_started = False
        agent._start_session = AsyncMock()
        agent._submit_recorded_turn = AsyncMock()
        agent._new_session = AsyncMock()
        agent._answer = AsyncMock(return_value="answer")
        agent._save_answer = AsyncMock()
        environment = object()
        case = {
            "case_id": "official/case-1",
            "memory_scope": "cross_session",
            "sessions": [
                {
                    "messages": [
                        {"role": "user", "content": "u1"},
                        {"role": "assistant", "content": "a1"},
                    ]
                },
                {
                    "messages": [
                        {"role": "user", "content": "u2"},
                        {"role": "assistant", "content": "a2"},
                    ]
                },
            ],
            "question": "question",
        }

        # 1. 第二个官方 Session 前切一次，最终问题前再切一次。
        await agent.run(json.dumps(case), environment, object())

        agent._start_session.assert_awaited_once_with(environment)
        self.assertEqual(2, agent._new_session.await_count)
        self.assertEqual(
            [
                call(environment, "u1", "a1"),
                call(environment, "u2", "a2"),
            ],
            agent._submit_recorded_turn.await_args_list,
        )
        agent._answer.assert_awaited_once_with(environment, "question")
        agent._save_answer.assert_awaited_once_with(
            environment,
            "official/case-1",
            "answer",
        )

    async def test_single_session_case_answers_without_starting_new_session(self):
        """验证单对话历史与最终问题留在同一个 Session。"""
        agent = object.__new__(QuillithMemoryAgent)
        agent._resume = False
        agent._session_started = False
        agent._start_session = AsyncMock()
        agent._submit_recorded_turn = AsyncMock()
        agent._new_session = AsyncMock()
        agent._answer = AsyncMock(return_value="answer")
        agent._save_answer = AsyncMock()
        environment = object()
        case = {
            "case_id": "official/case-2",
            "memory_scope": "single_session",
            "sessions": [
                {
                    "messages": [
                        {"role": "user", "content": "u1"},
                        {"role": "assistant", "content": "a1"},
                    ]
                }
            ],
            "question": "question",
        }

        # 1. 固定历史回放完成后直接在当前 Session 提交最终问题。
        await agent.run(json.dumps(case), environment, object())

        agent._start_session.assert_awaited_once_with(environment)
        agent._new_session.assert_not_awaited()
        agent._submit_recorded_turn.assert_awaited_once_with(
            environment,
            "u1",
            "a1",
        )
        agent._answer.assert_awaited_once_with(environment, "question")
        agent._save_answer.assert_awaited_once_with(
            environment,
            "official/case-2",
            "answer",
        )


if __name__ == "__main__":
    unittest.main()
