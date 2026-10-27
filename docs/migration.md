# 原生业务迁移清单

2026-10-09。此文件是业务范围和待确认问题的单一清单；当前全业务非 UI 执行状态见 [business-foundations spec](../.scratch/business-foundations/spec.md)。前阶段基础读取状态见 [pig-read spec](../.scratch/pig-read/spec.md)，验证见 [结果](../verification/pig-read/results.json)。正式 UI 等待产品规范。旧工程保持只读。

## 上下文与验收约定

旧 Web 入口以 `../Sentri/mobile_webapp/src/config/route.ts` 为准，接口以 `src/services/` 的非生成 operations 为准，离线行为以 `src/db/` 和 `src/lib/offline/` 为准。不要把路由存在视为后端功能已完成。

业务共用 Logto 资源 JWT、后端用户、选中厂区。`services/user.ts` 的 GetMe 返回 role/access_modules；本次检查的 router、layout 和首页未发现按 access_modules 统一拦截路由的实现。因此不自造模块权限名称或前端授权规则；继续由 JWT 与服务端权限约束，逐项迁移时核对具体操作。原生会话与只读快照按 environment/oauth/user/farm 隔离；旧 Web 同一个 `sentri` 数据库部分表只按实体 ID 存储，不能直接照搬其隔离策略。

下表中“尚未迁移”指原生业务页面及写入未实现，并非旧业务缺失。验收每个模块时，应具备真实读取、明确写入结果、上下文隔离、适用的离线/设备验证及产品提供的 UI。所有写入都先核实幂等和回执条件。

## 模块与依赖

| 模块及入口 | 核心接口／数据与源码 | 离线和设备依赖 | 原生状态、顺序与验收重点 |
| --- | --- | --- | --- |
| 登录 `/user/login`, `/user/callback`, `/user/farmSelection` | services/user.ts：GetFarmSelectionList、ChooseFarm、GetMe、GetFarmInfo；旧登录 SDK | 会话、刷新、WebView；厂区变化影响所有缓存/命令 | 已接入；持续回归登录、退出、刷新、重启、权限拒绝 |
| 首页 `/`，单元/栏位 | services/location.ts：GetFarmUnitList、GetFarmUnitPenList、GetSectionList、GetZoneList；db/location.ts；首页 task hooks | 基础数据离线读取，任务聚合依赖后续模块 | 单元/栏位读链路已实现；首页任务聚合随后迁移。验收厂区及单元关系正确 |
| 猪只 `/pigs`, `/pigs/boar-list`, `/pigs/newDetail/:id` | services/pig/{fullListQueries,singleQueries,fullDetailQueries,referenceQueries,mutations}.ts；猪只、位置、健康、任务 | db/pig.ts；耳号扫码、健康图片；ReportPigDeath、属性/角色/健康修改等写入 | 本阶段基础列表/详情已实现，公猪专用列表、完整历史/任务及写入尚未迁移。验收耳号/耳缺大小写、单元正则、分页与精度 |
| 生产 `/task/{cq,pz,cfq,yj,fm,piglet,postpartum,lactation,nursery}/:id` 及除 nursery 外的 endTask | services/task/{productionQueries,productionMutations,productionCareMutations,reEstrusQuery}.ts；CheckEstrus、SubmitEstrusStatus、MateWithBoar/Semen、复查/孕检、分娩/寄养、仔猪处理、产后/哺乳、结束任务 | db/task/production；production 类命令、任务/猪只依赖、精液引用、扫码、死亡附件 | 尚未迁移；基础读之后优先选择一个具体操作（建议 CheckEstrus）验证回执与去重，再扩展。验收检测→提交→配种及结束任务阻塞 |
| 医嘱 `/task/advice/:id`, `/prescription/:id` | services/prescription*.ts：GetTherapyList/Detail、CompleteTherapyTarget、SkipTherapyTask、TerminateTherapy/Prescription、Add/Remove target | db/task/advice；therapy/target 顺序与批量执行；pig 上下文 | 尚未迁移；依赖猪只与任务基础。验收目标状态、取消/终止及后续命令阻塞 |
| 疫苗 `/task/vaccine/:unitId` | services/vaccine.ts：GetVaccinationTaskList/Detail、ExecuteVaccinationTask、FinishVaccinationTask | db/task/vaccination；执行目标→完成任务 | 尚未迁移；可在单操作离线框架验证后接入。验收目标去重、时间及完成条件 |
| 采样 `/task/sampling` | services/task/sampling{Queries,Mutations}.ts：GetSamplingTaskList/Detail、SubmitSampled；猪只/环境/人员/物资目标 | db/task/sampling；sampling 命令；首页采样仅展示近七天规则在 pages/home/index.tsx | 非 UI 基础已接入：提交 payload、五类目标读取、全量/首页窗口快照、预览与双语；真实后端读写和正式 UI 未验收。完整列表不套首页窗口筛选 |
| 巡检 `/inspection` | services/inspection.ts、pig/mutations.ts：GetUnitInspection、设备报障/解决、SubmitUnitInspection/Remark、健康/体重/背膘/体温/盘点 | db/task/inspection；设备→解决依赖、健康事件；pickSingleImage、死亡图片上传 | 设备、单元、健康六写及图片上传、体重/背膘/体温三写和当前测量读取、栏位盘点一写一读的非 UI 基础已接；后端未保存巡检 cleanliness/compliance，健康入口未传 image_urls。验收真实读取反映与图片关联 |
| 转舍 `/transfer/{detail,unitList,outDetail,inDetail,pigsOverView}/:id` | services/transfer.ts：GetProductionTransferTaskView/Detail、PigTransferIn/Out、Add/Remove/ResetTransferTaskItems、TransferPig、CompleteTransferTask | db/task/transfer；两侧单位、猪只及任务依赖；键盘扫码 | 尚未迁移；依赖位置/猪只、离线队列和真实扫码。验收出/入场顺序与本地位置更新一致 |
| 出栏 `/task/pigOutbound/*`, `/pigOutbound/loading,loadingRecord` | services/outbound.ts：ProductionOutboundTaskView/Detail、LoadingTicketView、Log/UndoOutboundPig、非个体数量、CompleteOutboundTask、BatchCreateLoadingTickets | db/task/outbound；outbound 类命令；键盘扫码、装车票；一条命令可能含多个 mutation/补偿 | 尚未迁移；在基础离线操作之后。验收部分成功、数量/个体日志、撤销和装车完成 |
| 精液 `/task/semen/{collection,quality,dilution,history,inventory,outbound}` | services/semen*.ts：Batch/Inventory/Storage/History；AddSemenCollection、质量/稀释、AddStorage、Labeling、ManualInventory、Move/Adjust/Discard/Pick | db/task/semen；11 类命令；本地批号/储位→后端引用；配种 scan.startScan | 尚未迁移；依赖持久化命令与引用事务。验收采集回执、批号替换和库存动作顺序 |
| 简化登录/入场采样 `/simple/login`, `/simple/sampling`, `/simple/sampling/success` | simple 页面、CreateEntrySampling、GetCameraList/GetMe；另有简化认证入口 | 摄像设备列表；与主 Logto 流程不同 | 尚未迁移；待产品确认是否保留独立入口，不能自行替换主登录 |
| 消毒 `/disinfection/{disinfect,door,add}` | services/disinfection.ts：物理/逻辑设备、Start/InterruptDisinfection、PullUnlockCode、RetrieveDisinfectedItems | 实际设备与解锁动作；未在现有八类离线命令中登记 | 尚未迁移；在线专门验证，不能统一离线重放解锁/启动命令 |
| 物资 `/materials/{buy,verify,selectRoom,selectWay,selectMaterialsByWay,disinfect}` | 目前页面含静态数据、局部 state 和 TODO confirm；未找到这些页面的实际 GraphQL 提交 | 后续可能依赖设备；当前源码不足以证明完整业务规则 | 待产品补充契约；不将原静态原型复制成完成的业务 |
| 打卡 `/clockIn` | pages/home/clockIn.tsx：TODO 打卡逻辑，提交仅回首页，时间为固定展示值 | 当前无已确认后端调用；不能凭名称假设定位业务 | 待产品确认，优先级低于已实现接口的业务 |
| 工具箱/离线记录 `/toolbox,/offlineRecords` | offlineRecords 组件、commandQueue/syncCoordinator、错误/依赖/取消/记录导航 | 全部离线类别、快照、同步记录 | 后续实现可解释的队列 UI；本轮先承接调度规则模型，不启动线上写重放 |
| 开发入口 `/offlineNetworkTest,/component-gallery` | 网络故障模拟、组件展示 | 只用于开发，不属于真实业务 | 原生仅保留明确标识的验证入口，不迁移测试注入 |
| 文档/下载 `/doc/cq,/app/download` | 文档页面、utils 下载逻辑；旧 Android DownloadNative | 文件/PDF、下载、APK 更新 | APK 更新已接入；普通下载/文档阅读待实际需求。发布保持原身份/签名/版本/OSS |

