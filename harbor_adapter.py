import base64
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
    _SESSION_DIRECTORY = "/tmp/quillith-harbor-session"
    _REQUEST_PIPE = f"{_SESSION_DIRECTORY}/request.pipe"
    _RESPONSE_PIPE = f"{_SESSION_DIRECTORY}/response.pipe"

    def __init__(
        self,
        logs_dir: Path,
        jar_path: str,
        *args,
        **kwargs,
    ):
        """保存当前 Harbor Job 已经构建完成的唯一 JAR。"""
        super().__init__(logs_dir, *args, **kwargs)

        # 1. JAR 必须在创建 Trial 前由宿主机 launcher 构建完成。
        self._jar_path = Path(jar_path).expanduser().resolve()
        if not self._jar_path.is_file():
            raise FileNotFoundError(
                f"Quillith JAR 不存在：{self._jar_path}"
            )

        # 2. 带 provider 前缀时只接受 Anthropic-compatible 协议。
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

        # 3. GitHub Token 保持 Quillith 当前必需语义，并在 Trial 启动前失败。
        self._github_token = self._get_env(
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        )
        if not self._github_token:
            raise ValueError("缺少 GITHUB_PERSONAL_ACCESS_TOKEN")

        # 4. Harbor Linux 默认使用 /bin/bash，显式空值属于配置错误。
        configured_bash = self._get_env(
            "QUILLITH_BASH_EXECUTABLE"
        )
        if configured_bash is not None and not configured_bash.strip():
            raise ValueError("QUILLITH_BASH_EXECUTABLE 不能为空")
        self._bash_executable = configured_bash or "/bin/bash"

        # 5. 第一版只区分当前 Trial 是否已经启动 Quillith，不扩展恢复状态机。
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

        # 2. 每个 Trial 上传同一个宿主 JAR；上传不是重新构建。
        await environment.upload_file(
            self._jar_path,
            self._REMOTE_JAR_PATH,
        )

        # 3. 立即确认默认 Agent 用户可以读取 JAR，且 Java Runtime 可用。
        await self.exec_as_agent(
            environment,
            command=(
                f"test -r {shlex.quote(self._REMOTE_JAR_PATH)} && "
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
        runtime_env = {
            "QUILLITH_BASH_EXECUTABLE": self._bash_executable,
            "GITHUB_PERSONAL_ACCESS_TOKEN": self._github_token,
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
        await self.exec_as_agent(
            environment,
            command=(
                "set -e; "
                f"mkdir {shlex.quote(self._SESSION_DIRECTORY)}; "
                f"mkfifo {shlex.quote(self._REQUEST_PIPE)}; "
                f"mkfifo {shlex.quote(self._RESPONSE_PIPE)}; "
                f"nohup java -jar {shlex.quote(self._REMOTE_JAR_PATH)} harbor "
                "</dev/null "
                ">>/logs/agent/quillith.stdout.log "
                "2>>/logs/agent/quillith.stderr.log &"
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
        encoded_instruction = base64.b64encode(
            instruction.encode("utf-8")
        ).decode("ascii")
        request = f"TURN {encoded_instruction}"

        # 2. instruction 通过环境变量传入，不拼接到 Shell 命令或输出到日志。
        # Java 只有在 submit() 返回后才写 OK，所以命令返回就是 verifier 边界。
        await self.exec_as_agent(
            environment,
            command=(
                "set -e; "
                "printf '%s\\n' \"$QUILLITH_HARBOR_REQUEST\" "
                f">{shlex.quote(self._REQUEST_PIPE)}; "
                "IFS= read -r quillith_status "
                f"<{shlex.quote(self._RESPONSE_PIPE)}; "
                'if [ "$quillith_status" != "OK" ]; then '
                'echo "Quillith Harbor 返回未知状态" >&2; '
                "exit 1; "
                "fi"
            ),
            env={
                "QUILLITH_HARBOR_REQUEST": request,
            },
        )

    @override
    def populate_context_post_run(
        self,
        context: AgentContext,
    ) -> None:
        """首版不生成 trajectory 或额外 AgentContext 指标。"""
        pass
