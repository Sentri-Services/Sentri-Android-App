# 发布与覆盖更新

本项目发布构建接替旧 Android 应用。Kotlin namespace 保持 `com.sentri.nativeapp`，安装身份由 applicationId 决定，沿用旧环境包名和签名。此决定更新了初始化时的独立发布身份，见 [ADR-0002](adr/0002-update-compatibility.md)。

## 环境、版本与通道

2026-10-08 核查结果：

| 环境 | 发布 applicationId | 旧仓库版本 | 线上 Debug 版本 | 当前候选版本 |
| --- | --- | --- | --- | --- |
| dev | com.sentri.jingan.dev | 135 / 1.3.5 | manifest：135 / 1.3.5 | 136 / 1.3.6 |
| demo | com.sentri.jingan.demo | 136 / 1.3.6 | manifest：136 / 1.3.6 | 137 / 1.3.7 |
| prod | com.sentri.jingan | 134 / 1.3.4 | manifest 404；last.zip：132 / 1.3.2 | 135 / 1.3.5 |

三个环境独立维护 `versions.properties`。真正触发更新的是递增的 versionCode。发布前再次检查远端，不依赖本次调查快照。

沿用 `https://farm-apk.sentri.cn/apks/<env>/debug/manifest.json`。当前旧 CI 发布的是 **Debug APK**；Release 构建仍支持原签名，但不会擅自把 OSS 通道切到 release。

普通 Debug 为 `<旧环境 applicationId>.local`，禁用启动更新并使用本机调试签名。开启 `-PenableApkUpdate=true` 后，Debug 恢复旧 applicationId、原签名与启动检查，成为可发布构建。Release 同样要求原签名。两类产物不能混淆。

本地真机验证包可在忽略提交的 `sentri.local.signing.properties` 中指定独立 keystore；普通 Debug 会优先使用它。本机 2026-10-11 的 devDebug 证书 SHA-256 为 `dbbc2d7d19466da6d9364d6fd9c0219c1ad7388656f824750a9b0b3eaff7555e`，与上述旧发布证书不同。这个新 key 仅用于 `.local` 包，不可用于覆盖旧发布包；发布 Debug/Release 仍由原签名配置和安全门槛控制。复制到其他开发机时需安全保管 keystore 及密码，否则无法覆盖安装同包名的本地验证包。

## 原签名材料

所有环境沿用 `sentri` alias。期望签名证书 SHA-256：

`2d458afc199a5cf9cc04fc35a91cba4b5cc3e52e4898a7e6df7cd0fa35b7ebe6`

这是公开证书指纹，不是私钥。发布脚本同时校验原企业证书 `META-INF/ENTERPRISE.CER` 的内容哈希，两个期望值在 `deployment/contract.json`。新工程通过生成的 Java resources 在 APK 签名前嵌入企业证书，避免依赖旧 Groovy 插件；实际 ZIP 条目与签名已验证。

把 `sentri.signing.properties.example` 复制为忽略提交的 `sentri.signing.properties`，在安全环境提供密码；或注入以下环境变量：

- `SENTRI_KEYSTORE_FILE`：原 key.jks 路径。
- `SENTRI_STORE_PASSWORD`、`SENTRI_KEY_PASSWORD`：原密码。
- `SENTRI_KEY_ALIAS`：默认 sentri。
- `SENTRI_ENTERPRISE_CERTIFICATE`：原 ENTERPRISE.CER 路径。

本机可引用同级旧项目的材料，不复制私钥或密码进新仓库。缺少材料时发布构建失败，不回退为其他签名。

## 本地构建与产物准备

在已安全注入上述变量的 shell 中：

```bash
SENTRI_GRADLE_DIRECT=1 ./scripts/gradle.sh \
  :app:assembleDevDebug :app:assembleDemoDebug :app:assembleProdDebug \
  -PenableApkUpdate=true
python3 scripts/apk_release.py all
```

产物位于忽略提交的 `release-upload/<env>/debug/`：

- `Sentri_<versionName>_<versionCode>_<env>_debug.apk`：版本化 APK。
- `last.zip`：与 APK 字节一致，兼容更早客户端；不是 ZIP 压缩包。
- `manifest.json`：schemaVersion、versionCode、versionName、applicationId、buildType、apkUrl、fileSize、sha256、forceUpdate，保持旧字段协议。

准备步骤校验实际 APK 的包名、版本、签名和企业证书，并要求 versionCode **严格大于**线上清单版本。清单不存在时检查旧 last.zip。网络异常或签名不一致时失败；同版不能重复发布。已有 release-upload 时拒绝混入新文件，请先保存需要的产物再清理。`--offline` 仅供本地结构验证，不用于部署。

## 发布入口与 CI

沿用 `releaseDev / releaseDemo / releaseProd / releaseAll` Gradle 入口。这些命令会增加对应环境 versionCode 与 versionName patch，提交版本文件、创建 Tag 并原子推送当前 main/master 分支与 Tag，**执行即触发外部发布**。

在新仓库的干净 master 分支、已与 origin/master 同步时，操作与旧项目一致，按需选择一个命令：

```bash
./gradlew releaseDev
./gradlew releaseDemo
./gradlew releaseProd
./gradlew releaseAll
```

本地需要 JDK 21、Python 3.9+ 与 Git 推送权限。macOS 可用 `./scripts/gradle.sh releaseDev` 自动选择 Android Studio 自带 JBR；全局 Gradle 代理不可用时加 `SENTRI_GRADLE_DIRECT=1`。发布入口只负责版本提交与 Tag 推送，APK 在 Actions 构建，因此执行这些入口不需要本地注入签名密码。

