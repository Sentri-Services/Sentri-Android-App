# 初始化验收核对

2026-10-08，依据用户 goal-objective.md 的完整初始化范围核对当前文件、测试报告、安装包和模拟器运行。P 表示本阶段已完成；带范围说明的条目不扩大为未执行环境的结论。

| 目标条目 | 当前证据 | 结论 |
| --- | --- | --- |
| 一.1 可构建运行的 Kotlin/Compose | Gradle 配置、三环境 APK、01/04/05 截图、build-and-tests.txt | P |
| 一.2 架构/配置/日志/验证 | AppContainer、config/auth/network/ui、DiagnosticLog、scripts/verify.sh | P |
| 一.3–5 后端/现有登录/原生认证查询 | Identity.graphql、GraphqlFarmRepository、LogtoAuthGateway；04 与 runtime-events.txt | P，dev 真实环境 |
| 一.6 AI 工作说明 | AGENTS.md、README、docs/integration.md、docs/status.md | P |
| 一.7 Skills 与配置 | 38 技能及配套资源、skills-lock.json、docs/agents、.scratch/README.md | P |
| 一 不制定样式规范 | 默认 MaterialTheme/Material 3 临时页面，无设计系统或组件库 | P |
| 二 当前状态/工具与参考只读调查 | integration.md 的源码入口/参考 commit；JBR21/SDK36/AVD；旧仓库 git status 干净 | P |
| 二 环境/endpoint/schema/scalar/headers | AppConfig、flavors、schema 快照与 SHA256、scalar 映射；请求头行为测试 | P |
| 二 登录/WebView/桥接/Cookie/存储/刷新/上下文/退出 | integration.md 已确认现状与范围差异、认证源码、真实 03–08 | P |
| 三 Kotlin DSL/Catalog/BOM/单 Activity | settings/build.gradle.kts、libs.versions.toml、Manifest/MainActivity | P |
| 三 ViewModel/协程/StateFlow/单向状态/DI | ConnectionViewModel、AuthRepository、FarmRepository 接口、AppContainer | P |
| 三 官方兼容与固定版本 | ADR-0001 引用资料、Gradle8.13/AGP8.12.2/Kotlin2.2.21 等；实际构建成功 | P |
| 三 数据库与离线边界 | ADR-0001 与 status.md 明确数据访问接入点 | P，按目标允许暂不实施 |
| 四.1 schema 来源/更新 | 完整旧 Web SDL 哈希一致；Apollo introspection 配置与 README 更新命令 | P |
| 四.2–3 operations/生成/scalar | 四个 operations；BiosafetyUserId→String；generated 文件在 build；offline-codegen.txt | P |
| 四.4–6 环境/认证头/上下文 | flavors + Authorization/sentri-language；ChooseFarm→refresh→GetMe/GetFarmInfo | P |
| 四.7 网络/GQL/业务/认证区分 | ApiFailure/AuthErrorPolicy/GraphqlFarmRepository、对应 JVM 测试 | P |
| 四.8 刷新后请求/mutation 克制 | query 刷新重试一次、mutation 不重放；MockWebServer 测试 | P |
| 五.1–3 进入/完成/取消/原生状态 | 01–04、08；CallbackPolicy/state 检查、AuthRepository/ViewModel | P |
| 五.4–5 持久化/重启恢复 | Keystore AES-GCM、备份排除、设备存储测试、05 新进程真实查询 | P |
| 五.6–8 到期/并发/刷新失败/重新登录 | AuthRepository + JVM 测试、OIDC 请求设备测试；真实主动刷新事件 | P，自然到期/invalid_grant 用测试替身验证 |
| 五.9 退出清理 | signOut 本地清除/revoke/end-session/Cookie/WebStorage；06/07、空私有存储 | P |
| 五.10 用户/厂区恢复或选择 | 03/04/05，单厂区自动选择、多厂区/不一致分支 ViewModel 测试 | P |
| 五 WebView 最小协议/回调来源/生命周期 | 精确 callback、第一方 HTTPS allowlist、无 JSBridge、DisposableEffect/back/error | P |
| 五 独立身份与旧应用回调冲突 | flavor applicationId、移除 AppAuth 外部 URI receiver、实际现有回调成功 | P |
| 五 外部配置变更 | 沿用现有 public SPA client/resource/redirect，真实成功，无变更需求 | P |
| 六 环境/状态/加载/成功失败/重试/退出 | ConnectionScreen/strings/ViewModel；Compose 设备测试与 01–08 | P |
| 七 官方完整集合/版本/位置/清单/限制/更新 | lock 逐资源哈希、官方 tree 38/38、LICENSE、verify/update scripts、skills.md | P |
| 七 tracker/文档/必要状态/原生验证/vendor 边界 | .scratch 纳入 Git、三份 setup 模板、AGENTS 链接、Skills 说明 | P |
| 七 按需流程/不配置外部任务或持续循环 | 项目约定明确，仅安装和适用诊断，不强制规划链 | P |
| 八 AGENTS/README/ADR | 架构/环境/命令/接入/Skills/约束/标准均有链接 | P |
| 九 配置示例/凭证保护 | example 仅公共配置；私有文件忽略、日志事件无凭证；凭证扫描与存储检查 | P |
| 九 Debug/Lint/必要测试 | 27 JVM+5 设备报告零失败；三 Debug APK；Lint 零错误 | P |
| 九 启动→登录→原生查询→重启→退出 | 01–07 与 PID/时间事件一致；实际 dev | P |
| 十 自主完成/参考不变/无推送发布 | 本地工程与证据；参考仓库干净；没有远端写入/发布 | P |
| 十 完整交付报告/外部项/正式UI下一步 | README、status.md、integration.md、Skills 说明与最终报告 | P |

自动化测试覆盖由 XML 与测试源码共同核对，不能用 Fake 替代实际 dev 登录证据。此次验收不包含正式 UI、全部业务 operations、demo/prod 真实账号、生产发布、真机矩阵或完整离线引擎。
