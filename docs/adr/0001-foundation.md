# ADR-0001：原生基础与现有认证承接

日期：2026-10-08。状态：初始化采用，生产发布策略待确认。

## 决定

使用单 app 模块，按 auth/network/config/ui 划分包，显式构造函数注入。单 Activity + Compose + ViewModel/StateFlow。没有提前引入业务数据库、离线引擎或设计系统，后续真实边界成立时再拆模块。

选择本机已支持的 AGP 8.12.2 + Gradle 8.13 + Kotlin 2.2.21 + JBR 21，目标字节码 17。Apollo 官方兼容矩阵要求 v5 使用 KGP 2.1+、Gradle 8+、AGP 8+，本组合满足；实际构建是最终验证依据。OkHttp 与 Apollo v5.2 的传递依赖对齐到 5.3.0，防止测试服务器与运行时内部 API 不匹配。Compose BOM 统一管理 Compose 版本。

保留现有 Logto 服务、公开 SPA client ID、资源 URL 与 `https://mobile_sentri_android/index.html` 回调。WebView 展示现有认证页面，AppAuth 负责原生 PKCE、state/nonce、令牌交换与刷新；不引入 JSBridge，不嵌入旧业务 Web 应用。回调在 WebView 内精确拦截，开发包不注册外部回调处理器，避免与旧应用争抢链接。

AppAuth 0.11.1 是官方 Maven 最新稳定版本。本项目只使用其协议和 Token API；它推荐的 Custom Tabs 展示方式不适用于沿用现有 SPA synthetic HTTPS 回调的本轮约束。若未来切换官方 Logto Native SDK，需要单独配置 native client/redirect 并验证兼容性。

本地 Token、AuthState 和待完成 PKCE 请求使用 Android Keystore AES-GCM 加密，禁止备份；失效会话清除账号与厂区。GraphQL 不使用旧配置中的管理员密钥，厂区权限沿用 JWT 内上下文。

## 后续边界

正式组件库与设计规范到位后再设计原生 UI。Room/离线命令队列、设备 SDK、生产签名、版本迁移和旧 IndexedDB 待同步数据处理均留给后续明确任务。

## 资料

- https://developer.android.com/build/releases/past-releases/agp-8-12-0-release-notes
- https://www.apollographql.com/docs/kotlin
- https://github.com/openid/AppAuth-Android
- https://docs.logto.io/quick-starts/android
