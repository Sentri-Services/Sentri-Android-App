# 登录页输入光标回跳

2026-10-26。用户报告真机和模拟器都能复现：每输入一个字符，光标回到开头，后续内容倒序；浏览器正常。

## 原因与修复

`LoginWebView` 原先把 WebView 直接作为 Compose `AndroidView` 的根 View。对同一个真实 Logto 登录页做对照：

| 宿主／输入方式 | 修改前 | 修复后 |
| --- | --- | --- |
| Compose 直接托管 WebView，IME commitText | 第 1 个字符后 length=1、selectionStart=0，失败 | 1–5 字符位置正确 |
| Compose 直接托管 WebView，硬件按键 | 同样回到 0，失败 | 顺序和位置正确 |
| 原生 Activity 直接托管 WebView | 通过 | 通过 |
| 无脚本本地 HTML 输入框，Compose／原生 | 均通过 | 均通过 |

证据把问题定位到 **Compose 直接托管 WebView 与登录页输入的焦点／选区交互**；没有据此宣称所有 HTML 或某个 WebView 版本都会失败。测试设备为模拟器 API 36／WebView 133；连接的小米是 WebView 120，自动复核状态见下方。

修复只调整原生宿主：`LoginWebView.kt` 用 `FrameLayout` 包含 WebView，`FOCUS_AFTER_DESCENDANTS` 将网页编辑焦点交给原生子视图；Compose 对接容器，而不直接对接网页编辑视图。原有 PKCE、回调地址／域名白名单、Cookie 策略、会话存储、返回和取消路径均未更改。

未向登录页注入“输入后强制移到末尾”的脚本；允许正常把光标放到中间。回归覆盖中间插入、删除和组合输入，防止用固定光标位置掩盖问题。

## 可复核命令

只跑本地无脚本输入框，不访问认证服务：

```bash
adb -s emulator-5554 shell am instrument -w \
  -e class 'com.sentri.nativeapp.LoginInputCursorTest#localComposeHostPreservesCaretWithoutPageScripts,com.sentri.nativeapp.LoginInputCursorTest#localNativeHostPreservesCaretWithoutPageScripts' \
  com.sentri.jingan.dev.local.test/androidx.test.runner.AndroidJUnitRunner
```

对真实 dev 登录页复核：

```bash
adb -s emulator-5554 shell am instrument -w \
  -e class com.sentri.nativeapp.LoginInputCursorTest -e liveLoginInput true \
  com.sentri.jingan.dev.local.test/androidx.test.runner.AndroidJUnitRunner
```

测试只输入固定探针数字，不输入账号密码、不点登录、不交换授权码、不写 AuthRepository 的会话或待登录数据、不退出已有账号。真实页探针必须显式开启，默认跳过。日志仅记录宿主／输入方式／字段类型，不输出 URL、凭证或实际用户输入。

## 当前证据与限制

模拟器修改前 3 项真实页对照：Compose 的 IME／按键两项失败，原生通过；最小化的两项本地 HTML 通过。修复后 5 项全部通过；最后增加中间插入、删除、组合输入的同一批 5 项也通过，同时复核了两项输入组件，总计最后定向 7 项通过。完整 `scripts/verify.sh` 通过，278 JVM，Lint 无错误。

小米 `wg8hobu4euofxkuc`（WebView 120）的业务 APK 已通过 `adb install -r` 更新；安装后的 base.apk SHA-256 与交付包完全相同。用户已手动安装 `Sentri-Test-Helper.apk`，包路径和签名核对成功。最终真实登录页的 Compose／原生 IME 两项对照通过（`OK (2 tests)`，7.37 秒），每项覆盖连续输入、中间插入、删除和组合输入。测试只使用固定探针数字，没有提交登录。随后恢复业务 MainActivity，UI 树确认 Home 可见、登录入口不可见、无系统确认弹窗，沿用原登录会话。

真机测试首次停在 `Instrumentation.startActivitySync`／`ActivityScenario.launchInternal`，尚未进入测试方法；当时手机锁屏息屏，用户解锁后该等待仍未结束，因此不能把锁屏认定为全部原因。改用不依赖 ActivityScenarioRule 的原始 WebView 探针，通过标准 shell 命令启动导出的 debug ComponentActivity，再使用同一个生产 `LoginWebView` 和相同选区断言；这只修改辅助包，不修改业务 App 或认证逻辑。辅助包已正常覆盖更新，未更改手机安全设置。新驱动在模拟器复跑全部 5 项通过（17.175 秒）。没有卸载业务 App、清数据或退出账号。

本轮只改测试驱动和验证记录，未重复完整构建／业务回归；业务 APK 保持此前 `verify.sh` 通过并已安装的版本。

尚未覆盖：真机百度输入法实际候选交互、密码可见性切换／自动填充、全套登录往返与其他 WebView／Android 版本。本次无需重新登录来复现输入问题，后续亦优先沿用会话。

## 本地交付

- `app/build/outputs/apk/dev/debug/Sentri-Dev-2026-10-26.apk`
- 包名 `com.sentri.jingan.dev.local`，版本 `1.3.6`／136，本地 dev 签名。
- SHA-256：`403f5678e6c36545bb8c289523c9733e11f5ebfc4789d76c2ca962d213f75c28`。

这是本地测试包，未推送代码、未发布到远端通道。
