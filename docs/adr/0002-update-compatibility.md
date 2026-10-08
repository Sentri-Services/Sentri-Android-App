# ADR-0002：沿用旧应用安装身份与 APK 更新链路

2026-10-08，用户明确要求原 key 与更新逻辑一致，保证旧应用正常更新。本决定替代 ADR-0001 中独立发布身份及生产签名待确认的部分。

发布 dev/demo/prod 采用旧 applicationId、原签名证书和企业证书，版本独立递增。普通本地 Debug 加 .local 隔离；发布 Debug 由 enableApkUpdate=true 开启原身份与更新，沿用旧 CI 的 debug OSS 通道、Tag、清单及 last.zip。

原私钥保持在外部安全配置，通过环境变量或忽略提交文件注入。发布准备必须检查双证书与真实线上基线，严格拒绝同版和降级。发布上传顺序仍为 APK/last.zip 在先、manifest 在后。

新客户端保留清单校验、下载完整性、包名/版本/证书校验、强制/可选更新和系统安装。Web dist.zip 更新不用于原生 UI。旧离线数据保留在应用目录，业务迁移仍需独立实现。

真实覆盖安装与当前限制见 [发布说明](../release.md)。Android 更新依据参见 [应用签名](https://developer.android.com/studio/publish/app-signing) 与 [版本管理](https://developer.android.com/studio/publish/versioning)。
