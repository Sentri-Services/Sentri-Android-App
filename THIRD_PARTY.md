# 本地设备 SDK

`app/libs/reader.jar` 原样来自只读参考工程 `../Sentri-Android/app/libs/reader.jar`。

- SHA-256：`377b8ec0cb853139ab5cc3b5f5de33967ed47ad7447dd3fe84ee51853557bb9e`。
- Java 包：`com.gg.reader.api` 等，保持原二进制，不修改 vendor 类。
- 本轮使用 USB HID 枚举、GClient、EPC 单次盘存与停止/关闭；不接入其他串口、蓝牙或写标签能力。
- 原归档未发现 LICENSE/NOTICE 条目，因此不将其声明为开源组件。本项目沿用原应用已有 SDK，后续升级应从供应商取得对应分发材料并重新验证硬件。
- Android 授权及断开监听由本项目代码管理，避免使用旧 SDK 内部 flags=0 的 PendingIntent 授权路径。

其余 Maven 依赖使用各组件自身许可。此文件仅记录迁移的本地二进制来源，不存放签名材料或账号配置。
