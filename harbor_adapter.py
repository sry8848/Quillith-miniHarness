import base64
import json
import shlex
from pathlib import Path
from typing import override

from harbor.agents.installed.base import BaseInstalledAgent
from harbor.agents.model_connection import ModelConnectionSpec
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext


class QuillithHarborAgent(BaseInstalledAgent):
    """在 Harbor Trial 中运行宿主机预构建的 Quillith JAR。"""

    SUPPORTS_ATIF = False
    SUPPORTS_RESUME = True
    MODEL_CONNECTION = ModelConnectionSpec(
        api_key_envs=(
            "QUILLITH_API_KEY",
            "ANTHROPIC_API_KEY",
            "DASHSCOPE_API_KEY",
        ),
        base_url_envs=(
            "QUILLITH_BASE_URL",
            "ANTHROPIC_BASE_URL",
        ),
    )

    _REMOTE_JAR_PATH = "/installed-agent/quillith.jar"
    _REMOTE_OTEL_AGENT_PATH = "/installed-agent/opentelemetry-javaagent.jar"
    _SESSION_DIRECTORY = "/tmp/quillith-harbor-session"
    _REQUEST_PIPE = f"{_SESSION_DIRECTORY}/request.pipe"
    _RESPONSE_PIPE = f"{_SESSION_DIRECTORY}/response.pipe"

    def __init__(
        self,
        logs_dir: Path,
        jar_path: str,
        otel_agent_path: str,
        *args,
        **kwargs,
    ):
        """保存当前 Harbor Job 使用的 Quillith 和 OpenTelemetry JAR。"""
        super().__init__(logs_dir, *args, **kwargs)

        # 1. JAR 必须在创建 Trial 前由宿主机 launcher 构建完成。
        self._jar_path = Path(jar_path).expanduser().resolve()
        if not self._jar_path.is_file():
            raise FileNotFoundError(
                f"Quillith JAR 不存在：{self._jar_path}"
            )

        # 2. Trace Agent 也由宿主机显式提供，Trial 内不下载运行依赖。
        self._otel_agent_path = Path(otel_agent_path).expanduser().resolve()
        if not self._otel_agent_path.is_file():
            raise FileNotFoundError(
                f"OpenTelemetry Java Agent 不存在：{self._otel_agent_path}"
            )

        # 3. 带 provider 前缀时只接受 Anthropic-compatible 协议。
        self._quillith_model: str | None = None
        if self.model_name:
            if "/" in self.model_name:
                provider, model = self.model_name.split("/", 1)
                if provider != "anthropic":
                    raise ValueError(
                        f"Quillith 不支持模型协议：{provider}"
                    )
                if not model:
                    raise ValueError("Harbor model 缺少模型名称")
                self._quillith_model = model
            else:
                self._quillith_model = self.model_name

        # 4. GitHub Token 保持 Quillith 当前必需语义，并在 Trial 启动前失败。
        self._github_token = self._get_env(
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        )
        if not self._github_token:
            raise ValueError("缺少 GITHUB_PERSONAL_ACCESS_TOKEN")

        # 5. Harbor Linux 默认使用 /bin/bash，显式空值属于配置错误。
        configured_bash = self._get_env(
            "QUILLITH_BASH_EXECUTABLE"
        )
        if configured_bash is not None and not configured_bash.strip():
            raise ValueError("QUILLITH_BASH_EXECUTABLE 不能为空")
        self._bash_executable = configured_bash or "/bin/bash"

        # 6. 第一版只区分当前 Trial 是否已经启动 Quillith，不扩展恢复状态机。
        self._session_started = False

    @staticmethod
    @override
    def name() -> str:
        """返回 Harbor 结果中使用的 Agent 名称。"""
        return "quillith"

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        """验证 Trial 环境并上传宿主机已经构建的 JAR。"""
        # 1. Trial 只验证预装运行依赖，不在这里安装 JDK、Maven 或源码。
        quoted_bash = shlex.quote(self._bash_executable)
        await self.exec_as_agent(
            environment,
            command=(
                "command -v java >/dev/null && "
                "command -v git >/dev/null && "
                "command -v mkfifo >/dev/null && "
                "command -v nohup >/dev/null && "
                f"test -x {quoted_bash}"
            ),
        )

        # 2. 每个 Trial 上传同一个宿主 Quillith JAR；上传不是重新构建。
        await environment.upload_file(
            self._jar_path,
            self._REMOTE_JAR_PATH,
        )

        # 3. 上传固定版本的 OpenTelemetry Java Agent，避免 Trial 内联网下载。
        await environment.upload_file(
            self._otel_agent_path,
            self._REMOTE_OTEL_AGENT_PATH,
        )

        # 4. 立即确认默认 Agent 用户可以读取两个 JAR，且 Java Runtime 可用。
        await self.exec_as_agent(
            environment,
            command=(
                f"test -r {shlex.quote(self._REMOTE_JAR_PATH)} && "
                f"test -r {shlex.quote(self._REMOTE_OTEL_AGENT_PATH)} && "
                "java -version"
            ),
        )

    @override
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        """启动或复用当前 Trial 的 Quillith，并提交一条完整 instruction。"""
        # 1. 第一次普通 run 启动会话；resume 只能复用已经启动的会话。
        if not self._session_started:
            if self._resume:
                raise RuntimeError(
                    "无法恢复 Quillith Session：当前 Trial 尚未启动会话"
                )
            await self._start_session(environment)
            self._session_started = True
        elif not self._resume:
            # 第一版没有主动关闭协议，不能把第二次普通 run 偷偷变成续聊。
            raise RuntimeError(
                "Quillith 最小多轮版本不支持同一 Trial 中的第二次非 resume run"
            )

        # 2. 一条 Harbor step 只发送一条完整 instruction，并等待正常完成信号。
        await self._submit_turn(
            environment,
            instruction,
        )

    async def _start_session(
        self,
        environment: BaseEnvironment,
    ) -> None:
        """在当前 Trial 工作目录中后台启动唯一的 Quillith Harbor 进程。"""
        # 1. 只映射 Harbor 显式模型连接；缺失字段留给 Quillith 默认值。
        connection = self.model_connection

        # 100 ms 批处理间隔降低 JVM 异常退出时丢失最后一批 Span 的概率。
        # Session JDBC 自动 Span 数量远高于业务 Span，关闭后保留已确认的业务树。
        runtime_env = {
            "QUILLITH_BASH_EXECUTABLE": self._bash_executable,
            "GITHUB_PERSONAL_ACCESS_TOKEN": self._github_token,
            "OTEL_SERVICE_NAME": "quillith-harbor",
            "OTEL_TRACES_EXPORTER": "logging-otlp",
            "OTEL_BSP_SCHEDULE_DELAY": "100",
            "OTEL_METRICS_EXPORTER": "none",
            "OTEL_LOGS_EXPORTER": "none",
            "OTEL_INSTRUMENTATION_JDBC_ENABLED": "false",
        }
        if self._quillith_model:
            runtime_env["QUILLITH_MODEL"] = self._quillith_model
        if connection.api_key:
            runtime_env["QUILLITH_API_KEY"] = connection.api_key
        if connection.configured_base_url:
            runtime_env["QUILLITH_BASE_URL"] = connection.configured_base_url

        # 2. 只在创建 Session 时确定工作目录，后续 Turn 继续使用同一 cwd。
        workdir_result = await environment.exec("pwd")
        workdir = (workdir_result.stdout or "").strip()
        if workdir_result.return_code != 0 or not workdir:
            raise RuntimeError("无法确定 Harbor Trial 工作目录")

        # 3. 创建两个最小单向 FIFO，并让 Java 脱离本次 Docker exec 持续运行。
        # 下一次写 request FIFO 会自然等待 Java 完成初始化并打开 reader，
        # 因此正常链路不需要额外 READY 文件或轮询状态。
        process_command = (
            "java "
            f"-javaagent:{shlex.quote(self._REMOTE_OTEL_AGENT_PATH)} "
            f"-jar {shlex.quote(self._REMOTE_JAR_PATH)} harbor "
            "</dev/null "
            ">>/logs/agent/quillith.stdout.log "
            "2>>/logs/agent/quillith.stderr.log; "
            "quillith_exit_code=$?; "
            f"(IFS= read -r _ <{shlex.quote(self._REQUEST_PIPE)}) & "
            "quillith_request_drain_pid=$!; "
            "printf 'PROCESS_EXIT %s\\n' \"$quillith_exit_code\" "
            f">{shlex.quote(self._RESPONSE_PIPE)}; "
            "kill \"$quillith_request_drain_pid\" 2>/dev/null || true; "
            "wait \"$quillith_request_drain_pid\" 2>/dev/null || true"
        )

        # Java 异常退出时必须唤醒正在等待的请求，避免只能等 Harbor 总超时。
        await self.exec_as_agent(
            environment,
            command=(
                "set -e; "
                f"mkdir {shlex.quote(self._SESSION_DIRECTORY)}; "
                f"mkfifo {shlex.quote(self._REQUEST_PIPE)}; "
                f"mkfifo {shlex.quote(self._RESPONSE_PIPE)}; "
                f"nohup /bin/bash -c {shlex.quote(process_command)} "
                ">/dev/null 2>&1 &"
            ),
            cwd=workdir,
            env=runtime_env,
        )

    async def _submit_turn(
        self,
        environment: BaseEnvironment,
        instruction: str,
    ) -> None:
        """向当前 Quillith 进程提交一个 Turn，并等待正常完成信号。"""
        # 1. Base64 只负责把多行 UTF-8 instruction 放进一行 FIFO 消息。
        encoded_instruction = self._encode_text(instruction)
        request = f"TURN {encoded_instruction}"

        # 2. 保持原 TURN 协议只接受 OK，避免改变普通 Harbor 任务语义。
        response = await self._exchange(environment, request)
        if response != "OK":
            raise RuntimeError("Quillith Harbor TURN 返回未知状态")

    async def _submit_recorded_turn(
        self,
        environment: BaseEnvironment,
        user_text: str,
        assistant_text: str,
    ) -> None:
        """提交一组固定 user/assistant，同时复用真实 Turn 外层生命周期。"""
        # 1. 两个独立 Base64 字段保持消息角色和文本边界。
        request = (
            f"RECORDED_TURN {self._encode_text(user_text)} "
            f"{self._encode_text(assistant_text)}"
        )
        response = await self._exchange(environment, request)
        if response != "OK":
            raise RuntimeError("Quillith Harbor 固定历史提交失败")

    async def _new_session(
        self,
        environment: BaseEnvironment,
    ) -> None:
        """清空当前活动 Session，同时保留 JVM 和工作区长期记忆。"""
        # 1. Session 切换由 Java Runtime 执行，Python 不接触内部历史或 Memory。
        response = await self._exchange(environment, "NEW_SESSION")
        if response != "OK":
            raise RuntimeError("Quillith Harbor 新 Session 创建失败")

    async def _answer(
        self,
        environment: BaseEnvironment,
        question: str,
    ) -> str:
        """提交最终真实问题并返回 Quillith 的完整文本答案。"""
        # 1. ANSWER 与普通 TURN 使用同一真实 submit，只额外返回最终文本。
        response = await self._exchange(
            environment,
            f"ANSWER {self._encode_text(question)}",
        )
        if not response.startswith("RESULT "):
            raise RuntimeError("Quillith Harbor 未返回最终答案")
        return self._decode_text(response.removeprefix("RESULT "))

    async def _exchange(
        self,
        environment: BaseEnvironment,
        request: str,
    ) -> str:
        """发送一条 FIFO 机器请求并返回 Java 的单行响应。"""
        # 1. 请求通过环境变量传入，不拼接到 Shell 命令或输出到日志。
        exchange_result = await self.exec_as_agent(
            environment,
            command=(
                "set -e; "
                "printf '%s\\n' \"$QUILLITH_HARBOR_REQUEST\" "
                f">{shlex.quote(self._REQUEST_PIPE)}; "
                "IFS= read -r quillith_status "
                f"<{shlex.quote(self._RESPONSE_PIPE)}; "
                "printf '%s' \"$quillith_status\""
            ),
            env={
                "QUILLITH_HARBOR_REQUEST": request,
            },
        )
        response = exchange_result.stdout or ""
        response = response.rstrip("\r\n")
        if response.startswith("PROCESS_EXIT "):
            raise RuntimeError(
                f"Quillith Harbor 进程已退出：{response}"
            )
        return response

    @staticmethod
    def _encode_text(text: str) -> str:
        """把 UTF-8 文本编码为单行协议字段。"""
        return base64.b64encode(text.encode("utf-8")).decode("ascii")

    @staticmethod
    def _decode_text(encoded_text: str) -> str:
        """把单行协议字段还原为 UTF-8 文本。"""
        return base64.b64decode(encoded_text, validate=True).decode("utf-8")

    @override
    def populate_context_post_run(
        self,
        context: AgentContext,
    ) -> None:
        """首版不生成 trajectory 或额外 AgentContext 指标。"""
        pass