## 旧离线数据的保留与迁移预案

旧 `db/index.ts` 使用 Dexie `super('sentri')`、schema 25：用户、me、位置、猪只、各任务表、offlineCommands、offlineCommandLogs、offlineEntitySnapshots、精液批次/库存/储位等。迁移必须在原 WebView 的同源页面上下文读取 IndexedDB；新 `.local` 应用沙箱不能直接读取旧正式应用私有数据。生产同 ID 覆盖安装仍需保留旧 WebView 数据目录及 origin；现有代码没有清理旧 Web 数据。

拟定路径（本轮不执行）：旧同源页面导出带格式版本、原 origin、环境、后端用户/厂区、表计数和内容摘要的文件；不导出会话/令牌。先备份，再在原生导入暂存区做 schema、字段、日期/精度、主键、归属和命令依赖/本地引用校验。无归属或歧义行隔离，不能默认归给当前登录人。一个作用域的数据及命令/引用在同一事务提交；导入 ID/摘要防重复导入。导入后只读对比数量、关系和样本，业务写入仍等待服务端结果核实。失败回滚原生暂存/事务并可重新导入；旧 IndexedDB 保留至另行明确批准清理。

## 已发现问题及决定

- useScanSn.ts 的确认连接回调 3 秒后注入固定耳号 A00031；被实际扫码 popup 使用。这是测试注入，原生排除。真实键盘输入来自聚焦 input 的 Enter，大小写及空白不能擅自归一化。
- pigs/list/index.tsx 分组同时保留原数组及无标识单独分组，存在重复展示疑点；原生缓存/列表保持唯一猪只 ID，正式分组展示待产品核对。
- 同一 IndexedDB 全局命令表、syncCoordinator 读取全部命令但未按当前 user/farm 过滤。原生规则明确强制 scope 过滤，不能把旧行为当作允许跨账号同步的业务规则。
- 旧错误分类依据“duplicate request/already submitted”等文本推断 idempotentSuccess；仅错误文本不足以证明本次命令已经正确执行。原生需要具体操作的回执或查询确认。
- 简化登录、物资、打卡和正式页面分组属于产品待确认项；不阻塞本阶段只读链路与设备技术验证。

离线具体契约和设备调查继续记录在 [前置准备](offline-devices.md)。本阶段尚未完成全量迁移；下一批任务按上表依赖逐个建立验收条件。
