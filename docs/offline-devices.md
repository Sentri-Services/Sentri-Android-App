# 离线命令与设备前置准备

2026-10-09。本文件记录源码契约、已实现前置能力和验证边界。业务顺序见 [迁移清单](migration.md)；线上业务写同步与真实硬件验证不在已完成声明中。

## 旧命令契约

证据目录：`../Sentri/mobile_webapp/src/lib/offline/`；类型在 types.ts，入队在 commandQueue.ts，执行在 syncCoordinator.ts，持久化在 `src/db/index.ts`。

- 八类：production、advice、inspection、vaccination、sampling、transfer、outbound、semen。每个 handler 按 operationName 注册并校验 payload kind；commandInput 与 display 分开。
- id 默认 UUID，operationId 默认 id，可单独指定；operationId 用于本地等待结果及通知。payload.environment 包含 farmId/userId/deviceId/version/network/route，但旧字段允许缺失。原生必须使用已确认的环境、OAuth 用户、后端用户、厂区作用域，不能猜测缺失归属。
- 状态：queued/synced/failed/conflicted/cancelled；依赖通过 synced 或 cancelled 被解析。缺失、failed、conflicted 依赖阻塞；queued 依赖等待。groupKeys 优先于 groupKey，缺省 command:id；共享实体组按创建时间串行。同时间 CheckEstrus 先于 SubmitEstrusStatus，发情命令先于配种，再比较 ID。
- syncCoordinator 在网络 good 时处理，默认 maxParallelCommands=1。失败/冲突或未解决依赖阻塞后继及共享组；认证失败暂停本轮。重试等待 5s/15s/60s/300s，300s 后维持上限。批量手动忽略依赖和重试延迟属于旧工具能力，原生本轮不提供绕过开关。
- 同步状态由内存 syncing 锁管理；schema 25 将旧 syncing 行改回 queued。网络错误/超时被分类为 retry，再写 queued。请求送达后丢失回执或进程死亡，因此有重复执行风险，不能照搬成已证明可靠的恢复机制。
- 旧同步从全表读取命令，未见对当前账号/厂区的过滤；认证只是请求时的当前 JWT。切换上下文后可能用新权限发送旧命令。原生必须每次调度和提交前检查作用域，切换/退出时停止提交；已提交且无回执的操作保持结果待确认。

## 去重核查和具体操作

已检查一个具体操作 CheckEstrus：

1. 旧 estrusCommandHandler.ts 校验猪只/task/check_status/check_at，以 `Idempotency-Key: command.id` 调用 CheckEstrus；并非传 operationId。operationId 与 id 不同时不能假定它们代表同一个服务端请求。
2. 旧 services/task/productionMutations.ts 的输入没有 operationId；后端 `../Sentri/backend/apps/production_v2/src/production_v2/entrypoints/graphql/production_task.py` 的 check_estrus 构造 CheckEstrusPigCommand，未从 info/request 读取该头。
3. common.py 的 _execute_graphql_command 进行时区转换后调用 handler；`backend/libs/service-runtime/src/service_runtime/commands/executor.py` 每次生成新 command_id，没有按客户端幂等键查持久化结果。
4. handlers/commands/production_tasks/estrus_mating.py 的 check_estrus_pig 加载任务、调用 check、持久化、提交。没有发现按请求 ID 去重的步骤。搜索 backend 与 hasura 的 Idempotency-Key/idempotency_key 未发现接入实现；其他领域的自然幂等或事件 inbox 去重不能证明该请求只执行一次。

这证明当前可见源码路径不支持“仅加这个头即可安全重放”的结论；无法据此证明已部署 dev 网关不存在额外去重。本轮未发送真实业务 mutation，也没有重复提交测试。后续需服务端明确持久化去重的作用域、有效期、相同 key 不同 payload 的行为和成功/失败回执，或为此操作提供可查询的结果确认。然后才验证首次提交、重复请求、响应丢失和重启恢复，并扩展其他写操作。

