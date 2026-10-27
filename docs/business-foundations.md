# 全业务非 UI 基础

2026-10-10。执行状态唯一来源：[任务表](../.scratch/business-foundations/spec.md)。业务入口、源码和产品缺口见 [迁移清单](migration.md)。这里记录已实现的适配边界，不代表全部业务完成。

## 健康图片与猪只测量（第 13 项进行中）

`NativeUploadHealthyImage` 保存独立图片上传输入及服务端 `file_key`/`file_url` 回执。离线队列可持久化大 data URL，结果不明确时不自动重发。当前健康事件 GraphQL 入口忽略 `image_urls`，因此上传 URL 不能作为已关联健康事件的证据；没有自动把图片回执注入健康命令。

`NativeRecordPigWeights`、`NativeRecordPigBackfat`、`NativeRecordPigTemperature` 对应旧巡检测量接口；`NativePigMeasurements` 按厂区和猪只 ID 读取当前体重、背膘、体温。体重输入区分 null/空逐猪列表和仅总体重，保持逐猪顺序与重复 ID；背膘毫米与等级同时填写时按后端阈值验证一致性；Decimal 与时间保留原文本精度。三种 format-2 payload、严格回执、同猪/总体重依赖及独立测量快照已接入。JVM 契约测试、模拟器 SQLite 重开和 `verify.sh` 通过；未进行真实业务读写或注册自动发送。

## 栏位盘点（第 13 项进行中）

`NativeReconcileLocationInventory` 对应旧 `ReconcileLocationInventory`；输入保留栏位编码、目标数、可空无身份猪批次分配与原因。服务端以现有有耳号/耳缺猪数加无身份猪分配数等于目标数作为约束，可能创建、移除或调整猪只。原生输入检查非负数量、批次 ID 唯一，未伪造当前有身份猪数量。`NativeLocationInventory` 按厂区、栏位和 ACTIVE 状态读取猪只，保留身份/无身份及批次信息，形成隔离快照。离线队列保留 null 与空分配区别，同栏位操作显式依赖；成功回执严格核对栏位编码。服务端没有返回差异或记录 ID，未知结果不自动重发。当前处理器虽接收 `reason`，但领域服务未使用它生成调整记录，不能宣称原因已保存；MockWebServer/SQLite 验证通过，真实后端未验证。

## 设备报障与解决（第 13 项进行中）

`NativeReportDeviceInspection`、`NativeResolveDeviceInspection` 与 `NativeDeviceInspections` 已按旧 `inspection.ts`、`referenceQueries.ts` 和 `deviceInspectionCommandLink.ts` 接入。输入区分 Feed/Water/Other、Fault/Normal、可空设备名/备注/既有巡检 ID；服务端读取保留报告/解决人员与原始时间，按厂区限制并保存独立快照。两个离线 payload 已登记 format 2，同栏同设备类型报障共享顺序；解决命令可保存服务端 ID 或本地报障命令 ID。后者必须引用同作用域已有报障，并在报障成功回执确认后才会在领取时转换为服务端 `recordId`，持久化的原始本地引用不被提前改写。未知报障结果不会放行解决，也不会自动重发。存在未取消解决命令时不能取消其报障命令。

MockWebServer 线协议测试 3 项、设备 SQLite/快照测试 4 项通过；`verify.sh` 完成 204 项 JVM、Lint、GraphQL 契约及中英文校验。未连接真实设备报障后端，也未注册自动队列发送或改 UI。第 13 项其他巡检、健康、测量、盘点和附件继续待做。

## 单元巡检与备注（第 13 项进行中）

`NativeSubmitUnitInspection` / `NativeSubmitUnitRemark` 及按厂区读取的 `NativeUnitInspections` / `NativeUnitRemarks` 已接入。输入保留可选 `unit_inspection_id`、所有环境测量、选择值、空字符串和 null；显式传 ID 时成功回执必须返回同一 ID，未传时保留服务端生成的 ID。离线 payload 保存 BigDecimal 原始字面量与 scale；GraphQL 入参的 `Float` 仍按现有后端契约转为 Double，超出或下溢到零的值在排队前拒绝。读取的 `float8` 走 BigDecimal，时间保留原始时区与精度；日窗口要求调用方给出明确偏移量的完整起始时间。两种命令按单元顺序建立依赖，巡检与备注分别保存作用域快照。

旧 Web 实际提交的照明、通风、清洁度、合规性选项已加入中英文映射，同时保留服务端未来未知字符串。本段原核对时，`SubmitUnitInspectionCommand` handler 没有把 `cleanliness`、`compliance` 传入 `UnitInspection.create`。2026-10-20 复核当前后端源码时已看到两字段传入；部署环境和真实读取反映仍未验证，见[后端调整清单](backend-adjustments.md)。MockWebServer 四项、Android SQLite/快照两项定向测试通过；完整 `verify.sh` 为当批 208 JVM、Lint、70 operations/718 字段和 442 文案/302 映射通过。没有真实业务读写、自动重放或 UI 改动。

## 猪只健康操作与目录（第 13 项进行中）

按旧 `services/pig/mutations.ts` 和巡检 `health.ts`/`healthActions.ts` 接入 Add/RemovePigDisease、Add/RemovePigSymptom、SetPigTriageLevel、SetPigBodyTemperature 六项操作。输入保留疾病/症状 ID 原顺序与重复项、恢复标记、可空耳标/栏位/原因/分诊、空字符串、图片 URL 列表，以及体温 BigDecimal 的原十进制文本和 Celsius/Fahrenheit。六种 format-2 payload、猪只 ID 回执和同猪显式依赖已接入 SQLite；不同猪可独立领取，未知结果不会自动重发。`NativeHealthReferenceCatalog` 保留疾病与症状的中英文名称、缩写、分类/部位和描述，目录有独立作用域快照；猪只当前健康详情继续由已有 `PigProfileReads` 提供。六操作与温度单位加入资源，分诊等级复用已有双语映射。

当前后端 `pig_health.py` 的 AddPigDisease/AddPigSymptom GraphQL 入口没有把 `image_urls` 传入命令，即使前端先调用 UploadHealthyImage 成功并把 URL 放入入参，也不能证明图片已关联健康事件。因此本批保存原始 URL 输入但不宣称附件闭环；上传子命令、回执引用与真实读取反映单独待做。MockWebServer 四项、Android SQLite/目录快照三项定向测试通过，`verify.sh` 为 212 JVM、Lint、77 operations/729 字段与 450 文案/310 映射通过。未执行真实后端读写或注册自动重放。

## 已实现操作

查情：NativeEstrusTask、NativeCheckEstrus、NativeSubmitEstrusStatus；CheckEstrusInput 与 SubmitEstrusInput 保留原始业务 ID/时间。批量提交保留猪 ID 顺序、显式 null submit_at，以及默认 false/显式 true 的 auto_estrus，不将“缺省参数”与“显式 null”混用。依据旧 services/task/productionMutations.ts、lib/offline/estrusCommand{Handler,Adapter}.ts。