class QuillithMemoryAgent(QuillithHarborAgent):
    """按一条对话记忆 Case 的顺序驱动 Quillith 机器协议。"""

    @override
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        """回放固定历史、切换 Session，并保存最终真实答案。"""
        # 1. Memory Case 是单步 Harbor Task，不能进入现有 resume 语义。
        if self._resume:
            raise RuntimeError("对话记忆 Case 不接受 Harbor resume")
        if self._session_started:
            raise RuntimeError("同一对话记忆 Case 不能重复执行")

        # 2. 解析并校验运行链路真正依赖的最小字段。
        case = json.loads(instruction)
        self._validate_case(case)

        # 3. 复用父类已有的 JAR、FIFO 和长驻 JVM 启动逻辑。
        await self._start_session(environment)
        self._session_started = True

        # 4. 每个官方 session 的固定 user/assistant 按原顺序逐 Turn 回放。
        for session_index, session in enumerate(case["sessions"]):
            if session_index > 0:
                await self._new_session(environment)

            messages = session["messages"]
            for message_index in range(0, len(messages), 2):
                user_message = messages[message_index]
                assistant_message = messages[message_index + 1]
                await self._submit_recorded_turn(
                    environment,
                    user_message["content"],
                    assistant_message["content"],
                )

        # 5. 跨对话最终问题必须位于不继承旧活动上下文的新 Session。
        if case["memory_scope"] == "cross_session":
            await self._new_session(environment)

        # 6. 最终问题使用真实主模型，并把纯答案交给 Harbor verifier。
        answer = await self._answer(environment, case["question"])
        await self._save_answer(
            environment,
            case["case_id"],
            answer,
        )

    @staticmethod
    def _validate_case(case: object) -> None:
        """校验首版 Memory Case 的消息顺序和 Session 边界。"""
        if not isinstance(case, dict):
            raise ValueError("Memory Case 必须是 JSON object")

        # 1. 首版只接受运行链路需要的标量和 Session 集合。
        case_id = case.get("case_id")
        memory_scope = case.get("memory_scope")
        sessions = case.get("sessions")
        question = case.get("question")
        if not isinstance(case_id, str) or not case_id.strip():
            raise ValueError("Memory Case 缺少非空 case_id")
        if memory_scope not in {"single_session", "cross_session"}:
            raise ValueError("memory_scope 必须是 single_session 或 cross_session")
        if not isinstance(sessions, list) or not sessions:
            raise ValueError("Memory Case 必须包含至少一个 session")
        if memory_scope == "single_session" and len(sessions) != 1:
            raise ValueError("single_session Case 必须恰好包含一个 session")
        if not isinstance(question, str) or not question.strip():
            raise ValueError("Memory Case 缺少非空 question")

        # 2. 普通文本历史必须是非空、严格交替的 user/assistant 对。
        for session in sessions:
            if not isinstance(session, dict):
                raise ValueError("session 必须是 JSON object")
            messages = session.get("messages")
            if (
                not isinstance(messages, list)
                or not messages
                or len(messages) % 2 != 0
            ):
                raise ValueError("session messages 必须是非空 user/assistant 对")
            for message_index, message in enumerate(messages):
                expected_role = "user" if message_index % 2 == 0 else "assistant"
                if not isinstance(message, dict) or message.get("role") != expected_role:
                    raise ValueError("session messages 必须严格按 user/assistant 交替")
                content = message.get("content")
                if not isinstance(content, str) or not content.strip():
                    raise ValueError("session message content 不能为空")

    async def _save_answer(
        self,
        environment: BaseEnvironment,
        case_id: str,
        answer: str,
    ) -> None:
        """把 Case ID 和最终答案写入 verifier 可读取的 Agent 日志目录。"""
        # 1. JSON 在 Python 中完成，避免模型文本参与 Shell 语法解析。
        answer_json = json.dumps(
            {
                "case_id": case_id,
                "answer": answer,
            },
            ensure_ascii=False,
        )
        encoded_answer = self._encode_text(answer_json)

        # 2. Base64 通过环境变量进入容器，只把解码后的 JSON 写入结果文件。
        await self.exec_as_agent(
            environment,
            command=(
                "set -e; "
                "printf '%s' \"$QUILLITH_FINAL_ANSWER\" "
                "| base64 --decode "
                ">/logs/agent/final_answer.json"
            ),
            env={
                "QUILLITH_FINAL_ANSWER": encoded_answer,
            },
        )
