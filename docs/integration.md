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

四个身份 operations 来自 `src/services/user.ts` 的现有定义，GetMe 只取验证必要字段。BiosafetyUserId 映射 String；猪只读取阶段引入的其他 scalar 见下节。

生成命令和可选开发环境 schema 下载命令见 README。日常构建只读取本地 schema。原 Web 的 codegen 配置包含历史管理凭证，未复制到本项目。

## 已明确的范围差异

Web 仍保留旧 password API 和采样 simpleSignIn，但主 `/user/login` 当前实际使用 Logto。本轮接入主 Logto 链路，不把遗留 API 当作主登录，也不为取得测试成功而走 simple login。采样快捷登录属于后续业务切片。

旧 Web 部分文档仍描述更早的离线结构；该差异与本轮登录/GQL 无关，后续离线迁移再对照源码确认。

## 当前验收

真实环境和设备证据、尚未验证项统一记录在 `docs/status.md`，不以 Fake 测试代替真实登录完成。

开发环境人工验收使用 Logto 手机号登录（中国区号 `+86`），再选择 `zz-dev` 测试厂区；账号、密码及会话凭证只在授权的本地设备输入，不写入仓库。当前本地调试包为 `com.sentri.jingan.dev.local`，需用 `adb install -r` 保留已有会话；直接运行 `connectedAndroidTest` 可能重装应用并清除它。模拟器即使报告 `PARTIAL_CONNECTIVITY`，仍需以实际 dev API 请求和服务端读回判断连接是否可用。

### 模拟器登录操作记录（2026-10-23）

1. 启动本地 dev 包，进入 Logto 页面，在 **Farm ID** 填 `zz-dev`。
2. 在 **Username / Phone number / Email** 栏输入完整的 11 位手机号。**先输入号码，不必先操作左侧区号**；页面会根据输入自动识别区号。输入完成并等页面更新后，确认左侧最终显示 `+86`，号码仍为完整的 11 位。如果没有自动选对，再从区号列表搜索 `86`，手动选 `+86`，重新核对号码。
3. 点 **Sign in**。在密码页先核对提示的账号是 **`+86` 加完整的 11 位号码**，再输入本地授权的密码并点 **Continue**。如号码不对，返回上一步修正，不要反复试密码。
4. 认证成功后，在厂区列表选择 **`zz-dev`**。进入首页并看到该厂区的真实分区／单元数据，才算完成登录与厂区选择；停在密码页或厂区列表不算完成。

本次走过的错误和下次排查顺序：

- **区号误判**：曾只核对输入框里的数字，忽略左侧仍显示 `+1`；密码页实际提示为 `+1` 加剩余 10 位，说明正在尝试另一个账号。返回号码页，核对 `+86` 和完整 11 位手机号，再继续。以密码页显示的账号作为最后一道检查。
- **输入顺序误判**：先反复操作区号，忽略了完整输入手机号后页面会自动选区号，增加了无用步骤。正常顺序是先输入号码、等待识别、核对 `+86`；只有自动识别失败才手选。
- **ADB 输入不可靠**：连续按键曾在 WebView 输入框里出现字符顺序颠倒。以页面最终显示为准；必要时清空后重新输入，并在进入密码页后再次核对账号，不能凭 ADB 命令返回成功就继续。
- **2026-10-25 复核**：这台模拟器的 Farm ID、手机号受控输入框把连续 ADB 字符插到光标前，表现为整段倒序；区号搜索框和密码框按输入顺序显示。逐框读取页面显示值，清空错误输入后修正；不要把“某个字段会倒序”套用于其他字段。只有密码页显示 `+86` 与完整 11 位号码后才继续；验证不输出密码或保存密码截图。
- **把页面进度当作登录成功**：到达密码页或厂区列表仍未建立完整厂区上下文。必须选择 `zz-dev` 并进入真实首页后，再执行真实接口验证。
- **测试重装丢会话**：覆盖同签名安装使用 `adb install -r`；若测试任务卸载／重装了包，需要重新登录。已登录时运行只读设备测试可直接使用 `adb shell am instrument`，避免再次安装目标应用。
- **2026-10-26 会话保留与验证优先级**：用户要求优先交付，仅做必要测试。日常覆盖安装保留同一包名、签名和应用数据；不使用会卸载目标包的 Gradle connected 流程，不运行会清除真实会话的认证／存储测试。必要的设备验证只覆盖安装测试包并定向运行；完整回归留到版本检查点，未覆盖项记录到交付对照的待测清单。正常网络错误保留会话，不能为省去登录而绕过认证或延长 Token 有效期。
- **页面过渡需结束后再输入**：区号列表有滑入过渡和搜索防抖；等待页面稳定、点搜索框后再输入，确认列表只剩 `+86` 后再选择。过渡尚未结束时键盘输入可能落回手机号框。2026-10-26 复核再次用密码页的 `+86` 与完整号码确认账号；带 `+86` 的整串文本不能代替区号选择。

