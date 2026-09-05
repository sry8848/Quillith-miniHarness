# Quillith Harbor 兼容层需求变更记录

## 2026-09-02 15:02

- 上一个需求文档：`Quillith Harbor兼容层需求_2026-09-02_00-17.md`
- 当前需求文档：`Quillith Harbor兼容层需求_2026-09-02_15-02.md`
- 变更内容：
  - 明确兼容层位于 Quillith 项目内。
  - 明确每条 Harbor `run` 前在宿主机编译一次，同一 Job 的并行 Trial 复用同一产物。
  - 明确产物为可直接 `java -jar` 启动的 self-contained JAR。
  - 明确首版支持 Harbor Linux 容器，同时保留 Quillith Windows 本机运行能力。
  - 明确 Bash executable 不能继续使用单一平台硬编码。
  - 明确 Harbor 显式模型配置优先、Quillith 默认配置 fallback，并只支持 Anthropic API-compatible 协议。
  - 明确缺少 `GITHUB_PERSONAL_ACCESS_TOKEN` 时保持启动失败。
  - 明确首版只保存日志，不生成 ATIF trajectory。
- 变更原因：补齐首次需求文档中尚未确认的构建、运行平台、模型配置、凭据和日志边界。

## 2026-09-02 15:25

- 上一个需求文档：`Quillith Harbor兼容层需求_2026-09-02_15-02.md`
- 当前需求文档：`Quillith Harbor兼容层需求_2026-09-02_15-25.md`
- 变更内容：
  - 用固定生命周期明确 Maven 只在所有 Trial 启动前由宿主机构建一次。
  - 明确禁止在 Adapter `install()` 或其他 Trial 生命周期中构建 Quillith。
  - 增加仅承担 `build + harbor run` 的薄宿主机 launcher，并限制其首版范围。
  - 将 Linux 兼容范围收窄到项目选定的 Harbor Trial 环境，不承诺任意发行版和 CPU 架构。
  - 明确 Adapter 不增加、删除或改写 instruction。
  - 明确 v1 不承诺 Harbor Multi-step 支持，同时不限制 Exec 内部的模型与工具循环。
- 变更原因：进一步约束构建位置和首版范围，避免把重复编译放入 Trial，也避免宿主机启动层膨胀成新的平台。