配种：NativeMatingTask、NativeMateWithBoar、NativeMateWithSemen；MateWithBoarInput/MateWithSemenInput 与任务、猪只、汇总、记录、配种材料模型。读取限制 task/batch.farm 与猪只 farm，并验证任务类型、记录 task/pig 和材料 task/公猪归属；时间保持原精度。两种输入固定显式 auto_check_estrus=false，与旧 matingCommandHandler.ts 一致，不把旧离线记录的 operated_at 擅自新增为后端参数。依据旧 productionPigFields1.ts、productionMutations.ts 和 matingCommand{Handler,Adapter}.ts。

精液配种当前接收已存在的服务端 semen_id。旧 local-semen-batch:/local-semen-storage: 是精液批次/储位占位符，不能冒充 semen_id；跨模块本地引用映射仍属于任务表第 16 项，当前不将占位符传给后端。公猪列表、精液查询/库存动作和配种的正式 UI 尚未实现。中英文 operation 文案接在 BusinessEnumKeys 的 operation 分组，其余业务文案随各项实现。

复情：NativeReEstrusTask、NativeCheckReEstrusPig、NativeSubmitReEstrusStatus；CheckReEstrusInput / SubmitReEstrusInput。结果为 NOT_RE_ESTRUS / RE_ESTRUS / SIGNS_OF_RE_ESTRUS，任务读取保留检查、配种、提交时间、操作者 ID、是否返情、位置与 disabled。

孕检：NativePregnancyTask、NativeCheckPregnancyPig、NativeSubmitPregnancyStatus；CheckPregnancyInput / SubmitPregnancyInput。结果为 NOT_PREGNANT / NOT_SURE / PREGNANT；re_check_days 使用可空 Int，null 与 0 分开保存/发送，不接受小数、字符串或超出 Int 的持久化值。没有自行限制源码未定义的天数范围，也没有新增后端不存在的检查方式字段。服务端 is_pregnant 的 null 与 false 保持不同。

依据旧 reEstrus/pregnancyCommand{Handler,Adapter,Dependencies}.ts、db/task/production/{reEstrus,pregnancy}.ts 与 productionMutations.ts。unitCode 是旧页面的原始单元选择器，空字符串合法，保存于 payload 供同单元轮次协调，不发送为 GraphQL 参数。两个操作和各三种结果的中英文文案已接在资源映射 API。

分娩：NativeFarrowingTask、NativeSubmitFarrowingPiglet、NativeFosterPiglet；查询保留状态、寄养/领养/无身份数量、时间、母猪关联的身份仔猪、分类统计和每次分娩记录。输入保留 is_completed，允许 partial、零数量以及 nullable/空 counts 的契约差别。仔猪身份记录中的 pig_id 是母猪 ID，读取核对 task_id 与母猪 ID，并拒绝重复 sequence、分类或记录 ID。

仔猪：NativePigletTask、NativeSubmitPigletHandlingItem、NativeSubmitPigletInfo、NativeSubmitPigletTotalWeight；保留八类处理项目、计划日龄、完成时间/操作者、身份/公母数量、寄养数量、窝次、总体重与原始 items JSON。身份输入保留 breeder/sex/耳标/耳缺/出生体重；与旧 adapter 一致，GraphQL 中空 identity_piglets 列表发送为 null，但离线原始输入仍区分空列表和 null。ProductionFloatString 映射 BigDecimal，请求发送普通十进制字符串；读取支持服务端数字或字符串；持久化使用 BigDecimal.toString 保留精度与 scale（包括 1E+3），不经过 Double。

当前旧 fosterPigletCommandHandler.ts 调用统一 FosterPiglet，任务类型由后端确定。FosterPigletInput 保存 FARROWING/PIGLET_PROCESSING/LACTATION 元数据用于本地分组，不擅自发为服务器参数；来源与接收母猪共同串行，未知结果阻塞两者，其他猪可独立执行。旧 SDL 的 FosterFarrowingPiglet / FosterPigletHandling 仍保留定义并有线协议测试，但不登记为当前离线发送操作。旧 handler 虽携带 Idempotency-Key，并不单凭客户端 header 推断服务端去重已经可靠。新增五类命令均沿用共用依赖/状态约束，无自动 mutation transport。

中英文覆盖五操作、三分娩状态、六仔猪类型和八处理项目；LIGAMENT_CUTTING 文案沿用旧业务“断脐带 / Umbilical Cord Cutting”。来源为旧 productionCareMutations.ts、fosterPigletCommandHandler.ts、farrowing/pigletCommand{Handler,Adapter}.ts 和 zh-CN/en 的 offline/fm 文案。

产后/哺乳：NativePostpartumTask / NativeLactationTask 与 SubmitPostpartumPigStatus / SubmitLactationPigStatus。SowAssessment 保存七个必需枚举和三个 nullable 脱垂字段；保留 NONE/null、true/false/null，协议中的 NO_MASSTITIS 拼写不自行改动。产后 assessment 必需，哺乳 assessment 可空。哺乳另保留公母数量、重量、身份与分类统计；身份空列表按本操作旧 handler 保留为空，不能套用仔猪信息接口的空列表→null 规则。is_obsoleted 明确保存，描述/原因不从 UI 默认值推断。

保育/育肥：NativeNurseryTask / NativeFatteningTask 与 CheckNurseryPigStatus / CheckFatteningPigStatus。CheckGrowingInput 是每 task/location 的整栏汇总命令，包含健康/弱/畸形、公母数量、总体重及有序身份猪列表；不拆成独立猪只 RPC。身份猪 ID 是实际猪 ID，区别于母猪任务的仔猪 sequence 记录。GrowingStage.FATTENING 保留旧离线类别 growing；分组按准确 task/location，长度前缀避免含冒号的 ID 产生碰撞，不因共享猪 ID 擅自改变旧栏位顺序。没有新增跨栏位数量一致性规则。

来源为旧 productionCareMutations.ts、postpartum/lactation/pigStatusCommand{Handler,Adapter}.ts、db/task/production/{postpartum,lactation,nursery,growing}.ts、productionPigFields2.ts 与本地参考 SDL。SowCheckResult 保留服务端 check_status 的原始 JsonValue，包含旧记录未提供的脱垂字段、未来 JSON 字段和数字精度，不把不透明 jsonb 强行改成当前输入 schema。LactationResult 保留初始数量 null、身份 breeder、母猪检查和仔猪数据。GrowingTask 保留任务猪只的身份/育种/位置、性别及体重；NurseryLocation 保存保育栏位汇总及时间/操作者。当前 SDL 无育肥栏位汇总 view，育肥 locations 保持空，不伪造与保育相同的读接口。