semenCommandHandler.ts 同样用 command.id 作头；outbound 一条命令包含多次写入，identity-log/identity-undo 等用不同后缀，必须逐子操作记录结果。错误分类仅凭“already submitted/duplicate request”文本转 synced 不足以确认当前业务目标完成。

## 本地引用及迁移规则

semenLocalReferences.ts 生成本地批号/储位引用。semenCommandReferenceResolver.ts 根据来源命令的 resolvedSemenBatchNo/resolvedStorageLocationId 替换 JSON 的精确字符串，并更新显示、group/entity/task 和本地库存/批次。依赖来源回执缺失时返回 undefined；本地批次替换和所有命令更新未包在统一事务，存在成功提交后引用部分更新的恢复问题。

原生正式承接时应在一个作用域内，事务保存来源回执、local→remote 映射和受影响记录；保留来源命令，所有引用解析只使用确认结果，未解析的后继保持阻塞。导入旧数据时同时验证依赖图、来源命令和批号/储位引用，不通过字符串相似性猜测映射。旧数据保留方案在迁移清单中，本轮不导入或清理。

## 本轮原生规则实现

`offline/CommandPolicy.kt` 已实现独立可测试的元数据/调度规则：作用域与认证/网络门禁、显式依赖、实体组顺序、延迟、开始/回执/取消，以及在进程恢复时将 IN_FLIGHT 保留为 UNCERTAIN。只有已确认结果或具体去重机制才允许调用 retryConfirmed；它没有 HTTP 发送器，也未连接 GraphQL mutation。

这部分是后续单操作实现可复用的规则，并非完整队列：业务 payload、本地引用事务、命令持久化、结果查询及同步记录 UI 尚未接入。CommandPolicyTest 覆盖缺失/跨作用域依赖、取消/成功解阻塞、失败冲突顺序、同时间检测/提交/配种、认证/网络/延迟门禁和不明确结果不能直接重放。

## 键盘扫码

证据：旧 useScanSn.ts 检查蓝牙名称 Scanner KBD，否则检查 externalKeyboard；聚焦 input，Enter 提交原始字符串；轮询连接 500ms。confirm 回调在 3 秒后写入 A00031，是真实调用路径中的测试注入；新业务路径不会引入它。转舍与出栏的扫码组件使用该 hook。

`devices/KeyboardScanSession.kt` 实现明确选中 deviceId 的 Enter 分帧：只接收该设备的 key-down、忽略 key-up/重复键，保留大小写和空白、限定长度/超时。默认单读模式成功、取消或断开后清空；巡检连续模式在每次 Enter 后清空当前帧并继续监听，停止或断开才释放设备。AndroidScannerInput 接入 InputManager 连接/变化/断开事件与实际 KeyEvent 解码；Android 10 起筛选 external 且非 virtual 键盘，API 24–28 没有 isExternal，展示非虚拟键盘候选，必须由人选择实际扫码设备，不能假定内置键盘是扫码枪。

登录后的“扫码与 RFID 验证”入口已接入：选择设备开始、10 秒超时、结果、取消；离开页面/应用后台时取消并释放。DeviceInputTest 在 API 36 用明确标识为模拟的 Android KeyEvent 验证解码及 Enter 分帧；普通文本编辑和虚拟测试输入没有进入真实设备选择路径。最后仍需真实扫码枪验证布局、连续扫描、断开和重连。

## USB HID RFID

旧 `RFIDNative.java` 使用 app/libs/reader.jar 的 GClient；取第一个 AndroidUsbHidClient，连接回调后发送 AntennaNo_1、InventoryMode_Single，读取 result==0 的 EPC 字符串。JsBridgeManager 对 RFID 走单例特例；Web sdk.ts 暴露 scanTag，但当前 Web 源码中没有找到它的真实业务调用方。配种精液弹窗使用的是 scan.startScan，旧 Bridge 未注册 scan，不能将两条路径混为同一种设备。

