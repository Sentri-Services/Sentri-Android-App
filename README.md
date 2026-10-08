# Sentri Android App

独立的 Kotlin + Jetpack Compose 原生工程。首阶段接入现有 Logto 登录和 Hasura/GraphQL，业务界面后续迁移。验证页面使用默认 Material 3，尚未定义产品样式规范。

## 开发环境

- Android Studio 自带 JBR 21；项目字节码目标 Java 17。
- Android SDK 36、Build Tools、platform-tools；最低 Android 7.0 / API 24。
- Gradle Wrapper 8.13、AGP 8.12.2、Kotlin 2.2.21、Compose BOM 2026.06.01。
- Apollo Kotlin 5.2.0、OkHttp 5.3.0、AppAuth 0.11.1。锁定版本见 `gradle/libs.versions.toml`。

创建忽略提交的 `local.properties`，设置 `sdk.dir`。使用 Android Studio 打开本目录，选择 `devDebug`。
可将 `sentri.local.properties.example` 复制为 `sentri.local.properties` 覆盖公开 endpoint、client ID 和回调配置。登录凭证由现有 Logto 页面输入，工程无内置账号或测试密码。

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
四个当前 operations 为 GetMe、GetFarmInfo、GetFarmSelectionList、ChooseFarm；用户 ID scalar 映射为 String，其他 scalar 随实际 operation 引入后明确映射。

更新开发 schema：在当前 shell 中安全设置 `SENTRI_SCHEMA_TOKEN` 后运行 `./scripts/gradle.sh :app:downloadSentriApolloSchema`。需要有 introspection 权限的凭证；不要把 Token 放进命令参数、示例或提交。更新后检查 schema diff，再生成与验证。当前仅配置开发端点的下载任务。

## 目录

| 入口 | 用途 |
| --- | --- |
| `app/src/main/java/com/sentri/nativeapp/auth/` | Logto PKCE、会话、刷新、Keystore 存储 |
| `app/src/main/java/com/sentri/nativeapp/network/` | 原生 Apollo 调用、错误分类、业务模型 |
| `app/src/main/java/com/sentri/nativeapp/config/` | 环境配置 |
| `app/src/main/java/com/sentri/nativeapp/ui/` | ViewModel、登录 WebView、接入验证页 |
| `app/src/main/java/com/sentri/nativeapp/update/` | 兼容旧清单的 APK 检查、下载校验与系统安装 |
| `app/src/main/graphql/` | schema 快照与手写 operations |
| `app/src/test/`、`app/src/androidTest/` | JVM 行为测试与设备验证 |
| `.agents/skills/` | 完整、固定版本的 Matt Pocock Skills |

详细行为与旧代码证据见 [接入说明](docs/integration.md)，技术理由见 [ADR](docs/adr/0001-foundation.md)，真实验证状态见 [进度](docs/status.md)。
