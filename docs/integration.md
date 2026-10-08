# 现有登录与 GraphQL 接入

## 参考状态

- 旧 Android：`../Sentri-Android`，commit `ee0f6c333fa99014ca067fc5b47c538d36285cff`，调查时工作区干净。
- Web：`../Sentri/mobile_webapp`，所属仓库 commit `4c1a2ff49e8d938e1af053d844018cc140c63f38`，调查时工作区干净。
- schema 是上述 Web 的完整 SDL 快照，来源为 `schema.graphql`，复制到本项目 `app/src/main/graphql/schema.graphqls`。

## 已确认现状

`src/config/base.ts` 与 `src/config/index.ts` 定义 dev/demo/prod，GraphQL 分别位于 `https://dev.sentri.cn`、`https://demo.sentri.cn`、`https://sentri.cn` 下 `/hasura/v1/graphql`。

`src/lib/logto.ts` 主认证为 Logto，issuer `https://auth.sentri.cn/oidc`，公开 client ID `hakfcyqmu5daasjwv2p6i`，Android redirect/logout redirect 为 `https://mobile_sentri_android/index.html`。resource 是当前环境 GraphQL 完整 URL。2026-10-08 已读取实际 discovery：支持 S256、公开 client 的 none 认证方式和 refresh token 相关 scope。

`src/pages/user/login.tsx` 发起登录；`callback.tsx` 完成回调后进入厂区选择；`farmSelection.tsx` 通过 oauth_id 查询列表，只有一个厂区时自动选择，多个厂区时由用户选择。ChooseFarm 成功后更新 farm_id，清除旧 access token 并刷新资源 Token，然后 GetFarmInfo 建立上下文。

`src/lib/client.ts` 发送 Authorization Bearer 和 sentri-language；未见此链路自行发送 Hasura role 或 farm header。厂区信息由更新后的 JWT/服务端上下文提供，原生实现保持一致。

旧项目安装的 `node_modules/@logto/client/lib/types/index.js` 会默认加入 openid/profile/offline_access，并使用 prompt=consent。本轮真实验证曾因遗漏默认 consent 而缺失 refresh token，现已补齐，并增加设备层授权请求回归测试。会话失效时使用 login consent，保持旧源码的强制重新认证逻辑。

旧 Android 的 `WebViewActivity.java` 通过包含 synthetic host 的 URL 将回调映射到本地 HTML。新实现不加载旧 dist，直接精确拦截约定的 scheme、authority 与 path，将授权码交给原生 PKCE 交换。旧 host 包含下划线，不能用 java.net.URI.host 判定，因为该属性会为 null；本项目有针对不同下划线 host 的回归测试。

## 原生链路

1. AuthRepository 保存原生 AppAuth 生成的授权请求（state、nonce、PKCE verifier）并打开 Logto 页面。
2. WebView 只允许认证 host 的 HTTPS 主页面和精确回调，不暴露 JSBridge；OAuth state 必须等于原请求。
3. AppAuth 使用 code/verifier/redirect/client/resource 交换 Token，校验 ID Token 的 issuer、audience、expiry 与 nonce。Code 流的 ID Token 从 TLS Token endpoint 获取，AppAuth 依据 OIDC 的 TLS 直接交换规则处理，不宣称做了独立 JWKS 签名校验。
4. 加密保存 AuthState、资源 Token、expiry、oauth ID。oauth ID 沿用 ID claims 的 oauth_id/oauthId/sub 顺序；初次登录进入厂区选择。
5. 原生 Apollo 查询厂区列表；选择后通过原生 mutation 调用 ChooseFarm，再强制刷新资源 Token，保存厂区，查询 GetMe/GetFarmInfo。
6. 页面只消费 AccountContext。返回的厂区必须与保存选择一致，否则清空该上下文并提示重新确认。
7. 重启读取加密会话；Token 过期前 60 秒刷新。并发请求共用 mutex 与拒绝 Token 比较，避免重复刷新。
8. query 的认证错误最多刷新并重试一次；再次拒绝清空该会话。mutation 可以准备刷新 Token，但不自动重放写请求。
9. 网络失败保留会话；invalid_grant/invalid_token 清除会话和厂区。退出先清除本地状态，按旧 SDK 尝试撤销 refresh grant（最长 5 秒，失败不撤销本地退出），再进入现有 end-session 页面；完成或取消后清理 WebView Cookie 与认证 origin 的 WebStorage。覆盖升级后与旧应用共用沙箱，因此不全局删除旧业务 origin 的存储，避免损失尚未迁移的离线数据。

目前 WebView allowlist 对应已调查的第一方 Logto 页面。如果启用外部社交身份提供商，需要明确补充实际 connector 跳转域名并验证，不能将任意 HTTPS 域名纳入授权完成来源。

## 错误与生成

网络/HTTP 服务失败、GraphQL error、业务响应 FAILURE 与认证错误分别分类。运行时不发送管理员凭证；mutation 未自动重试。全局刷新逻辑有行为测试。

四个 operations 来自 `src/services/user.ts` 的现有定义，GetMe 只取验证必要字段。BiosafetyUserId 映射 String；没有为未使用 scalar 发明映射。后续引入日期、JSON、Decimal 等 operation 时显式定义适配与相应测试。

生成命令和可选开发环境 schema 下载命令见 README。日常构建只读取本地 schema。原 Web 的 codegen 配置包含历史管理凭证，未复制到本项目。

## 已明确的范围差异

Web 仍保留旧 password API 和采样 simpleSignIn，但主 `/user/login` 当前实际使用 Logto。本轮接入主 Logto 链路，不把遗留 API 当作主登录，也不为取得测试成功而走 simple login。采样快捷登录属于后续业务切片。

旧 Web 部分文档仍描述更早的离线结构；该差异与本轮登录/GQL 无关，后续离线迁移再对照源码确认。

## 当前验收

真实环境和设备证据、尚未验证项统一记录在 `docs/status.md`，不以 Fake 测试代替真实登录完成。
