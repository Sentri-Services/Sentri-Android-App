# 本阶段验收核对

范围：用户持续目标的 A（迁移清单）、B（原生基础数据/猪只读链路）、C（离线/设备前置梳理及适当验证）。正式 UI、全部写操作、正式旧数据切换不是本阶段已实现范围。真实硬件按目标中的缺少设备条款记录未验证，不伪装为通过。

| 目标条目 | 实际证据与结论 |
| --- | --- |
| 1：复用已有基础、参考项目只读 | 原 auth、Apollo、更新入口继续使用；旧 Android commit ee0f6c3、Sentri commit 4c1a2ff，最终两个工作区 status 为空。没有改旧业务源码；完整 commit、SDK 哈希、发布配置与敏感字面量检查见 provenance.json |
| 2–3：A 迁移清单 | docs/migration.md 按真实 route.ts 覆盖业务及开发入口，列出 services/operations、核心数据、上下文/权限、离线/设备依赖、当前能力、顺序、验收与待确认项；.scratch/pig-read/spec.md 为本阶段任务状态 |
| 4：B 基础数据、列表、条件、分页、详情 | Pigs.graphql 的三个 Native operations；GraphqlPigRemote 的网络→业务转换；LivePigReadTest 真实 dev：3 单元、11 栏位，所选单元 200 条记录，列表/搜索/offset/详情/栏位条件（样本具备位置时）成功。没有全量搬迁旧生产任务历史 |
| 4：旧规则与权限 | 旧单元 _iregex、耳号/耳缺 JS includes、ID 稳定顺序的源码证据；Native where 显式 farm_id，响应归属验证；旧 Hasura metadata 的 JWT farm 过滤；PigContractTest/PigRemoteTest 的 SQL 转义、请求头、类型和跨厂区返回拒绝 |
| 4：scalar 与模型 | Decimal/日期/JSON adapters、业务 DTO、独立 SnapshotCodec；精度、时区、日历、嵌套、非法网络值及缓存值测试，避免解析异常走 Network 回退 |
| 4：可操作状态与默认 UI | PigScreen + PigViewModel；页面具备空单元/空列表、正常数据及离线详情入口；PigViewModelTest 的 loading、空页、新旧筛选竞争、分页失败保留前页/正确 offset 重试、详情失败/重试/未找到、恢复和退出；未定义设计系统 |
| 5：环境/用户/厂区及参数隔离 | ScopeGate、Auth contextRevision、GraphqlExecutor；PigContractTest 的刷新/切换/退出 lease；PigCacheTest 的环境/OAuth/用户/厂区/筛选/分页隔离、迟到厂区请求不写库；PigViewModelTest 迟到筛选及退出不覆盖；AuthRepositoryTest 等待 mutex 后上下文保护及相同 Token 文本的新登录保护 |
| 5：事务、升级、清理、保留与失效 | SQLiteOpenHelper schema 2；插入失败回滚、v1→v2 保留、坏 JSON/格式失败、只清当前作用域、单 OAuth/farm 绑定避免复活旧账户、同时间戳容量裁剪保留最新和其他作用域/元数据，均在 PigCacheTest 的 Android 运行环境执行（API 36 模拟器） |
| 5：网络失败/认证失败区分 | 仅 Network/AuthUnavailable 可回退；Business/Protocol/明确 Authentication 不回退；已有认证测试覆盖刷新与失效，缓存测试覆盖权限拒绝；账户缓存 UI 不宣称 GraphQL 在线成功 |
| 5：真实断网与进程重启 | live-offline.txt：关闭模拟器活动网络、force-stop 后新进程；ConnectivityManager.activeNetwork=null 断言；已有会话/账户/浏览恢复，正常网络模式查询回退缓存。offline-ui.json：再次重启主 Activity，账户/详情/缓存时间/刷新失败提示全部断言通过。之后网络恢复 |
| 5：旧 IndexedDB 保留 | 新独立 SQLite 库；没有旧 Web 数据清理代码；迁移清单记录 Dexie schema 25、同 ID 沙箱/origin、导出/导入暂存、归属、引用、摘要、防重复、事务校验/回滚路径；未执行正式导入/切换 |
| 6：离线契约与具体去重调查 | docs/offline-devices.md 对照 types/queue/coordinator/dependencies/errorClassifier/semen 引用源码；具体 CheckEstrus 前端发送 command.id 头而非 operationId，后端构造命令和 executor 无客户端键去重证据；部署网关的额外能力未确认。没有连接自动写重放 |
| 6：适当核心验证 | CommandPolicy + 5 JVM 测试：具体 CheckEstrus→Submit→mating 顺序、依赖/组阻塞、作用域/认证/网络/延迟、开始/确认/取消/结果不明确；IN_FLIGHT 恢复为 UNCERTAIN，不能无确认直接重放。未宣称完成真实业务队列、payload、持久化和回执查询 |
| 7：两种设备分开处理 | AndroidScannerInput/KeyboardScanSession 与 AndroidRfidTransport/RfidReadController 分别接入；明确 deviceId 与原始字符串/EPC；连接/断开、开始/停止、取消、超时、重复/迟到、后台/关闭处理；DeviceVerificationScreen 可操作，无业务提交 |
| 7：排除测试注入 | 原 useScanSn.ts 的 3 秒 A00031 注入已记录；新 main 业务代码没有该注入或延迟自动输入。扫码由选择设备的真实 KeyEvent 产生；注入事件只在 Android 测试中验证解码 |
| 7：设备验证边界 | 4 个 JVM 设备规则测试；DeviceInputTest 的 2 个 API 36 测试证明 Android KeyEvent 解码、原 SDK 可加载、无设备失败与 worker 关闭；device-ui.json 验证无设备入口。没有连接扫码枪/RFID 实物，不宣称授权、射频、连续读或设备断开/重连真实通过 |
| 7：其他能力 | 相机/选图、定位、下载/PDF、推送、企业/串口来源及依赖已列入清单。只读链路无这些新依赖，因此未无差别迁移旧 SDK |
| 8：架构/技能/敏感信息 | 单 app 模块、构造注入、实际第二个调用方提取执行器；未新增通用领域/模块框架；Skills 38 个完整性通过，未强制运行流程；日志只有事件/计数，私密值仅读取旧条件进入内存，字面量检查无匹配，SDK 来源及哈希单独记录 |
| 9：发布与兼容 | .github、scripts、versions.properties 无 diff；三个 local Debug + 本地 Dev Release 构建通过，原签名、com.sentri.jingan.dev、136/1.3.6 验证；没有执行 releaseDev/Demo/Prod/All、Tag、上传、OSS 指针修改或版本递增。GitHub 只读复核仍缺 ALIYUN 四项 |
| 10：回归与交付 | verify.sh、60 JVM、22 常规 Android（另外两个真实环境测试默认跳过）；真实 online/offline 各显式执行一个；登录通过真实 WebView、选厂刷新/查询；加密会话/OIDC/更新契约及下载均在常规测试中通过；logout-ui.json 证明真实退出撤销、私有 session/pending 清理和新进程业务/设备入口不可见。文档、证据、产品问题、后续任务已交付 |

## 验证限制与后续门槛

- 真实后端只验证 dev；demo/prod 只构建，不登录或修改业务。
- 设备运行是 API 36 模拟器；最低 API 24 由编译/Lint 边界检查，其他系统和真实硬件运行尚未执行。
- Release 只在本地做混淆构建、二进制身份和签名检查，没有分发或安装成正式更新。
- 服务端客户端去重未获确认，离线写处理器/队列持久化及正式旧数据导入留到后续明确任务；不能通过错误文本自动确认成功。
- 正式 UI、静态物资/打卡流程、独立 simple 登录是否保留、猪只无标识分组重复展示等产品选择见 migration.md，不阻塞本轮原生读链路。

原始输出和计数见 results.json、jvm-suites.json、device-tests.txt、live-online.txt、live-offline.txt、runtime-events.txt、all-builds.txt、foundation-checks.txt。验证中的解析错误分类、测试网络权限、API 29 输入属性及测试 import 失败均已修复；失败尝试没有作为通过证据计数。
