# Sentri Android App

独立的 Kotlin + Jetpack Compose 原生工程。首阶段接入现有 Logto 登录和 Hasura/GraphQL，业务界面后续迁移。验证页面使用默认 Material 3；业务 UI 以相邻 Sentri-UI 中对应 feature 的已确认设计为依据。

## 开发环境

- Android Studio 自带 JBR 21；项目字节码目标 Java 17。
- Android SDK 36、Build Tools、platform-tools；最低 Android 7.0 / API 24。
- Gradle Wrapper 8.13、AGP 8.12.2、Kotlin 2.2.21、Compose BOM 2026.06.01。
- Apollo Kotlin 5.2.0、OkHttp 5.3.0、AppAuth 0.11.1。锁定版本见 `gradle/libs.versions.toml`。

创建忽略提交的 `local.properties`，设置 `sdk.dir`。使用 Android Studio 打开本目录，选择 `devDebug`。
可将 `sentri.local.properties.example` 复制为 `sentri.local.properties` 覆盖公开 endpoint、client ID 和回调配置。登录凭证由现有 Logto 页面输入，工程无内置账号或测试密码。

**下次在模拟器登录 dev：**在 Logto 页填 `zz-dev`，直接在手机号栏输入完整的 11 位号码，等待页面自动识别区号，再确认左侧是 `+86`。进入密码页后核对显示的号码为 `+86` 加完整手机号，然后输入密码；认证后选择 `zz-dev` 厂区，进入真实首页才算登录完成。本次的区号误判、ADB 输入乱序和安装测试导致会话丢失等错误及排查顺序见[模拟器登录操作记录](docs/integration.md#模拟器登录操作记录2026-10-23)。账号和密码只在授权设备上输入，不写入仓库。

真机验证可使用独立的本地 APK 签名：生成专用 `.jks`，按 `sentri.local.signing.properties.example` 创建忽略提交的 `sentri.local.signing.properties`。普通 `devDebug` 会采用该签名并安装为 `com.sentri.jingan.dev.local`；没有本地配置时仍使用 Android 调试签名。签名文件和密码不提交。此配置只用于普通本地 Debug，不改变 Logto Client ID 或发布签名。已有同包名但不同签名的本地包不能直接覆盖安装，需先处理其数据迁移或卸载。

## 常用命令

```bash
./scripts/gradle.sh :app:generateSentriApolloSources
./scripts/verify.sh
./scripts/gradle.sh :app:installDevDebug
./scripts/gradle.sh :app:connectedDevDebugAndroidTest
```

本机全局 Gradle 配置可能指定失效的本地代理。遇到依赖下载失败时，使用 `SENTRI_GRADLE_DIRECT=1 ./scripts/verify.sh` 绕过该代理，不修改全局配置。

环境为 dev / demo / prod。发布构建沿用旧包名、签名和 OSS APK 更新通道，支持覆盖安装。版本分别记录在 `versions.properties`，不会互相推进。普通本地 Debug 在旧环境包名后加 `.local`，不会覆盖已安装的旧应用；发布 Debug 使用 `-PenableApkUpdate=true`，必须注入原签名材料。签名配置和发布命令见 [发布与更新](docs/release.md)。

## GraphQL

完整 schema 快照来自旧 Web 仓库；日常构建离线生成 Kotlin 类型，不进行线上 introspection。
身份 operations 为 GetMe、GetFarmInfo、GetFarmSelectionList、ChooseFarm；新增 NativeLocations、NativePigList、NativePigDetail。用户 ID 映射为 String；timestamptz 保留有效时间戳文本，float8 映射 BigDecimal，jsonb 保留经过验证的 JSON 数字字面量。

更新开发 schema：在当前 shell 中安全设置 `SENTRI_SCHEMA_TOKEN` 后运行 `./scripts/gradle.sh :app:downloadSentriApolloSchema`。需要有 introspection 权限的凭证；不要把 Token 放进命令参数、示例或提交。更新后检查 schema diff，再生成与验证。当前仅配置开发端点的下载任务。

## 目录

| 入口 | 用途 |
| --- | --- |
| `app/src/main/java/com/sentri/nativeapp/auth/` | Logto PKCE、会话、刷新、Keystore 存储 |
| `app/src/main/java/com/sentri/nativeapp/network/` | 原生 Apollo 调用、错误分类、业务模型 |
| `app/src/main/java/com/sentri/nativeapp/config/` | 环境配置 |
| `app/src/main/java/com/sentri/nativeapp/ui/` | ViewModel、登录 WebView、接入验证页 |
| `app/src/main/java/com/sentri/nativeapp/update/` | 兼容旧清单的 APK 检查、下载校验与系统安装 |
| `app/src/main/java/com/sentri/nativeapp/pigs/` | 单元/栏位、猪只读取、隔离快照缓存与默认验证页 |
| `app/src/main/java/com/sentri/nativeapp/offline/` | 离线命令依赖、顺序与结果不明确规则；巡检打卡已接受控真实发送，其余业务写入仍待接入 |
| `app/src/main/java/com/sentri/nativeapp/devices/` | 外接键盘扫码、USB HID SDK adapter、单读生命周期和验证入口 |
| `app/libs/reader.jar` | 与旧 Android 一致的 RFID SDK，来源见 THIRD_PARTY.md |
| `app/src/main/graphql/` | schema 快照与手写 operations |
| `app/src/test/`、`app/src/androidTest/` | JVM 行为测试与设备验证 |
| `.agents/skills/` | 完整、固定版本的 Matt Pocock Skills |

详细行为与旧代码证据见 [接入说明](docs/integration.md)，技术理由见 [ADR](docs/adr/0001-foundation.md)，真实验证状态见 [进度](docs/status.md)。

本阶段业务范围与迁移顺序见 [迁移清单](docs/migration.md)，离线及设备边界见 [前置准备](docs/offline-devices.md)。业务 UI 的需求定位、设计成熟度检查和反哺见 [Sentri-UI 实现流程](../Sentri-UI/docs/implementation-workflow.md)。接入真实数据后的首页内容与逐卡规则见 [首页工作台 PRD](docs/home-prd.md)。登录并选定厂区后默认进入连接真实读取的 `workbench.today` 首页：厂区、分区、单元及能可靠归属的开放生产、免疫和转舍任务来自当前账号；免疫、转舍的执行页及其他未接入任务入口会明确标注状态。按系统返回可进入接入验证页，再使用“基础数据与猪只查询”和“扫码与 RFID 验证”入口验收。接入验证页仍提供无需登录的“首页 UI 预览”样例入口；实现范围与待确认的设计反馈见 [Home UI 交接](docs/home-ui-handoff.md)。

`LivePigReadTest` 默认跳过，只有显式提供 `liveRead=true` 或 `offlineRead=true` 才会使用现有真实开发登录会话。不要把常规设备测试通过当作真实后端验证。Gradle connected 测试可能卸载目标应用；需要保留手动登录会话时，先 assemble 两个 Debug APK，使用 `adb install -r`，再直接运行 `am instrument`。不要把账号或令牌作为 instrumentation 参数。


中英文/生产任务契约/离线持久化基础见 [基础阶段记录](docs/language-offline.md)，全业务扩展见 [当前实现](docs/business-foundations.md) 与 [任务表](.scratch/business-foundations/spec.md)。`scripts/verify.sh` 还会检查中英文资源与 GraphQL 消费契约；首次需要 Node.js/npm 安装锁定的工具依赖，依赖存在后的检查不请求线上 schema。巡检打卡已在 App 活跃、启动及 WorkManager 联网后台任务中接入真实 mutation 发送、读回确认与安全恢复；其余业务操作尚未接入真实发送。
