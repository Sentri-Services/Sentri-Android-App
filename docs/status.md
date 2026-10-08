# 初始化状态

2026-10-08：本阶段工程、GraphQL、现有 Logto 登录与真实开发环境闭环已完成。正式 UI、设计系统和业务页面迁移等待后续任务。

## 实际运行证据

设备：`Sentri_Dev_API_36`，Android 16 / API 36，`emulator-5554`。应用：`com.sentri.nativeapp.dev`。测试使用旧项目提供的开发测试条件，账号凭证没有复制到新仓库。

| 操作 | 结果与证据 |
| --- | --- |
| 启动、打开现有登录 | [未登录](../verification/01-signed-out.png)、[现有登录页](../verification/02-login-page.png) |
| WebView 登录返回原生 | 17:53:55 `login_exchange_succeeded`、`refresh_token_available`；[原生厂区列表](../verification/03-native-farm-selection.png) |
| 选择原先测试厂区、刷新、原生查询 | 17:54:15 `token_refresh_succeeded`、`native_account_query_succeeded`；[认证查询成功](../verification/04-native-authenticated-account.png) |
| 强制停止应用并重新启动 | 新进程 7405 在 17:54:38 再次完成原生查询；[恢复后的界面](../verification/05-restored-session.png) |
| 原生退出、远端撤销、end-session 返回 | 17:55:03 `token_revocation_succeeded`、`local_session_cleared`；[退出后](../verification/06-signed-out-after-logout.png) |
| 退出后再次强制停止并启动 | [仍未登录](../verification/07-signed-out-after-restart.png)；检查私有会话存储，session 与 pending 条目均已删除 |
| 再次打开登录并取消 | [返回未登录](../verification/08-cancelled-login.png)；再次检查会话存储，无 session/pending |

上述事件保存在 [脱敏运行日志](../verification/runtime-events.txt)。真实 GraphQL 调用为 GetFarmSelectionList、ChooseFarm、GetMe、GetFarmInfo；ChooseFarm 选择已选中的同一个开发测试厂区，未变更生产数据。

登录验证中 ADB 输入/按键步骤未形成稳定的提交反馈，出现过失败；最终通过开发包 WebView 的 CDP 输入和现有页面按钮完成真实流程。没有绕过登录、注入 Token 或改用遗留快捷登录，也没有据此修改认证协议。临时诊断脚本、UI XML 与端口转发已移除。

## 构建与自动化验证

已执行：

```bash
SENTRI_GRADLE_DIRECT=1 ./scripts/gradle.sh \
  :app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug \
  :app:assembleDemoDebug :app:assembleProdDebug \
  :app:connectedDevDebugAndroidTest --console=plain
SENTRI_GRADLE_DIRECT=1 ./scripts/gradle.sh \
  :app:generateSentriApolloSources --offline --rerun-tasks --console=plain
SENTRI_GRADLE_DIRECT=1 ./scripts/verify.sh
```

- 三个环境 Debug 构建成功；Dev Lint 完成，无错误，有版本更新与 Kotlin 扩展建议等非阻塞警告。
- 27 个 JVM 测试通过：认证转换/恢复、到期及拒绝 Token 的并发刷新、失效清理、网络失败保留、退出竞态、mutation 不重放、请求头与错误分类、上下文一致性。
- 5 个设备测试通过：Keystore 加密与清理、损坏密文处理、Compose 登录入口、默认/强制授权的 consent 与 PKCE 契约。
- 强制离线执行 Apollo 生成成功，日常生成不需要在线 introspection；依赖首次下载仍需要网络。
- 38 个 Skills 完整性通过，固定版本与当日上游 main 一致。
- Skills 更新器在临时 Git 仓库验证了正常替换及元数据写入失败时的文件/锁/许可回滚；本次未执行当前项目的版本更新。证据见 [更新器验证](../verification/skills-updater-check.txt)。

原始输出：[完整构建与设备测试](../verification/build-and-tests.txt)、[常规验证](../verification/final-checks.txt)、[离线生成](../verification/offline-codegen.txt)。计数与校验值：[test-results.json](../verification/test-results.json)。逐项范围核对：[completion-audit.md](../verification/completion-audit.md)。

## 当前范围与后续

本轮真实验证仅覆盖 dev。demo/prod 的配置与 Debug 编译已验证，未使用这些环境进行真实登录或业务查询。到期时间和认证失效分支通过自动化测试覆盖；真实环境验证了主动刷新，没有等待自然到期，也没有主动破坏服务端会话。

当前没有需要调整的外部认证白名单或客户端配置。开发包独立身份，不注册抢占旧应用的回调处理器。两个旧项目工作区保持干净，本轮没有推送或发布。

后续在产品规范到位后接入 Compose 主题与组件，并选择第一个业务页面，补充它需要的 operations、状态和交互。离线数据库/同步可接在数据访问边界；生产应用身份、签名、升级迁移、旧离线数据、设备 SDK 与社交登录 connector 属于后续明确任务。