账号、密码和会话凭证仅在授权设备上输入，不写入源码、截图归档或 Git 文档。

## 基础数据与猪只读取（2026-10-09）

- NativeLocations：选中厂区的 farm_view_farm_unit 与 farm_view_pen_index，保留区域/单元全编码及栏位关联。
- NativePigList：同厂区、单元 location_code `_iregex`（旧页面语义）、可选精确栏位；ear_tag/ear_notch 区分大小写的包含搜索，用 SQL LIKE 转义 `%`、`_`、反斜杠；ID 升序、offset/limit 与同条件 aggregate count。
- NativePigDetail：精确 id/farm；身份、位置、状态、批次、日期、体重、温度、症状、疾病及健康记录。完整生产任务历史和写入后续按迁移清单引入。本轮没有将旧完整查询全部搬入。
- 旧 Hasura 的 farm_view_pig、farm_view_farm_unit、farm_view_pen_index 权限 metadata 均用 `farm_id = X-Hasura-Farm-Id` 约束。继续使用 JWT 所提供的服务端上下文；显式 where 与响应 farm_id 校验只是额外约束，没有新增管理员/角色头。

第二个业务仓库开始使用相同认证策略后，将共享执行逻辑提取为 GraphqlExecutor。请求在取令牌、刷新和返回时校验 contextRevision；登录/退出/厂区变化使旧请求取消，单纯同身份的令牌刷新不会使浏览失效。查询一次刷新重试、mutation 刷新但不自动重放。字段转换失败分类 Protocol，不用缓存掩盖接口契约错误。

网络模型由 Apollo 生成；GraphqlPigRemote 转换为 Pig/Locations/PigDetail 等业务模型；SnapshotCodec 使用独立、格式版本为 1 的存储结构。float8 使用 BigDecimal，不经 Double 保存；jsonb 用 JSON 流复制保留数字字面量并限制嵌套；timestamptz 校验日历、时间、时区偏移，保留服务器原始精度。本轮没有跨时区年龄/时长计算。网络 scalar 参数错误使用 Apollo JsonDataException，以免被包装成 Network。

只读缓存采用 SQLiteOpenHelper，schema 2：当前只有 account/locations/page/detail/browse 几类快照，暂不增加 Room 及其处理器；关键是复合归属键、事务和可验证恢复。网络失败或认证服务暂不可用才可回退缓存；明确认证失效、业务/权限错误、Protocol 不回退。

- 归属键：environment/oauthId/userId/farmId/kind/requestKey。筛选键按长度编码，区分大小写、空白、null、页大小和 offset。
- 成功的网络结果事务替换原快照；失败不覆盖。每个作用域 page/detail 合计最多保留最新 256 项；其余作用域及账户/位置元数据不参与该裁剪。
- 每个 environment/oauth/farm 只保留一个已验证的后端用户绑定。新绑定事务替换旧 account 元数据，保留旧业务快照的隔离归属；清除新作用域后不会重新选中旧用户账户。
- 没有按 TTL 自动丢弃离线快照；展示来源、抓取时间及刷新失败原因，直到刷新、显式清理或容量裁剪。缓存格式错误/损坏明确失败；schema 1→2 保留行，未知升级路径不做破坏性重建。
- 退出使会话失效、UI 清空、旧 ScopeLease 拒绝访问；缓存保留在私有不备份数据库中，重新授权相同作用域后可恢复。切换账号/厂区不能借用其他作用域的快照。
- BrowsePosition 保存已成功读取的筛选、offset、详情 ID 和验证用 cacheOnly 模式。重启先恢复会话/账户，再重开业务入口恢复位置；未缓存的页面明确报缺失，不冒用其他页。

只读链路不修改或清理旧 WebView IndexedDB。导入与回滚预案在 [迁移清单](migration.md)。设备最小验证入口和 SDK 来源见 [离线/设备调查](offline-devices.md)、[第三方归档](../THIRD_PARTY.md)。

资料依据：[Apollo 自定义 scalar](https://www.apollographql.com/docs/kotlin/essentials/custom-scalars)、[Android SQLite](https://developer.android.com/training/data-storage/sqlite)、[Android 离线数据层](https://developer.android.com/topic/architecture/data-layer/offline-first)。这些是实现参考；业务契约仍以旧源码和真实接口验证为依据。

## 登录输入光标回跳（2026-10-26）

若输入顺序倒置或光标回开头，先核对版本是否包含 `LoginWebView` 的 FrameLayout 宿主修复；无需反复重新输入账号密码。真实页对照与固定数字探针见[登录输入修复](login-input-cursor-fix.md)。不要为了测输入退出当前账号、清会话，或记录完整认证 URL；普通浏览器正常不等于 Compose 内嵌 WebView 的焦点路径正常。