四操作与八组检查枚举有中英文资源。旧 adapter 在用户操作时补当前时间；本阶段输入/codec 保存调用者已确定的时间或明确 null，不在恢复/发送时重新生成时间。待接入业务入口时应在提交前捕获操作时间。本地投影不能丢弃已保存的脱垂字段，旧 postpartum 本地更新只拷贝七检查字段的差异已记录，后续投影按完整输入及待确认状态实现。

## 通用任务命令与目录基础（第 8 项进行中）

MarkTaskPigInput / CompleteTaskInput 及两 mutation 已定义，注册到 format 2 payload 与 SQLite。标记保存 task/pig/batch 和 nullable remark，null 与空字符串分别恢复/发送，不把标记解释为 disabled；参考后端 TaskPigMarked 的投影更新 task pig 的 mark。结束保存 task/batch/十类 task kind 和 nullable 原始时间，恢复时不重新取当前时间。

CompleteTaskInput 在入队时依赖同作用域/同任务所有未决命令，包括 FAILED/CONFLICTED/IN_FLIGHT/UNCERTAIN 和不同猪/栏位。领取事务再追加结束命令入队后出现的未决操作并保存 TASK_DEPENDENCIES_EXTENDED 事件；这是普通命令自动依赖冻结规则的明确例外，requestedDependencies 保持调用者原值。同任务只能有一个未取消的结束命令；一旦结束为 IN_FLIGHT/UNCERTAIN/SYNCED，新同任务命令被类型化拒绝。调用者依赖直接或传递指向该任务待处理结束命令也被拒绝，避免结束与迟到写入互相等待。原 operation ID 的相同输入重复入队仍返回原记录；已知失败的结束可以等待修复命令，但重试仍需确认原结果。

旧 lib/offline/taskCompletionCommand{Adapter,Handler}.ts 保留持久化适配，但当前 db/task/production/completion.ts 页面仅允许联网且无未决任务命令时结束。这里完成底层契约/存储与屏障，未改页面门禁、提供离线结束入口或注册真实 transport。入口时间捕获和正式行为接入仍按原规则处理。

AppContainer.taskCatalog 提供 page(lease, request, cacheOnly) 与 metadata(lease, id, cacheOnly)。NativeProductionTaskCatalog 同时用于分页和单 ID 元数据读取，强制 farm filter；参考 Hasura farm_view_task 的 user 权限允许 aggregation。保存十任务类型/六状态、原始时间、批次配置 JSON、产线、同批任务、负责人、计划、猪只引用与 mark、配种材料、公猪耳号、复情统计 JSON、保育栏位数量和 Decimal。逐层验证归属、枚举、唯一身份和非负统计。

TaskCatalogRequest 支持类型/状态/批次/产线/负责人/offset/limit（1..200）与两种稳定排序。activeAndCompleted 明确接收时间窗口，不在缓存或恢复时重新计算“今天”；采用 SCHEDULED/RUNNING 或 COMPLETED 且 endedAt 在 [from, until)。窗口比较保留任意小数精度及服务端偏移，UTC 等效边界一致；窗口筛选也受其他条件约束。配种列表可按计划开始时间及 ID 升序，用于后续精液出库关联。它是目录基础，未声称覆盖旧页面所有辅助条件或完成关联详情组装。

TaskCatalogCodecs 使用 format 1 独立显式 schema；production_catalog_page 的 key 包含完整筛选/排序/分页/窗口，production_catalog_detail 按 ID 保存。全作用域隔离，缓存 null 与未读区分，只在 Network/AuthUnavailable 回退。两种目录缓存加入原有每作用域 256 条读取容量，账户/位置保留，命令队列不参与裁剪。单页不覆盖整套离线任务，也不把目录快照冒充十类生产详情快照。

精度回归发现 Apollo 5.2 的 named fragment ObjectAdapter 先 buffer 到 MapJsonReader，其 readAny 路径可把 JSON 数字舍入为 Double。目录查询直接选字段；原 NativePigList / NativePigDetail 同步去除相同的 fragment 路径，读取模型和快照格式不变。MockWebServer 回归验证长小数重量/体温、健康记录 JSON、目录 profile/statistics JSON 和汇总 Decimal 均精确保留。保留的 NativeMatingMaterial fragment 不含数值小数或 jsonb。

验证见 [task-common](../verification/business-foundations/task-common/results.json)：99 JVM、69 常规 Android 通过，7 opt-in 默认跳过；新的 prepare/force-stop/restore 各显式通过一项，恢复两命令、一元数据和一页，新 PID，迟到依赖保留，未知标记结果不重发且阻塞结束，确认后结束可领取。当前共 19 种命令、10 类生产详情快照及目录/元数据缓存；39 operations/345 消费字段、209 文案/123 映射。第 8 项本地待同步投影、完整列表/详情组装及辅助查询仍未完成，其他业务继续按任务表执行。

## 待提交操作的本地预览（第 8 项仍进行中）

ProductionProjector 是纯函数：从服务端任务/元数据快照与同作用域队列重新构建 previewTasks / previewMetadata，保留 serverTasks / serverMetadata 原值，不将预览写回 SQLite 快照。十九种已登记输入有对应分支；取消操作移除预览，失败/冲突显示拒绝，IN_FLIGHT/UNCERTAIN 等待结果确认，未满足依赖或缺关联任务时不应用。依赖按显式关系与同组顺序排列，设备时钟回拨不覆盖明确前置关系；跨任务操作先在私有副本验证，失败时两端均不更新。

查情提交可在同批配种任务中追加待配种猪，复情提交关联孕检猪的禁用状态；分娩统计按后端单调累计规则预览增量，保留确认记录，待生成记录使用命令 ID 单独表达。配种同样只新增 LocalProductionRecord，不伪造服务端记录 ID、执行时间或确认汇总。孕检保留服务端 reCheckAt，保存的复查天数仍在原输入中，未套用旧前端与后端存在差异的日期算法。仔猪信息按照后端的完整身份替换规则处理 null/空列表，产后包含脱垂字段，哺乳缺 assessment 时保留原检查结果。保育/育肥汇总与已有身份猪更新一起验证；缺未读取的身份猪时原子拒绝预览。

寄养同时预览来源与接收母猪的身份和数量，同任务与跨任务均先校验库存/选择器/重复身份。哺乳身份保留 breeder。分娩或仔猪身份转入哺乳时，现有来源读取未提供 breeder，返回 MISSING_CONTEXT，不推断育种身份；哺乳出口标志等辅助读取仍需补齐。这些限制是读取上下文尚不完整的事实，不应当作全量寄养规则已覆盖。

