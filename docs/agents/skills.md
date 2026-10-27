# Skills 安装与项目适配

来源：https://github.com/mattpocock/skills

固定 commit：`b0618bc436ad893b3c5e84e55fba86586d34a404`。安装日期：2026-10-08。完整安装上游 `skills/` 下全部 38 个技能目录，包含 engineering、productivity、misc 与 in-progress，配套文件一并保留。

通过 Codex skill-installer 的 GitHub 安装脚本安装到项目 `.agents/skills/`。清单与全部资源哈希在 `.agents/skills-lock.json`，许可在 `.agents/MATT-POCOCK-LICENSE`。可运行 `python3 scripts/verify-skills.py` 检查完整性。新安装的技能可在后续轮次发现；当前未强制执行任何规划链。

沿用 setup 技能提供的 local-markdown、domain 与 triage 模板，选择由用户目标明确授权：本地 Markdown 纳入 Git、AGENTS.md、单一上下文。五个 triage 标签采用默认值。没有配置远端任务系统、AFK 调度或外部服务。上游 setup 的重复询问在这些已明确的选择上不再执行。

使用原则：明确任务直接实施；复杂产品问题才采访；跨会话方案才写 spec；大任务才拆 tickets；不确定的大工作才用 wayfinder。in-progress、TypeScript、Claude 专属以及外部服务相关技能虽然完整安装，只在平台与任务匹配时使用。脚本或 Hook 的存在不意味着它已执行或启用。

Android 的验证路径为 Gradle 代码生成、编译、Lint、行为测试、Compose UI、模拟器与真机。WebView 登录可以补充 Web 验证；浏览器测试不能证明原生会话恢复或设备行为。业务 UI 按 Sentri-UI 对应 feature 的成熟度接入，技能不得擅自把占位或候选设计升级为正式规范。

跨仓库业务 UI 开发使用 Sentri-UI 的 `sentri-feature-delivery` skill；其项目源位于 `../Sentri-UI/.agents/skills/sentri-feature-delivery/`，本机 Codex 通过 `~/.codex/skills/sentri-feature-delivery` 链接安装。该 skill 只串联已有需求、原型、组件和原生验证，不替代 Sentri-UI 的设计评审流程。具体步骤以 [Sentri-UI 实现与反哺流程](../../../Sentri-UI/docs/implementation-workflow.md) 为准。

项目适配保存在本文件、AGENTS.md 与 `docs/agents/`，上游技能文件保持原样。多代理实现仅在明确授权、规格稳定且依赖允许时使用。

更新：先审查上游变更并选定完整 commit，再运行 `python3 scripts/update-skills.py --revision <commit>`，检查 vendor 和锁文件 diff，运行完整性校验并复查项目适配。更新不会自动运行新技能或修改全局 Codex 设置。
