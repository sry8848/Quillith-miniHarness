import base64
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock


# 1. 测试只将当前 manual-agent 根目录加入导入路径。
# 这保证加载的是本次修改的 Adapter，不会偷换 Harbor 安装来源。
PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT))

from harbor_adapter import QuillithHarborAgent


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
        agent.exec_as_agent = AsyncMock()
        instruction = "第一行\nsecond line\n最后一行"

        # 1. Adapter 只编码边界，不改写 instruction 内容。
        await agent._submit_turn(object(), instruction)

        request = agent.exec_as_agent.await_args.kwargs["env"][
            "QUILLITH_HARBOR_REQUEST"
        ]
        self.assertTrue(request.startswith("TURN "))
        decoded = base64.b64decode(request.removeprefix("TURN ")).decode("utf-8")
        self.assertEqual(instruction, decoded)


if __name__ == "__main__":
    unittest.main()