已确认发送成功的 SYNCED 操作返回 WAITING_REFRESH，不在可能已经包含结果的旧快照上重复累加。reflectedCommandIds 只接受当前作用域中 SYNCED 的命令 ID，代表调用者已取得读取反映该操作的证据；当前未注册自动证据判定或真实 mutation transport，不能把此参数当成自动校验机制。结束任务只生成 COMPLETION_REQUESTED，本地不直接将服务端状态改成 COMPLETED。

AppContainer.productionWorkspace.task 组装单个任务的元数据、十类型之一的详情、同批活动关联任务读取，以及当前持久化队列。每份读取保留独立来源/时间，未宣称它们是后端原子事务快照。离线缺少关联快照时返回 unavailableRelatedTasks；业务/协议/认证拒绝与上下文切换仍抛错，不被缓存掩盖。完整辅助查询、元数据与所有业务预览字段的一致组装、成功操作的自动读取确认仍未完成。

本批验证见 [projections](../verification/business-foundations/projections/results.json)：verify.sh、112 JVM、75 常规 Android 通过，8 opt-in 默认跳过；投影进程测试另显式执行 prepare/force-stop/restore，各一项通过。新进程恢复两个服务端详情、一元数据、两命令，未知检查结果不重复发送，依赖提交不预览，确认缓存保持原值。GraphQL 仍为 39 operations/345 字段，中英文为 219 文案/133 映射。第 8 项及第 9–18 项整体仍进行中。

## 生产辅助读取（第七批）

AppContainer.productionAuxiliary 提供 batches / batch / rechecks，分别对应旧 batch.ts 的列表/详情与 reEstrusQuery.ts 的独立视图。批次保留产线状态与任务计划，BatchRequest.active 沿用 PLANNING/STARTED 且排除 LINE 默认批次；unfinished 排除 COMPLETED，仍包含 SKIPPED，与旧批次选择入口一致。按 ID 仍可读取默认批次原身份。复情视图保留任务/批次/厂区、检查/提交/配种时间、结果与操作者，以及区/单元/分区双语名称、行号和栏位。

两操作强制厂区过滤并核对响应关系，稳定排序，以多取一条判断 hasMore，不推断 total。三种辅助缓存使用独立 format-1 schema 与完整请求 key，复情唯一身份是 task/pig 对；null 详情与从未读取区分。纳入每作用域 256 条读取容量，连接/AuthUnavailable 以外错误不回退。七个批次/产线状态新增中英文。证据见 [auxiliary](../verification/business-foundations/auxiliary/results.json)：117 JVM、81 常规 Android、显式新进程恢复通过；9 opt-in 常规跳过。契约 41 operations/360 字段，226 文案/140 映射；本批仍无真实业务写入。

完整任务猪健康/历史/位置字段与第 9 项完整猪只读取重合，统一实现后回归第 8 项列表/详情组装。JoinBatch 的旧实际入口包含配种与巡检，后续需要命令适配，不因已有批次缓存就算实现。第 8 项及全业务仍进行中。

## 十类任务读取快照

AppContainer.estrus / mating / reEstrus / pregnancy / farrowing / piglet / postpartum / lactation / nursery / fattening 现提供 ProductionTaskReads.task(lease, id, cacheOnly)，返回 CachedResult，包含服务端值、来源、读取时间及适用的刷新失败。Graphql*Reads 实现 TaskRemote 并保留直接读取适配以供测试；没有调用 UI 或改变页面状态。

ProductionTaskCodecs 显式序列化六类业务模型，信封保存 format=1 和 task type。新增类型有独立 schema，既有四类型格式未变。保存原始精度时间、结果代码、null/false、位置、配种汇总/记录/材料以及分娩/仔猪数量、身份、处理结果、Decimal 和 JSON 文本，不保存 Apollo 对象、凭证或翻译文本。缺失任务可保存为 null；它与从未读取的 CacheMiss 分开。格式、字段、JSON 类型、时间和枚举错误拒绝恢复。日后变更 schema 必须显式处理格式迁移，不能无审阅更改格式 1 的含义。

StageTaskCodecs 新增四个独立类型 schema，沿用 format=1 信封；六个既有 schema 未变。Nursery/Fattening 共用 GrowingTask DTO，但 codec/type 分隔并验证 stage，不能互相借用缓存；育肥不能恢复带保育 locations 的伪造数据。设备测试覆盖四快照写入、重开与网络失败回退；显式新进程测试覆盖四快照和四命令恢复。

production_task 快照按 environment/OAuth/user/farm 和 type/task ID 分隔；恢复时再次核对 ID、厂区、类型及唯一猪 ID。只在 Network/AuthUnavailable 时回退快照，业务拒绝、认证拒绝、协议错误和上下文切换不会被好缓存掩盖。任务快照与猪只 page/detail 合计每作用域最多 256 条；账户和位置不参与容量裁剪，队列独立于读取缓存。

## 猪只属性与角色（第 9 项进行中）

UpdatePigPropertyInput / UpdateBreederRoleInput 已定义 NativeUpdatePigProperty / NativeUpdateBreederRole，并通过 PigManagementPayloadCodec 实际登记 format-2 队列；数据库 schema 仍为 2。属性保留生命周期、性别、乳头数、重量、备注的原始 null；空备注与零不改成 null，重量使用 ProductionFloatString / BigDecimal，离线保留 precision 与 scale。四角色动作保留原标记时间、输入顺序与重复项、耳号/耳缺、性别和父母 ID，未将参考前端的简单角色猜测作为确认结果。

两命令 productionType=unknown、无生产 taskId，按被修改的猪共享已有顺序；父母 ID 是引用，不作为被修改猪分组。批量角色同时占用所有目标猪的顺序，任一前置未知/失败都阻塞，其他猪及作用域仍独立。队列输入与显式依赖共同参与 operation ID 重用检查；已有 format-1 查情与其他 format-2 操作含义未变。

验证见 [pig-management](../verification/business-foundations/pig-management/results.json)：120 JVM、85 常规 Android 通过，10 opt-in 默认跳过；显式新进程恢复三命令，未知不自动重发，另一猪仅模拟成功，确认后角色可领取。当前 21 命令，43 operations/366 字段，232 文案/146 映射。第八批时死亡上报、图片上传持久化子步骤尚未实现，现已补齐如下；完整猪只读取与属性/角色/死亡本地预览仍待补；当前生产任务投影只覆盖十九生产命令，两种新命令不伪装成已有生产详情预览。没有真实发送器或后台重放。

## 死亡上报与图片子步骤（第 9 项进行中）

NativeUploadPigDeathImage / NativeReportPigDeath 保留旧 resources.ts、pig/mutations.ts 与 deathReportCommandHandler/Adapter 的请求语义。DeathPigInput 保留实际猪、母猪引用及无身份猪，耳号/耳缺/位置/原因的 null、空字符串和图片顺序；taskId 只用于本地协调，不发送给死亡接口。没有从后端 Python 内部字段猜测 SDL 未公开的 report_id 或 piglet_type。

