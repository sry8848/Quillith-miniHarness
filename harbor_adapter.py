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
    SUPPORTS_RESUME = False
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
        """在当前 Trial 工作目录中执行一次 Quillith Exec。"""
        # 1. 只映射 Harbor 显式模型连接；缺失字段留给 Quillith 默认值。
        connection = self.model_connection
        run_env = {
            "QUILLITH_INSTRUCTION": instruction,
            "QUILLITH_BASH_EXECUTABLE": self._bash_executable,
            "GITHUB_PERSONAL_ACCESS_TOKEN": self._github_token,
        }
        if self._quillith_model:
            run_env["QUILLITH_MODEL"] = self._quillith_model
        if connection.api_key:
            run_env["QUILLITH_API_KEY"] = connection.api_key
        if connection.configured_base_url:
            run_env["QUILLITH_BASE_URL"] = connection.configured_base_url

        # 2. 使用容器当前工作目录，不假定所有任务都固定为 /app。
        workdir_result = await environment.exec("pwd")
        workdir = (workdir_result.stdout or "").strip()
        if workdir_result.return_code != 0 or not workdir:
            raise RuntimeError("无法确定 Harbor Trial 工作目录")

        # 3. instruction 只作为一个双引号包围的 argv 值展开，不进入命令文本。
        await self.exec_as_agent(
            environment,
            command=(
                "java -jar /installed-agent/quillith.jar "
                'exec "$QUILLITH_INSTRUCTION" '
                "> >(tee /logs/agent/quillith.stdout.log) "
                "2> >(tee /logs/agent/quillith.stderr.log >&2)"
            ),
            cwd=workdir,
            env=run_env,
        )

    @override
    def populate_context_post_run(
        self,
        context: AgentContext,
    ) -> None:
        """首版不生成 trajectory 或额外 AgentContext 指标。"""
        pass