只预览、不修改文件或远端：

```bash
python3 scripts/release.py dev
python3 scripts/release.py all
```

Tag 沿用 `android-dev-vX.Y.Z`、`android-demo-vX.Y.Z`、`android-prod-vX.Y.Z`、`android-all-r<北京时间戳>`。main/master 与 PR 普通构建不上传；发布 Tag 才开启 APK 更新并上传对应环境。发布 Tag 在同一仓库内串行执行，避免清单被较早任务覆盖。先上传版本化 APK 与 last.zip，成功后再上传 manifest，保留历史文件。

GitHub 仓库需提供：

- `SENTRI_KEYSTORE_BASE64`：原 JKS 的 Base64。
- `SENTRI_ENTERPRISE_CERTIFICATE_BASE64`：原企业证书的 Base64。
- `SENTRI_STORE_PASSWORD`、`SENTRI_KEY_PASSWORD`。
- 原 `ALIYUN_ACCESS_KEY_ID`、`ALIYUN_ACCESS_KEY_SECRET`、`ALIYUN_OSS_BUCKET`、`ALIYUN_OSS_ENDPOINT`。

2026-10-09 已确认 origin 为 `git@github.com:Sentri-Services/Sentri-Android-App.git`，仓库默认分支为 master，四个发布入口的 Gradle dry-run 与版本脚本预览通过。已从本地原签名材料配置四项 SENTRI Secrets，上传前签名证书与企业证书指纹均与部署契约一致。

四项 ALIYUN Secrets 已存在于旧仓库，新仓库仍待配置。GitHub 只返回已保存 Secret 的名称和时间等元数据，不提供原值，因此不能从旧仓库自动复制；本地参考项目和当前环境中也未找到原 OSS 配置。需要提供原配置文件或在新仓库填写同名配置。若采用组织级 Secrets，需确保授权此仓库使用。

四项 ALIYUN 配置沿用原 OSS 账号、Bucket 和 Endpoint。新增四项 SENTRI 配置用于安全注入旧仓库中的原签名材料，不能替换为新生成的 key。可以在 [新仓库 Actions Secrets](https://github.com/Sentri-Services/Sentri-Android-App/settings/secrets/actions) 配置，也可使用已登录的 GitHub CLI；例如将两个原文件直接传入，不在终端打印内容：

```bash
base64 < ../Sentri-Android/key.jks | gh secret set SENTRI_KEYSTORE_BASE64 --repo Sentri-Services/Sentri-Android-App
base64 < ../Sentri-Android/ENTERPRISE.CER | gh secret set SENTRI_ENTERPRISE_CERTIFICATE_BASE64 --repo Sentri-Services/Sentri-Android-App
# 交互输入原签名密码；不要把明文写进命令历史。
gh secret set SENTRI_STORE_PASSWORD --repo Sentri-Services/Sentri-Android-App
gh secret set SENTRI_KEY_PASSWORD --repo Sentri-Services/Sentri-Android-App
```

本轮按用户授权配置签名 Secrets，并同步普通 master 分支，没有推送发布 Tag、上传 APK 或改动线上指针。新仓库的首次普通 CI 已通过，此类构建不会上传；实际发布仍需配置完成后验证。同一 OSS 通道应有一个发布源，避免新旧仓库流水线竞争。

## 应用内更新

发布包启动检查当前环境与 buildType 清单。仅高版本触发下载，相对 apkUrl 按清单 URL 解析，仅使用 HTTPS；下载先写 .part，依次验证长度、SHA-256、实际包名/版本和当前安装证书，再激活安装文件。安装前再次验证，并使用受限 FileProvider 启动系统安装；Android 8+ 先处理“允许来自此来源的应用”。

forceUpdate=true 时不提供跳过，并拦截返回；false 时可以稍后更新。检查或下载失败时提示并继续当前应用，保持旧启动策略。系统安装仍由 Android/设备策略确认，不静默安装。

原 Web dist.zip 热更新不再承载原生 UI，因此没有迁移这条热更新执行逻辑。已有旧 Web 离线文件和数据未清理；覆盖安装通过不等于完成业务离线数据或旧登录状态迁移，新原生会话仍需登录建立。

## 验证证据

- [覆盖安装](../verification/update-compatibility/overwrite-install.json)：dev 135→136、demo 136→137、线上旧 prod 132→135，UID 与私有验证文件保留。
- [发布构建](../verification/update-compatibility/publish-build.txt)：三环境发布 Debug 与 prod Release。
- [产物准备](../verification/update-compatibility/artifact-preparation.txt)：读取真实线上基线，产物协议与双证书检查通过。
- [最终自动化检查](../verification/update-compatibility/final-checks.txt)：36 JVM 测试、9 设备测试；HTTPS 服务夹具真实传输设备 APK，并拒绝损坏内容。
- [安全门槛](../verification/update-compatibility/safety-gates.txt)：本地 Debug 拒绝发布，缺失原签名时发布构建拒绝执行。
- [检查汇总](../verification/update-compatibility/final-results.json)：测试计数与 Lint 结果；[dev 覆盖后启动](../verification/update-compatibility/native-dev-after-update.png)、[prod 清单不可用后进入应用](../verification/update-compatibility/native-prod-after-update.png)。

HTTPS 设备测试的“已安装旧版本”采用版本快照夹具，APK 和证书解析使用真实设备文件。实际系统覆盖安装由上述三个环境的独立安装验证证明。没有把夹具下载测试描述为已完成线上 OTA 发布。