SqliteCommandQueue.enqueueDeath 在一事务内将每张 data URL 保存为 UploadPigDeathImageInput 子命令，并将最终 ReportPigDeathInput 的图片改为类型化 operationId 引用；已有远程 URL 原值保留。子命令身份以报告 operationId 长度及猪/图片索引构造，每猪上传有显式前后依赖；重复草稿检查全部输入和原请求依赖，变化导致整事务回滚。最终上报自动依赖所有附件命令 UUID，并验证同作用域、同报告、同猪/图片位置、taskId 与目标猪。目标沿用 pigId 优先、否则 motherId；无身份猪不虚构 ID。

每个领取尝试只发一个远程操作。DeathImageReceipt 独立 format=1 严格保存 file_key/file_url，缺失、空 URL、data URL、错误版本、重复字段或多余字段不能作为成功回执。只有 SYNCED 且有效的所有回执才能领取/确认最终上报，CANCELLED 附件不满足这个要求。图片 IN_FLIGHT/UNCERTAIN 时拒绝取消报告；可取消报告会原子取消 QUEUED/FAILED/CONFLICTED 附件，保留已经成功的上传回执，取消不删除远程文件。结果未知仍需按既有确认 API 处理；没有上传结果查询依据时不自动重发。

参考后端 media.py 的 upload_pig_death_image 每次生成随机 key，因此未把旧客户端 Idempotency-Key header 视为可靠去重依据。GraphqlPigDeathGateway / PigDeathTransport 是显式、构造注入的适配器，未接入 AppContainer 或后台调度；只有模拟请求/回执验证。上报重试只重新解析已保存 URL，不重新上传成功的图片，不创造服务器记录 ID 或时间。通用队列仍 schema=2、payload format=2，既有操作格式含义未变。

为支持照片 payload，查询命令元数据时不把大 payload 放入一个 CursorWindow；超过 65536 字符的不可变 payload 按 SQLite substr 分段读取，再核对原 SHA-256。设备测试验证 2700022 字节图片原值重开恢复。没有把临时路径当作持久化附件。

[第九批验证](../verification/business-foundations/death/results.json)：125 JVM、91 常规 Android 通过，11 opt-in 默认跳过；显式强制停止后新 PID 恢复一成功/一未决图片及最终上报，未知/已成功图片重发均为 0，手动确认后两次模拟上报复用回执并保留 URL 顺序。当前 23 命令、45 operations/372 字段、234 文案/148 映射。第九批时完整猪只读取与本地预览仍未完成；完整读取基础已补充如下，本地预览仍待做。

## 完整猪只档案读取（第 9 项进行中）

NativePigProfiles 的选择集是旧 fullDetailQueries、fullListQueries、singleQueries、GetPigTasks 和 ProductionTaskPigFields3 的字段并集，另带厂区验证所需字段。字段路径逐项对照见 [来源覆盖证据](../verification/business-foundations/pig-profiles/source-field-coverage.json)：六个旧定义无缺失，完整档案选择 157 个路径。这不是声称读取 SDL 的所有猪只关系；巡检测量、事件日志等其他业务仍按各自任务实现。

PigProfile / PigHealthProfile / PigProfileLocation 等是独立领域值，不序列化 Apollo 对象。保留完整身份/品种/来源/乳头数、生产时间、当前位置及双语层级、当前批次/任务、采精控制、健康操作人与测温时间、疾病/症状详情及其原关联、健康历史原始 JSON、医嘱/治疗/药品引用和死亡图片。疾病或症状详情关系 null 时仍保留关联 ID；处方关系不可见时 therapies=null，存在但无治疗时为 []；统计缺失是 null，不从返回引用数推断为零。读取端原始代码与历史事件按字符串保存，未知值不猜测归类。

本批使用 Apollo 的 sealedClassesForEnumsMatching 配置保留十一类读取枚举的未知 rawValue；不修改生成文件或 Production 写入枚举。基于本地已用 Apollo 5.2.0 插件接口核对并由未知角色/采精状态请求回归验证。既有任务/批次筛选从固定领域枚举转成对应 sealed 类型，已知请求含义不变。增加来源 2、品种 22、采精控制 4 组中英文映射，YLL/YLP 保留规范缩写，不推测其扩写。

PigProfileRequest 以厂区约束查询，支持 ID 集合、角色、生命周期、批次、既有单元/栏位/字面包含搜索、分页和位置/身份排序；排序追加 ID 保证稳定。ids=null 表示不按 ID 过滤，空集合保持 _in:[]；includeCount=false 可用于精确详情/关联读取而不依赖列表聚合权限。缓存 key 包含所有请求字段，集合排序归一但不改变字符串大小写和空白。领域请求未开放任意 Apollo where 对象；后续业务需新增筛选时应按实际旧入口适配。

AppContainer.pigProfiles 暴露 page/detail。pig_profile_page / pig_profile_detail 采用独立 format=1 信封，数据库沿用 schema=2，与旧 page/detail 缓存含义隔离，并纳入每作用域 256 条读取容量。缺失详情 null 与从未读取 CacheMiss 分开；只有网络/AuthUnavailable 可回退，权限/认证拒绝、协议和上下文变化不能用好缓存掩盖。

SqliteSnapshotCache 读取大 payload 时以事务固定行，元数据与最多 65536 字符的分段读取避免 CursorWindow 单行限制；旧缓存格式和迁移保留。设备验证超过 2.7 MB 的健康 JSON（含补充平面字符）原值恢复，缓存清理不删除离线队列。

[第十批验证](../verification/business-foundations/pig-profiles/results.json)：130 JVM、97 常规 Android 通过，12 opt-in 默认跳过；显式新进程恢复列表、详情及缺失详情三快照，无远端读取，精确 Decimal/JSON、双语和作用域通过。当前 46 operations/449 字段、262 文案/176 映射，23 命令。新增读取仍仅 MockWebServer/模拟 remote；尚未将这些档案接入完整生产任务组装或属性/角色/死亡预览，不能将第 8/9 项标为完成。

## 属性/角色预览与任务档案上下文

第十一批将完整档案接入 AppContainer.productionWorkspace。工作区返回每 ID 的 CachedResult<PigProfile?>，与详情、主任务元数据分别保留来源、读取时间及刷新失败。按排序 ID 每组 100 个请求，任务猪和元数据引用、配种材料/记录/待提交公猪以及相关批量/显式前置命令的猪都加入上下文；无关队列与只读父母引用不触发额外档案读取。返回列表缺某 ID 表示本次读取确认不存在；缓存缺失/断网不可读记录为 RelatedPigUnavailable，二者不会互相冒充。三组 201 ID 的在线/离线读取、部分缺失、网络回退、错误传播和作用域切换已验证。完整档案不能使缺失的同批详情自动变为存在。档案读取完成后重新获取队列，再生成预览；读取中发生的领取或取消不会沿用旧 QUEUED 状态。读取期间新增命令若需要未读取的档案，则明确缺上下文，后续刷新补齐。