旧 RFID 实现的问题：共享可覆盖 callback、成功后不停止、超时不取消 Future、Future 的截止时间与线程 sleep 使用同一超时、没有明确断开/关闭/注销生命周期，可能先成功后超时或旧回调进入新请求。EPC 被旧日志完整打印，原生不复制该日志行为。旧项目没有已使用的 RFID 写标签流程，因此本轮仅准备读取，写入协议需要实际业务证据。

通过 javap 直接检查旧 reader.jar：GClient 有 openAndroidUsbHid/sendSynMsg/close；AndroidUsbHidClient 有 getUsbHidList/getUsbDevice、deviceListener/stateListener、dispose、registerUsbState/unregisterState 等。接入应在后台执行同步 SDK 调用，使用明确选择的 USB 设备与权限结果，停止盘存后关闭连接、释放并注销。多设备默认第一个的旧技术实现不构成业务要求。

`devices/RfidReadController.kt` 和 RfidTransport 已实现单读生命周期及回调归属：每次开始独立 generation 和超时；SDK 回调切回 owner dispatcher；首次结果立即停止；重复/迟到/旧超时忽略；取消、断开、失败及关闭清理。DeviceReadTest 使用模拟 transport 验证这些规则。

AndroidRfidTransport 已原生接入同一 reader.jar，按明确设备 ID 枚举选择，Android 自己管理权限 PendingIntent 和非导出广播 receiver，再让 SDK 打开已授权设备。SDK 的 hasPermission 字节码显示其旧授权使用 flags=0，故不主动进入该路径；实际断开与授权之间的设备竞态仍要通过实物确认。阻塞 SDK 调用在 IO dispatcher 的 mutex 中串行执行；停止撤销当前请求 generation，清除回调、发 stop、close/dispose 并注销自己的 receiver。没有调用旧 SDK registerUsbState。权限结果只以 UsbManager.hasPermission 为依据，取消/回执后的 receiver 与 PendingIntent 在 finally 清理。

最小 Compose 入口可枚举、选择、开始、停止和显示单次 EPC；没有提交猪只业务或对 EPC 自行转换耳号。DeviceInputTest 在 API 36 证明归档的 GClient 可加载、无设备枚举为空、缺失设备失败、worker 关闭完成。模拟器验证入口显示无设备。它不证明实际 USB 授权、盘存和读取成功。

Android 接入参考：[USB Host](https://developer.android.com/develop/connectivity/usb/host)、[PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)。SDK 方法与其旧实现则由原归档及 javap 检查确认，归档哈希见 THIRD_PARTY.md。

当前未连接真实扫码枪或 USB RFID 读写器。JVM 事件测试不证明物理连接、USB 授权、设备协议、天线/射频或真实读取成功。

## 其他设备能力

| 能力 | 旧证据 | 本阶段决定 |
| --- | --- | --- |
| 相机/选图/Base64 | MediaNative.java；sdk.ts pickSingleImage/captureImage；巡检 useHealthImageUpload | 本轮只读链路不需要，后续迁移 Android 结果回调、URI/权限、压缩和上传；不先复制 Web Base64 管线 |
| 定位 | LocationNative.java；sdk.ts start/stop | 当前检索未找到对应业务调用，先保留清单，依赖明确后处理权限与生命周期 |
| 文件/下载/PDF | DownloadNative.java、utils/index.ts 下载调用、/doc/cq | APK 更新已完成；普通文档下载按需求后续实现 |
| 推送 | MiPushMessageReceiver.java、旧 MiPush SDK/Manifest | 本轮无读取链路依赖；注册、点击路由、后台行为后续按实际服务端契约验证 |
| 企业/串口能力 | EnterpriseSDK.jar、旧 serial so、宿主系统调用 | 不将所有旧 SDK 无差别引入新应用；实际使用时迁移并验证 |