ProductionProjector 接受独立 profiles 并返回 serverProfiles / previewProfiles，不写入缓存或队列。PigManagementProjection 与十九生产命令共用拓扑/同组顺序；属性和四角色动作只在 QUEUED 且依赖允许时预览。任一目标缺档案、状态不符或 grower result 缺失均使整条预览不应用。角色服务按最后同 ID 输入查找、保留重复 aggregate 迭代；后端 repository identity map 的重复转移失败也被原子保留。来源文件及哈希见 [规则证据](../verification/business-foundations/profile-workspace/source-rules.json)。

属性要求当前 ACTIVE，null 不覆盖，零/空备注/Decimal scale 原值保存；把生命周期属性设为 DEAD 不调用死亡上报的生产任务副作用。MARK_AS_BREEDER 仅接受 PIGLET/FATTENING，要求已有或提供身份及性别，按实际提供的值变更；UPGRADE/CANCEL 仅接受 breeder，忽略这些动作中后端不使用的身份/性别/父母输入。未生成 markedAt 的服务端默认时间或不存在的父母关系。十类任务猪身份字段和已有元数据仅传播命令明确写入的字段；即使值与档案相同也应用到独立任务，不以可能更旧的档案替换独立任务值；保育/育肥 breeder 标记仅更新 PLANNED/SCHEDULED/RUNNING 任务。其他生产命令对完整档案的业务副作用仍需逐项核对，不能由此推断全档案预览已齐全。

CheckGrowingInput 保留 task/location 分组，新增实际修改的 pig 分组，确保角色/属性/死亡与保育/育肥同猪操作互相等待。新测试先复现未确认 grower 操作被角色预览绕过，再修复；独立栏位 fixture 同时改为不同猪，避免把同猪换栏位误当独立。SQLite 每次解码由 input 派生分组，schema 2/format 2 与原 payload 不变。没有新增语言代码；继续使用既有角色、生命周期、操作及预览状态中英文映射。

[第十一批验证](../verification/business-foundations/profile-workspace/results.json)：141 JVM、106 常规 Android 通过，13 opt-in 默认跳过；显式新进程测试恢复三快照/两命令，PID 2778→2914，未知重发 0、显式模拟刷新前远端读取 0。成功回执仍 WAITING_REFRESH，确认缓存保持原值；只有模拟确认读取加调用方显式证据后后续角色才预览。本批增加 11 JVM / 9 常规 Android / 1 opt-in 测试。契约、语言、命令数量仍为 46 operations/449 字段、262 条目/176 映射、23 命令。初始排序回归及旧测试中已不匹配新输入的 record fixture 失败日志保留，最终完整验证通过。八个 UI 文件哈希未变、发布配置 diff 为空、两个参考工作区干净；没有真实后端读写或发布。

第十一批结束时第 9 项仍缺死亡本地预览，现已补充如下。后端死亡上报包含母猪未处理仔猪前置条件、同窝多任务数量调整和死亡统计，不能只复用旧前端禁用猪只的简化逻辑。第 8 项还需同批任务元数据、列表/详情与材料身份预览一致性及逐操作自动读取反映证据；精液本地引用继续依赖第 16 项。全业务任务保持进行中。

## 死亡本地预览与跨批次成员上下文

第十二批补死亡原子预览及按猪跨批次任务成员读取。实际猪死亡禁用十类任务，母猪引用仅调整三类并行窝；身份按非空耳号 OR 耳缺移除首项，匿名数量下限为零。包含 PLANNED 的成员查询完整分页后才形成上下文证据，缺页/变动总数不冒充无关联任务。实际猪与母猪共同参与队列顺序，已确认图片是就绪资源，未知/失败/取消图片仍阻塞上报；确认快照不被预览或回执改写。匿名猪使用操作/行身份，不生成服务端 ID/时间；SDL 缺少的母猪转出状态、死亡统计与图片元数据保持缺上下文或待确认。

ProductionWorkspaceReads 按猪 ID 分组（最多 100）读取 PLANNED/SCHEDULED/RUNNING 的全批次任务目录，每页 200；使用现有 farm/作用域约束。记录每页元数据、typed 详情及档案的独立来源和时间，全部页总数/重复项/数量和加载结果一致才提供 PigDeathContext.taskIdsByPig。空集合键是已查明无任务，缺键是尚不能确认。成功档案读取确认的 absentPigIds 与 CacheMiss/网络不可用分开。旧无 pigIds 目录 key 保持原样，format-1 页继续可读；新增页 format 2 明确保存 pigIds，数据库和命令格式不变。

PigDeathProjection 在 private copies 预检全部报告行后应用。ACTIVE 实际猪转 DEAD；已 DEAD 或已确认不存在的实际猪跳过。真实猪的十类活动任务按后端禁用规则处理，重复同类实际匹配因服务端未排序而明确缺上下文。motherId 是被修改窝的引用，绝不使母猪死亡；关联分娩/仔猪处理/哺乳窝全部调整，禁用条目保持不变。显式非空 location_code 同步共享猪关系，未知新位置详情不虚构。

死亡报告分组由 payload 派生，同时包含实际 pigId 和 motherId；上传子命令的目标身份及原图片引用约定不变。有效 SYNCED 上传回执产生 RESOURCE_READY，不要求猪只读取反映；后续上报仍检查完整依赖及附件归属，未知/失败/取消不能满足。图片按原顺序保留类型化意图和已确认 URL，服务端生成的图片元数据保持待确认。无 pigId 的 ghost 仅以 command/row 标识，保留原 input；不创建服务端身份或时间。母猪未处理窝检查使用 SDL 未公开的 is_exported，因此存在仔猪时返回明确缺上下文；alive_baseline_received 等死亡计数写侧字段不猜测，记录为 pending。

verify.sh、156 JVM、112 常规 Android 通过，14 opt-in 默认跳过；deathPreviewStage prepare/force-stop/restore 各一项显式通过，PID 3869→3940，六快照/两命令/三个跨批次任务恢复，远端读取与未知重发均为 0。201 个关联任务分页及旧目录 format-1 兼容通过。当前 46 operations/449 字段、268 文案/182 映射、23 队列命令；schema 2/payload format 2 未变。[第十二批验证](../verification/business-foundations/death-preview/results.json)。第 8 项元数据/列表详情/材料完整一致性与自动读取反映判定、第 9 项后端未公开状态及其他档案副作用、第 10–18 项仍待做。无真实后端读写、UI、版本或发布变更。

## 医嘱与治疗写入及步骤持久化（第 10 项进行中）

第十三批开始第 10 项医嘱/治疗：六个实际写操作（执行、跳过、增删目标、终止治疗/处方）的原生定义、独立输入和 format-2 持久化已完成基础。每个远程步骤独立保存尝试和绑定 operation/recordId 的严格回执，整个批次原子入队；修改数量/顺序/输入/调用方依赖不能复用原批次身份。后续步骤失败只重试该步骤，不重放成功步骤；同处方及跨治疗共享依赖，设备时间回拨仍按明确顺序，其他处方和作用域独立。创建医嘱仅保留旧声明与精确 Decimal 输入，不把返回 serial 当成新处方 ID，也未开启离线创建。

TherapyCommandInput 使用独立 prescriptionId/therapyId；CompleteTherapyTargetInput / SkipTherapyTaskInput 另存 therapyTaskId、有序重复目标与完成原时间/原因，本地协调 ID 不发送给执行/跳过接口，不混入生产任务结束屏障。PrescriptionTargetInput 保留症状/疾病、耳号/栏位的原始 null/空值；没有照搬旧 target handler 丢弃这些字段的简化转换。Add/Remove 面向全处方的进行中任务，TerminateTherapy 也可能终止整个处方，故所有相关命令共享 prescription 组并依赖该处方已有未决操作；目标同时共享既有 pig 组。

SqliteCommandQueue.enqueueTherapySteps 原子保存整个列表，每个步骤有长度前缀 operationId、原始 payload、显式上一步依赖和独立 attempt/receipt。重复入队核对全部步骤集合、顺序、输入及调用方依赖，减少/增加步骤被拒绝，中间 SQL 失败不残留首步。一次领取只发一个远程 operation；已 SYNCED 步骤不因后续拒绝/未知而重发。严格 format-1 TherapyWriteReceipt 绑定 operation 和 task/处方 recordId，错误回执不能标为成功。GraphqlTherapyGateway / TherapyTransport 仅显式构造验证，AppContainer 未注册真实发送器或 worker。

NativeCreatePrescription 保留旧未调用声明及独立输入；BiosafetyFloatString 映射 BigDecimal/十进制字符串，BiosafetyDatetime 保存原时间精度。后端 create_prescription 的 recordId 是随机 serial_number，生成的 prescription ID 另行确定，因此没有创建命令入队或新建本地引用映射。不存在以 serial 冒充服务端处方 ID 的路径。

verify.sh、164 JVM、118 常规 Android 通过，15 opt-in 默认跳过；therapyStage prepare/force-stop/restore 各一项显式通过，PID 4960→4996，三步骤恢复，成功步骤未重放、未知重发 0，显式确认后仅模拟执行 TerminatePrescription。新增 8 JVM / 6 常规 Android / 1 opt-in；首轮回执 JVM 环境失败及进程测试误用同毫秒列表顺序的日志保留，已改为通用 JSON writer 和持久化步骤身份核对。当前 53 operations/462 字段、285 文案/199 映射、29 队列命令；schema 2/payload format 2 未变。[第十三批验收](../verification/business-foundations/therapy/results.json)。医嘱完整读取/参考数据缓存/本地预览及第 8/9 项剩余、第 11–18 项仍待做。无真实后端读写、UI、版本或发布变更。

后续读取和预览规则见 [.scratch 医嘱上下文](../.scratch/business-foundations/therapy-notes.md)：完整列表与首页/缓存日窗口筛选分开；后端 complete/skip 按目标创建下一轮，不能照搬旧本地等全窝当前剂次完成的简化逻辑。

第十四批补第 10 项读取基础：`NativeTherapyRows` 以处方、治疗、执行目标和关联猪只的旧字段并集读取，新增各层 farm/外键校验；完整分页、按处方/状态筛选与“进行中或未开始，或指定日窗口内有已执行目标”的预取分别建键，并以时区感知的精确时间比较校验缓存中的日窗口成员。治疗详情按 farm+ID 独立快照；药物、药物规格、治疗方式三类参考数据各有独立查询和快照。治疗剂量保留 Decimal scale，症状/疾病 JSON 保留数字字面量，时间戳保留原始精度/时区，服务端未知状态保留原码。五类读取快照共享 256 条有界容量。`verify.sh`、173 JVM、124 常规 Android 通过，16 opt-in 默认跳过；新进程恢复六快照、远端读取 0，见 [本批证据](../verification/business-foundations/therapy-reads/results.json)。这里仅是 MockWebServer/SQLite 读取基础，不等于真实后端联调；逐操作预览仍待做。

第十五批新增 `NativeTherapyPrescriptionContext`，读取处方目标和治疗完整成员并以聚合计数核验；`TherapyWorkspaceReads` 按处方、稳定 ID 顺序分页组装全部治疗，保留每页来源。`TherapyProjector` 从确认快照与同作用域队列计算六操作预览，逐目标建下一轮，目标增删及终止跨治疗处理；失败、未知、已发送等待刷新和依赖未决有独立状态。服务端事件时间、操作者和新行 ID 不伪造，原快照不回写。处方上下文作为第六类读取缓存纳入 256 条界限。`verify.sh`、179 JVM、126 常规 Android 通过，16 opt-in 默认跳过；显式新进程恢复八快照、一命令且远端读取 0。GraphQL 58 operations/518 字段、中英文 370 条/207 映射；见 [预览证据](../verification/business-foundations/therapy-preview/results.json)。读取反映成功命令的证据仍需自动判定；后端活动任务若无物化目标行，本地读取不能证明其存在。真实后端联调、业务 UI、版本和发布均未覆盖。

## 疫苗任务写入基础（第 11 项进行中）

第十六批接入 ExecuteVaccinationTask / FinishVaccinationTask 的 GraphQL、独立输入、严格 task ID 回执与 format-2 payload。执行保留目标顺序及重复值，后端自行去重；执行与结束时间保留 null 或原始时区精度。同一疫苗任务的未决剂次显式依赖，结束命令等待此前全部剂次并阻止新接种及重复结束；跨猪共享既有 pig 组。真实 mutation transport 和自动重放未注册，旧前端结束入口本来要求在线，原生入口也未开放。

后端是多剂次模型：一次执行只推进选中的目标一剂，并可能为该目标生成下一剂；全部目标剂次完成时才自动结束，人工 Finish 可提前结束任务。旧 Web 把选中目标立即当作完成的乐观更新与多剂次规则不一致，因此未移植。完整列表/详情、剂次读取缓存、本地预览、进程恢复和真实后端联调待补；详细源规则与后续读取要求见 [疫苗上下文](../.scratch/business-foundations/vaccination-notes.md)。写入基础验证见 [vaccination-write](../verification/business-foundations/vaccination-write/results.json)。

第十七批补完整疫苗任务列表/详情读取及两类作用域快照。完整分页与旧首页“非 DELETED，未完成或指定日窗口内完成”分键；详情按 farm+ID 查询。逐剂模型保留剂量 BigDecimal scale、JSON 数字字面量及原始时间，校验任务/疫苗/品牌/猪只厂区、目标数量、聚合行数和连续剂次。Apollo fragment 路径在测试中舍入剂量，改用完整字段单查询供列表/详情复用。显式新进程恢复三份快照及一命令，远端读取 0；见 [读取证据](../verification/business-foundations/vaccination-read/results.json)。这里只验证 MockWebServer 与 SQLite，豁免上下文、逐剂本地预览、读取反映证据和真实后端联调仍待。

第十八批补逐剂本地预览与工作区：QUEUED 接种仅推进各选中目标的当前剂次，若还有剂次则以命令 ID 标记下一剂本地行；人工 Finish 仅结束任务，不虚构未接种目标的执行。新剂次调度时间、服务端事件时间和操作人保持未知，服务端快照不被预览改写。未知发送结果、失败、未决依赖和已发送等待刷新有独立状态，`reflectedCommandIds` 仍需调用方显式读取证据。新进程恢复三快照、一命令并重建预览，远端读取 0；见 [预览证据](../verification/business-foundations/vaccination-preview/results.json)。自动读取反映判定、豁免完整上下文与真实联调仍待。

第十九批把目标的豁免物化记录接入同一 GraphQL 查询和 SQLite 快照，保留命中、规则码、原始原因、评价/有效/修改时间及操作人。厂区、猪只 ID 和同目标各剂关系一致性均校验；旧快照缺字段时保留“未知”，新查询中关系为空才表示此次未取到记录。十二项后端规则配有中英文资源。豁免可能随健康/生产事件变化，后端单独重算任务结算，原生暂不把读取记录当成实时授权或改写确认统计。`verify.sh`、191 JVM、133 常规 Android 通过，17 opt-in 默认跳过；显式跨进程恢复豁免及原队列，远端读取 0。契约 61 operations/592 字段、402 双语条目/253 映射；见 [豁免证据](../verification/business-foundations/vaccination-exemption/results.json)。自动读取反映、结算副作用及真实后端联调仍待。

第二十批完成采样主任务的非 UI 基础：SubmitSampled GraphQL/输入、严格 task ID 回执和 SQLite payload，按任务阻止重复未确认提交；后端仅接收任务 ID 与可空备注，旧 Web 展示中的操作时间/人员不写成输入。完整分页、指定窗口首页预取和详情读取覆盖猪只、人员、环境、物资、其他目标，保留试验及报告关系、JSON 原始精度；分离两类 SQLite 快照并检查厂区/外键/聚合。预览只更新待确认 `isSampled` 与备注，服务端时间/人员未知。`verify.sh`、201 JVM、138 常规 Android 通过，18 opt-in 跳过；显式新进程恢复三快照与一条 UNCERTAIN 命令，远端读取和自动重发均为 0。契约 63 operations/672 字段、中英文 425 条/281 映射；见 [采样证据](../verification/business-foundations/sampling/results.json)。真实读写与自动读取反映未覆盖，简化入场采样另在第 17 项。

## 共用持久化

AppContainer.commandQueue 暴露 SqliteCommandQueue；此前的 SqliteEstrusQueue / EstrusDispatcher 已由共用队列 API 取代。仍打开 native-estrus-queue.db 以保留原记录。SQLite schema 2 新增 requested_dependencies：与自动推导的依赖分开保存，保证同一 operation ID 再次入队仍检查调用方输入一致性，检查完成后不会因推导集合变化创建另一条提交。

schema 1→2 只追加列并把旧 dependencies 复制为 requested_dependencies，保留命令、attempts、events、归属、原始 payload 和摘要。旧格式 1 明确解码为 CheckEstrusInput；新格式 2 是 operation + input 信封，目前支持查情、配种、复情、孕检各两种，以及分娩、统一寄养、仔猪三类操作、产后、哺乳、保育、育肥、任务猪只标记、任务结束、猪只属性、角色、死亡图片上传与死亡上报，共二十三种，另加医嘱六种、疫苗执行/结束两种，合计三十一种。TherapyPayloadCodec 与 VaccinationPayloadCodec 分别登记六种、两种。CareCommandPayloadCodec 实际登记后十一种操作，保留 stage、耳标顺序、Decimal、显式空值、列表、检查和整栏汇总。未知操作/版本/字段、不正确的 JSON 类型、损坏摘要会阻止发送；不会强转成已知操作。新增业务必须实际登记 codec，不因实现 CommandInput 接口就默认支持持久化。

批量查情提交在入队事务中，按同一 environment/OAuth/user/farm、task、所选 pig 推导所有未完成 CheckEstrus 的依赖。同 task/pig 保守串行；不同任务的同一 pig 查情/配种也串行。显式依赖和同组前序的 FAILED/CONFLICTED/IN_FLIGHT/UNCERTAIN 都阻塞后续；取消只取消本地命令，不能撤销服务端写入。新进程恢复将残留 IN_FLIGHT 标为 UNCERTAIN，确认原尝试结果后才解除阻塞。

复情/孕检分别按同 scope/module/task/unit：提交依赖该单元所有未决检查及前一次提交（包括本次未选中的猪），检查依赖该单元未完成提交。检查按猪分组，提交使用命令独立组，保持旧轮次屏障的行为。显式传递依赖优先于同组创建时间，设备时钟回拨也不能让下一轮检查反向阻塞前一轮检查。重复 operation ID 比较原输入与显式依赖；已推导的依赖不会因前序后来成功而改变。

原基础阶段的 AppContainer 只提供读取与本地队列。后续已为**巡检单元打卡**注册受控真实 transport：客户端记录 ID、严格回执和网络读回同 ID 后才确认，结果不明确时仅查询原记录，不自动重发；详见[当前交付对照](home-inspection-health-delivery.md)。其余 mutation 仍未注册发送器或独立后台 worker。MockWebServer 验证线协议不构成线上写入已通过的证据；当前巡检打卡也尚未完成真实 dev 写入验收。

首批证据：[business-foundations](../verification/business-foundations/results.json)，复情/孕检与快照证据：[rounds](../verification/business-foundations/rounds/results.json)，分娩/寄养/仔猪证据：[care](../verification/business-foundations/care/results.json)，产后/哺乳/保育/育肥证据：[stages](../verification/business-foundations/stages/results.json)。完整任务表继续推进第 8 项剩余列表/详情组装、生产本地投影及后续业务；完整查询筛选、辅助列表和跨模块引用仍待补齐，不能由底层命令持久化替代。

已记录的差异：旧 pregnancy.ts 仅 NOT_SURE 且有有限 re_check_days 时本地计算 re_check_at；参考后端 domain/models/tasks/pregnancy.py 对非 PREGNANT 且天数非零时计算（包括 NOT_PREGNANT），null check_at 使用服务端当前时间。当前保存/读取服务端结果与原始输入，不把前端推算值写成确认快照。后续本地待同步投影必须标识待确认并核对这些条件。
