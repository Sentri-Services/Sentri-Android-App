# 后端调整与核对清单

2026-10-26 用户确认：目前没有新后端实现分支或接口文档。新契约由本次 Android 交付提出，最后在本文件统一整理给后端；不再等待用户提供现成接口。表内“拟接”表示目标入口，不能据此认为 App 已实现该功能。每项均补充应用位置与用途；现有接口／缺失能力和建议仍分别列出。

本表记录开发时已发现的接口语义差异。`../Sentri/backend` 是只读源码参考；源码状态不能代替当前 dev/生产环境的实测结果。用户 2026-10-21 确定：健康记录现阶段只读与目录，写入等后端支持逐条发现及 Care 契约。

下表是缺口及使用场景；文末的[新契约提案](#backend-contract-proposal)给出建议输入、返回与离线规则。建议名称尚未成为已部署接口。

| 页面／操作 | App 应用位置与大致功能 | 现有接口与源码证据 | 差异及受影响场景 | 建议与当前处理 | 验证状态 |
| --- | --- | --- | --- | --- | --- |
| 巡检 Feed 视图与栏位饲喂 | 首页 → 单元 → 巡检的 Feed 视图、栏位详情 → 饲喂（拟接）。显示当前有效饲喂方案、停喂／调整对象和配方切换提醒。 | Sentri-UI `inspection.walk` 按逐猪 held／stopped／factor 变化和栏位 `switchDue` 计数；`feed-plan` 要求当前曲线、调整、固定量及停喂共同决定实际饲喂。现有 GraphQL 提供 `farm.view_pig_feed_override`、`farm.view_feed_amount_change`、`farm.view_batch_feed_override` 和 `production.unit_required_feeds`；最后一项只按饲料汇总头数与公斤。当前 `PigProfile` 无有效饲喂状态。后端 `unit_required_feeds_view` 能内部解析曲线、覆盖与系数，却只返回按饲料聚合的结果，读取系数时也未见按 `duration_kind`／`duration_value` 判定过期 | 单看调整投影不能确认调整是否仍有效，也不能得知每头猪当前曲线／固定量／停喂状态；汇总接口不含猪只、栏位和配方切换日期。用覆盖记录或聚合头数拼 Feed lens 会遗漏或误算，栏位总饲喂量与到期提示也无法核对 | 提供按厂区、单元、猪只／栏位读取的有效饲喂投影：当前计划与饲料、基础量、有效调整类型／系数或固定量、停喂、栏位模式与配方切换到期、计算时间；明确变化到期与离线快照语义，并核对汇总视图的调整期限。Android 当前只开放 All／Health，Feed 标不可用，不显示猜测的数量 | Sentri-UI `inspection`／`feed-plan` PRD、Android Schema／模型、后端领域服务与汇总视图源码已核对；当前 dev 的新投影与真机未验证 |
| 首页计划治疗任务与临时治疗区分 | 首页 → 总览／单元 → 治疗任务卡（拟接）。只列计划治疗；随手记录的临时治疗不生成待办卡。 | Sentri-UI Workbench 将 treatment orders 作为 Health 计划任务；旧 Web `HealthList.tsx` 则一条 therapy 一张卡。后端 `Prescription.is_adhoc` 与 `PrescriptionCreated.is_adhoc` 可区分临时治疗，创建临时治疗时服务会立即完成其目标；但 `farm.view_prescription` 投影、Android GraphQL Schema 和 `TherapyRow` 均未暴露 `is_adhoc` | `NotStarted`／`InProgress` 是执行状态，不是业务类型；仅靠该状态无法从接口本身核对计划治疗身份。一个处方含多疗程时，新旧卡片身份口径也不同 | 在可查询的处方／疗程投影返回稳定 `is_adhoc` 或明确的 work type；产品明确一张首页卡对应处方还是 therapy（建议按处方聚合，卡内展示疗程）。Android 在类型与口径确定前不把治疗目录接入 Home 计划任务卡，仍保留治疗只读工作区及临时治疗预览 | 后端领域服务、事件、投影、旧 Web、Workbench 原型和本地 Schema 已核对；真实 dev 的开放疗程汇总只读测试通过，类型仍无法由接口判定，首页未接入 |
| 巡检检查项 · `SubmitUnitInspection` | 巡检 → 结束／再次打卡 → 历史打卡详情。核对旧记录的清洁度、合规性是否准确保存与读回；这些不是当前打卡的新增必填项。 | `app/src/main/java/com/sentri/nativeapp/inspection/UnitInspectionModels.kt` 已发送 `cleanliness`、`compliance`；`../Sentri/backend/apps/biosafety_v1/src/biosafety_v1/service_layer/handlers/commands/inspection.py` 当前已把两字段传入 `UnitInspection.create` | 本仓库 `docs/business-foundations.md` 中的“handler 未传递”是旧结论；尚不知已部署环境是否同版、读取是否反映 | 不改接口；以真实 dev 写入和读取核对这两字段，再决定是否关闭旧缺口。未核对前 UI 不标“服务端已保存” | 源码已支持，部署/读回未验证 |
| 巡检单元环境传感器 | 首页 → 单元概况／巡检 → 环境信息（拟接）。区分设备未安装、已安装无读数和最新有效读数。 | Sentri-UI `inspection.unit-detail` 为 `placeholder`，样例区分已安装有读数、已安装无读数、未安装；Android 当前 `NativeUnitInspection` 只读取人工巡检记录中的温湿度等历史字段，不能表达传感器安装状态、实时读数及采集时间 | 把最近一次人工打卡的温度当作当前传感器值会误导巡检人员，缺失值也无法区分未安装与读取失败 | 明确单元传感器目录、安装状态、带采集时间的最新读数及权限；Android 现只接可核实的设备故障入口，环境数值保持未接入 | 设计状态与本地 GraphQL 读取已核对；真实传感器 API 未验证 |
| 健康发现图片 · `AddPigDisease` / `AddPigSymptom` | 巡检 → 单猪病症 → 记录疾病／症状 → 照片（拟接）。把照片关联到具体病症记录，并能离线保存、上传及查看。 | 输入模型含 `image_urls`，但 `../Sentri/backend/apps/biosafety_v1/src/biosafety_v1/entrypoints/graphql/pig_health.py` 构造命令时未传此字段 | 图片上传回执不能证明它已关联疾病/症状记录；离线图文复合操作无法确认完整结果 | GraphQL 入口传入图片 URL，并在记录读取中返回稳定关联；调整前只允许明确说明图片未关联的独立保存，不宣称图文闭环 | 源码缺失；真实环境未验证 |
| 栏位盘点原因 · `ReconcileLocationInventory` | 单元存栏／巡检 → 栏位详情 → 设置头数（拟接）。记录盘点前后数量、差值和原因，保留可核对的调整记录。 | 原生输入有 `reason`；`../Sentri/backend/apps/production_v2/src/production_v2/service_layer/handlers/commands/pig.py` 调用库存服务时未传 `command.reason`，响应只给位置编码。只读 `NativeLocationInventory` 可按厂区、栏位读当前在群身份 | 原因不能作为调整审计记录，未知结果不能靠回执识别具体调整；可能影响多猪/批次 | 明确保存原因并返回操作/调整 ID 或提供按客户端操作 ID 查询结果；Android 已开放设置头数的本地预览，保留未关联批次组、数量下限、选填原因和 Other 说明；资料不全的批次不允许猜测调整。正式保存、添加批次／移除分组与已记录结果仍未接，不自动重发未知结果 | 真实 dev Unit 2 Pen 2 只读 17＝12＋5 且与名单总数一致；写入契约仍缺，未验证 |
| 栏位盘点未关联批次的未标识猪 | 单元存栏／巡检 → 栏位详情 → 设置头数。保留尚未关联批次的数量，不因缺批次而丢失或自动改归属。 | 旧 `EditPenPigCountPopup.tsx:845` 明确过滤没有 batchId 的分配；旧 GraphQL 的 `NonIdentityBatchAllocation` 要求非空 batch_id。后端 `pig_inventory.py` 要求身份数＋分配数等于目标总数，把未分配列入 surplus 后可能补到别的批次或移除 | 未关联批次的组不能无损提交；即使总头数没变，也可能发生跨批次调整或数量校验失败，不能仅凭总数相同认定无变更 | 新读取显式返回“未关联批次”组和稳定群体身份；调整输入允许保留／调整该组，不默认补到其他批次。数量调整与批次归属变更分别确认并审计；当前不接旧盘点 mutation | 旧页面过滤、非空输入与后端领域服务已核对；2026-10-26 dev Unit 2 Pen 2 网络读到 17＝12＋5，其中未关联批次 4 头、已关联批次 1 头；同份缓存核对通过，未做盘点写入 |
| 首页厂区日期与已完成卡片 | 首页 → 单元的未来七日，以及任务列表中的已完成／提前结束卡片（后者拟接）。按厂区日期显示任务和当日结束记录。 | `features/workbench/PRD.md` 要求厂区时区内“今日结束”；原生 `TaskCatalogRequest.activeAndCompleted` 需要调用方传绝对时间窗口。当前后端源码的 `FarmInfoStrawberryType.timezone` 和 `farm_info` 返回值均已存在，但 Android 原 Schema 快照无此字段 | 当前 dev 已实测支持该字段，其他部署未核对；设备时钟也不是权威当前时间，跨时区设备或错钟可能错列 已完成／提前结束卡片 | Android 用独立的可选 `GetFarmTimezone` 查询读取 IANA 时区；旧部署拒绝该字段时账号仍可进入、日期功能保持不可用。确认部署与可信时钟前，不用设备日期生成“今日已结束”窗口 | 后端源码与本地 Schema 差异已核对；新查询经 MockWebServer 及当前 dev API 36 在线测试，已完成／提前结束卡片 窗口未验证 |
| 首页排程开始时间的时钟基准 | 首页 → 总览／单元 → 今日任务、未来七日。确定排程是否已到开始时间，避免手机错钟影响入列。 | `HomeTaskScopeProjector`、`HomeVaccinationScopeProjector`、`HomeTransferScopeProjector` 用绝对开始时间与 Android `System.currentTimeMillis()` 比较；首页每 30 秒重算一次 | 设备时钟被手动调错时，未开始任务可能提前或延后进入首页；30 秒重算只解决停留页面时的陈旧状态，不证明设备时间可靠 | 核实后端是否能提供可信当前时间，或明确客户端可用的校时规则；当前 dev 厂区时区已可读取；当前只按设备时钟做开始时间筛选，不据此生成逾期、到期头数或 Closed 日期 | 本机代码与 API 36 到点界面测试已核对；可信时钟契约和跨时区真机未验证 |
| 首页任务归属 · 生产任务目录 | 首页 → 分区总览／单元任务列表、分区／单元选择器。可靠判断任务属于哪里，计算该范围内的任务实例数。 | `TaskMetadata` 目前只有任务猪只的**当前** `location_code`、批次和产线，缺少稳定的任务分区/单元归属字段 | 猪只转出、目标为空或只剩历史对象时无法准确判定任务属于哪个分区；若按部分匹配计数会漏单元。用户 2026-10-20 确认：归属不可靠的任务暂不展示 | 任务目录返回明确的 section/unit ID 集合或具备历史归属语义的任务范围；客户端当前仅在所有有效任务猪只均匹配已知单元时展示，缺失者不计入首页 | 现有读取已核对；后端字段待设计，真实数据待验证 |
| 首页生产任务同类型排序 | 首页 → 生产任务卡列表／生产任务折叠组。同类型任务按已确认规则排序，避免用批次序号猜新旧。 | Workbench PRD 要求同阶段按最早批次排序；现有 `TaskBatch` 只有 ID、serial、profile 与 line，没有可证明的批次创建/开始时间 | serial 的字典序不能稳定表达批次先后，任务创建时间也不等于批次创建时间 | 任务目录增加批次稳定排序时间或序号；当前可用任务 ID 作稳定兜底，不声称与“最早批次”一致 | 契约待调整 |
| Workbench Overview tile 的待办数与日序 | 首页 → 分区总览精简任务卡、单元完整任务卡。展示当前待处理数、明确逾期数及有依据的计划日进度。 | 2026-10-19 Workbench PRD 的四槽 tile 需要当前分区“此刻应处理数／明确逾期数”、计划自然日序号与完成窗口。`TaskMetadata` 只有任务状态、当前成员、计划起点和可选时长；多数任务缺当前动作窗口、逐次记录与明确截止；当前 dev 已可读取厂区时区，但仍缺可信当前时间 | 成员数不等于此刻待做数；以设备日期算 `Day N of N` 或把计划末日当截止会制造虚假逾期。健康计划的剂次时间同样不能从总目标数推出 | 按任务类型返回当前动作的目标身份、执行窗口和记录结果，另返回厂区时区／明确截止。Android 现只在分区 tile 显示可靠的“任务涉及 N 头”和计划时长；到期、逾期、Ready to close 和日进度均不推断。单元任务仍保留权威状态与可靠成员数 | 最新设计与本地读取已核对；真实 dev 数据及新字段未验证 |
| 孕检本次检查与复查身份 | 首页 → 孕检卡 → 孕检任务详情。区分本次检查、复查与历史结果，展示本次待检数及记录进度。 | `NativePregnancyTask` 按任务 ID 返回猪只及单个 `pregnancy_pig` 最近结果、检查时间和复查时间；`features/pregnancy/PRD.md` 的 Home 交接按“本次检查”统计待检和记录 | 最近结果不能区分多轮检查或哪一次复查已记录，无法计算本次待检、逾期和进度；用 `result != null` 当完成会漏掉后续复查 | 为任务猪只返回带稳定检查/轮次 ID 的检查计划、窗口及逐次记录，明确复查归属；当前 Android 任务详情只读显示最近结果，不计算本次指标或开放孕检写入 | 本地 GraphQL 读取与设计已核对；真实 dev 数据和新契约未验证 |
| 查情逐猪检查的离线确认 | 首页 → 查情任务 → 逐猪检查／发情提交（写入拟接）。离线记录后确认具体哪一次检查成功，避免重复提交。 | `CheckEstrusPigCommand` 只有任务 ID、猪只 ID、检查状态和可选时间；`check_estrus` 的成功响应只带任务 ID，当前 `estrus_pig` 读取给最新状态与时间，没有客户端操作 ID 或逐次记录 ID。`SubmitEstrusStatus` 同样缺客户端操作 ID。Sentri-UI `heat-check` 的操作流程仍为 draft／earlier，结束任务为 placeholder | 断网排队后若响应丢失，最新状态即使相同也不能证明是这一条操作成功，之后的检查还可能覆盖它；自动重发可能重复业务效果。旧版的逐次状态不能直接证明新版“一次标记即提交”的结果 | 后端增加客户端操作 ID、幂等约束、逐次结果 ID／按操作 ID 查询及明确的检查与发情提交关系；产品先确认查情任务流程。Android 目前只展示逐猪最新检查状态及时间，不把它当今日已查完，也不开放写入或自动重发 | 本地 GraphQL、后端命令／handler、旧 Web 与 Sentri-UI draft 已核对；真实 dev 写入和新契约未验证 |
| 查返情逐猪检查与提交的离线确认 | 首页 → 查返情任务 → 检查／提交（写入拟接）。保留旧版检查与提交链路，核对提交及批次后续处理。 | `CheckReEstrusPigCommand` 和 `SubmitReEstrusStatusCommand` 只有任务／猪只、状态及可选时间；GraphQL 成功仅回任务 ID。当前任务读取有逐猪最近检查状态、`is_in_re_estrus` 和提交时间，但没有客户端操作 ID 或逐次结果 ID；提交 handler 还会处理返情猪只退出批次等后续事件。Sentri-UI `return-heat` 记录流程仍为 draft，Home 暂沿旧数据模式 | 若检查或提交响应丢失，最新状态无法证明是本次离线操作完成；重复提交涉及批次与猪只后续动作，不能安全自动重放。最近记录也不能推出今天应查人数 | 后端给两类操作稳定客户端 ID、幂等约束、按 ID 查询结果和可核实的逐次记录；产品确定新版记录与旧检查/提交的映射。Android 先只读展示当前范围猪只最近状态，明确旧接口来源，不开放检查或提交 | 本地 GraphQL、后端命令／handler、Sentri-UI draft 已核对；真实 dev 写入及新契约未验证 |
| 配种下一次执行与离线确认 | 首页 → 配种任务 → 逐猪配种记录（写入拟接）。显示有依据的下一次执行窗口，安全保存公猪／精液配种记录。 | `NativeMatingTask` 可读逐猪 `mating_count`、首末配种时间和记录，但没有配置的应配次数、相邻间隔或下一次执行窗口。现有 `MateWithBoar`／`MateWithSemen` mutation 只返回状态与错误，没有客户端操作 ID 或配种记录 ID；Sentri-UI `breeding` 仍为 draft | 旧累计次数不能推出何时应配或是否完成配置的 N 次；断网发送后响应丢失，后续配种记录即使相似也不能证明本次写入，自动重试可能多记一次 | 后端返回任务逐猪应配序列、下一次窗口与稳定的配种记录身份；写入支持客户端操作 ID、幂等和按 ID 核对。Android 当前仅按任务／单元只读展示旧累计次数及最近时间，不画下一次到期或完成进度，不开放离线配种写入 | 本地 GraphQL 查询/Mutation 与 Sentri-UI draft 已核对；真实 dev、写入和新契约未验证 |
| 首页免疫任务范围与到期数 | 首页 → 免疫卡 → 任务详情。按单元和剂次显示待接种数量、执行时间与明确逾期数量。 | `NativeVaccinationTasks` 已返回任务状态、疫苗、逐猪逐剂目标及其当前猪只位置；任务本身没有权威 section/unit 归属或明确截止时间；当前 dev 已可读取厂区时区 | 猪只转出或目标缺位置时无法准确归属；已指派猪只总数不等于本剂次已到时间且尚未接种数，也不能从创建时间推断逾期 | 后端提供任务分区/单元归属、剂次执行窗口/截止和厂区时区。Android 当前只在全部目标猪只可靠定位时出卡，显示“任务涉及 N 头”，不显示待接种/逾期数；免疫详情与执行暂未接 | 本地 GraphQL 字段与读回模型已核对；真实 dev 数据和新字段未验证 |
| 首页免疫任务读取：跨厂批次关联与疫苗身份冲突 | 首页免疫列表及单元未来七日的数据读取。保证批次属于当前厂区，待接种剂次的疫苗／品牌与任务一致；异常数据需要后端修复。 | 2026-10-22 在 `zz-dev` 用当前账号实测 `NativeVaccinationTasks`：一条目标猪只的 `current_batch.id` 等于 `current_batch_id`，但关联批次的 `farm_id` 不等于当前厂区；首页完整开放目录中的另一项 `IN_PROGRESS` 任务有 `PENDING` 第 1 剂目标，其 `vaccine_id` 与 `brand_id` 均不等于任务头的值。`../Sentri/backend/apps/biosafety_v1/src/biosafety_v1/adapters/hasura_matv/healthy.py` 分别投影任务头与目标行；当前数据未满足二者一致的读取规则 | 跨厂批次序号不可信，不能写入本机快照；待接种目标与任务头使用不同疫苗／品牌时，首页卡和详情可能误导执行者。用户已确认：待接种剂次的疫苗和品牌必须与任务头一致，后端修复数据 | 后端核对批次关系的厂区约束，并修复任务头与待接种目标的疫苗／品牌数据及投影更新，保证两者一致。Android 丢弃跨厂可选批次序号，其余同厂猪只与任务照常读取；对待接种目标身份冲突仍拒绝整份首页免疫目录，明确显示数据异常，不用部分目录或猜测值代替 | API 36 模拟器真实 dev 查询复现；跨厂批次回归测试先红后绿，目标身份冲突仍由显式 opt-in 真实读取测试复现。后端尚未修复或复核 |
| 首页采样任务入列与目标归属 | 首页 → 采样任务卡（拟接）。判断任务是否已指派、何时执行及涉及哪些单元，不把普通任务目录直接当今日待办。 | `NativeSamplingTasks` 可读 `created_at`、`is_sampled`、`disabled`、`used` 及目标；后端 `SamplingTask.link_permission()` 仅在检测结果用于权限时置 `used=true`，`disabled` 表示任务被移除或禁用。没有计划执行时间、截止、指派状态或任务范围字段；旧 Web 首页按最近 7 天创建筛选，人员／物料目标还会复制到分区 | 不能从 `used` 判断任务是否已安排，也不能把创建时间当今日到期；猪只当前栏位可能已变化，环境目标的 opaque `target_id` 不能靠字符串包含判断分区。盲目出卡会把未安排或历史采样当作今日工作 | 后端返回任务执行窗口／指派状态和稳定 section/unit 或全场范围，按目标返回可核实地点。Android 暂不将采样放入首页；现有 `SamplingReads` 仍可按任务 ID 只读，不把其 ALL 或“全部未采样”列表当今日任务 | 旧 Web、后端领域模型和本地 GraphQL 契约已核对；真实 dev 数据及新字段未验证 |
| 首页转舍待转数与接收范围 | 首页 → 转舍卡 → 转舍任务详情。区分当前单元的转出／接收工作、待确认头数和目标位置。 | `NativeHomeTransferTasks` 现在读取 `PLANNED`／`IN_PROGRESS`、计划时间、转出／转入单元编码；领域模型逐猪状态为 `PENDING`、`TRANSFER_OUT`、`TRANSFER_IN`、`CANCELLED`。旧 `UnitTransferOutCard` 按来源编码前缀算总数，转入卡只显示累计 `transfer_in_count`；转入单元可增补，任务创建时可没有接收单元 | 跨多个接收单元时，单元列表不一定能把某个待转猪只分配到具体目的地；累计转出数或总目标减完成数也不是当前单元此刻待确认数。前缀匹配还可能混入同名编码 | 后端给逐猪明确目标单元、当前应做方向及可执行窗口，或提供按单元的权威待转聚合。Android 先按精确单元编码与计划时间展示一任务一入口，只写来源→目标与权威状态，不填猜测的待转头数；目标未指定时写“接收位置待确定” | 本地 GraphQL/旧 Web/领域状态已核对；MockWebServer 和模拟器 fake 已验证；真实 dev 查询及新口径未验证 |
| 首页出栏任务入列与范围 | 首页 → 出栏任务卡及执行入口（拟接）。按明确执行起点和稳定单元范围展示目标数、已出栏数及待出栏数。 | `farm.view_outbound_task` 有 `PLANNED`／`RUNNING` 等状态、选择模式、选择项和执行记录，但没有计划时间、指派状态或任务单元；选择项仅有批次、标签、目标数量和可选指定猪只。旧 Web `pigOutboundTaskMatchesLocation` 用指定猪只的**当前** `location_code` 或已出栏记录的 `from_location` 做前缀匹配；标签选择可能没有指定猪只 | 不能证明 `PLANNED` 已安排且现在可执行，也不能把已出栏猪只的历史位置或当前猪只位置当作整项任务的稳定范围。目标数量和已出栏数无法拆成各单元此刻待出栏数；若只按有位置的记录过滤，会漏掉未执行目标 | 后端提供执行起点／指派状态、稳定 section/unit 范围，以及按单元的目标与已出栏数量或逐头目标归属。Android 暂不把出栏任务列入首页；已有执行记录不能让范围不明的任务出卡，亦不按 `created_at` 代替执行时间 | 本地 GraphQL schema、旧 Web 过滤与领域模型已核对；真实 dev 数据及新字段未验证 |
| 巡检设备报障照片、故障身份与重报 | 巡检 → 栏位详情 → 报告设备故障。填写设备、描述和照片，形成独立故障记录；解决后重报保留历史。 | `ReportDeviceInspectionCommandStrawberryInput` 只有栏位、设备类型/名称、状态、描述及可选客户端 `inspection_id`，无照片。`DeviceInspectionId.create` 给 Feed/Water 固定 `{pen_code}_feed/water` ID，Other 用毫秒时间戳；上报 handler 无开放故障核查，`DeviceInspection.create` 每次都生成新上报事件，投影对同 ID 执行 upsert，并保留已有 `resolved_at`。原生 `DeviceInspectionReads` 目前按栏位列出最新投影行 | 照片无法保存或读回；固定类型设备的重复上报／解决后重报会复用同一记录 ID，覆盖旧上报信息并保留旧解决时间，不能无损呈现设计要求的故障历史。Other 的自由文本名称不能证明是同一设备，毫秒 ID 也不是稳定客户端操作身份。仅凭本机旧快照禁止重复报障不能解决并发与断网问题 | 后端提供照片附件及读回；为每次故障提供稳定事件/记录 ID 与设备身份、可查询的当前开放故障、原子防重复规则，并明确已解决后重报如何保留历史。写入需客户端操作 ID、幂等和按 ID 确认；用户已确认 Android 先完成图片选择/预览/移除的前端逻辑，待接口支持后再接照片上传与完整离线报障。当前禁用提交；Android 已可显式保存按账号／厂区／单元／栏位隔离的本机草稿，照片复制到私有存储后再原子替换表单，重进可恢复。草稿未入上传队列，不当作已提交／待同步报障；未保存修改仍仅在本单元会话内保留 | 旧后端 ID 生成、handler、ORM/投影及本地 GraphQL 契约已核对；真实 dev 行为和新契约未验证 |
| 解决故障的离线目标身份 | 巡检 → 栏位 → 故障详情 → 标记已解决（拟接）。离线处理用户看到的那一次故障，防止旧指令误作用于新故障。 | 后端 `biosafety_v1/domain/models/inspection.py::DeviceInspection.resolve` 对已正常状态拒绝再次解决；`ResolveDeviceInspectionCommand` 和 GraphQL 输入只带 `inspection_id`。旧 Feed／Water 默认 ID 由栏位＋设备类型生成，重复上报可能覆盖同 ID 的投影；解决投影仅以 ID／厂区及上报时间不晚于事件时间更新 | 同一旧记录 ID 先解决、再重报后，较早离线排队的解决命令若晚到，将可能解决新的那次故障。仅以 Normal 状态读回也不能证明处理的是用户当时看到的那次；“已解决”错误文本不能充当本次回执 | 每次故障使用不可复用的实例 ID；解决输入绑定实例／预期版本或上报事件 ID，过期目标返回明确冲突，支持客户端操作 ID 和按 ID 查询本次结果。未具备前 Android 不开放解决提交或自动重放，不修改现有故障状态 | 2026-10-26 按本地后端 `4c1a2ff4` 的命令、领域模型和解决投影重新核对；未在真实 dev 制造新故障或执行解决操作，新后端分支／接口文档待提供 |
| 巡检名单阶段天数 | 巡检名单的猪只行、单猪详情。显示当前生产阶段及进入该阶段的天数，不使用猪龄替代。 | `NativePigProfiles` 现读取 `current_breeder_status.production_status/status_updated_at`；后端 `production_v2/domain/models/batch.py` 在批次猪只修改时也会更新 `status_updated_at`。当前 dev 账号已读到厂区时区；Sentri-UI `inspection.walk` 要按阶段起点显示天数 | `status_updated_at` 不能单凭字段名证明是该阶段首次开始，重复同状态修改可能重置它；设备日期也不能代替厂区日界线。用生日或最近配种／分娩时间统一推算会错 | 后端提供权威 `stageStartedAt`（或证明现有字段严格等价）及厂区时区，并覆盖各生产阶段。Android 现在仅显示后端明确的阶段名称加 `—`，不显示猜测天数；旧快照兼容新增时间字段 | GraphQL 字段、后端写入源码、MockWebServer/JVM 与模拟器旧快照测试已核对；dev 厂区只读搜索通过，阶段起点语义仍未确认 |
| 巡检病症 Care 状态 | 巡检 → Health 视图／猪只病症标签／护理筛选（逐条护理拟接）。每条病症独立显示和修改护理要求。 | `PigHealthProfile` 当前读取 `triage`、`triageChangedAt` 和疾病／症状关联；旧健康事件另有 `TriageLevelSet` 的猪只级值。Inspection 原型按每条 condition 的 Care 状态决定紧急、观察或持续标签 | 旧 triage 是猪只级字段，不能安全映射成逐条病症的 Care。dev Unit 2 的一头猪同时有两项疾病、一项症状，旧分诊值仍为 Healthy，说明仅凭该字段不能推断任一病症的处理状态；红色待处理标签可能误导 | 后端提供逐条病症的 Care 字段与稳定记录身份，并明确旧数据迁移口径。Android 只读展示明确标注的旧猪只级分诊及历史值，病症保持中性文案，不推断紧急程度或填入首页 Health 统计 | 原型、本地读取及 2026-10-23 dev 单猪只读页面已核对；新契约待定 |
| 首页 Health 关注数与名单 | 首页 → Health 关注入口及跨单元名单（拟接）。按逐条护理状态汇总需处理、观察中的猪只，点开查看对象。 | Workbench PRD 要按分区统计逐条 Care 为 Treat／Monitor 的猪只，并排除已治疗或已移走对象；当前 `PigHealthProfile` 只有猪只级 `triage`、病症关联和当前猪只位置，没有逐条 Care、待处理起点及与治疗记录的可核对关系 | 旧 triage 不能生成 To treat／Monitoring 数量，也不能判断“从昨天等待”、同猪多条发现是否去重或治疗后何时退出名单；首页错误的 0 会误导用户 | 后端提供按发现 ID 的 Care、记录时间、处理状态、治疗／转舍关联与权威 section/unit 归属，明确同猪计数和退出规则；Android 当前显示数据不可用，不计算虚假人数 | Workbench 2026-10-19 规则与本地 GraphQL 模型已核对；新契约、真实 dev 数量未验证 |
| 巡检栏位备注与记录 | 巡检 → 栏位详情 → 栏位备注。已接填写、保存／恢复／清空本机草稿；后续接当前备注读取、正式编辑／关闭／恢复及本栏历史。 | Sentri-UI `inspection.pen-note` 要求备注归属单个栏位，编辑覆盖当前备注、关闭进入栏位日志；旧版 `SubmitUnitRemark` 与 `farm_view_unit_remark` 只以 `unit_code` 定位，旧页面 `UnitInfoCard.tsx` 也是单元备注 | 直接复用会把一栏位的备注显示给同单元其他栏位，且缺备注 ID、编辑/关闭/恢复和逐条历史；无法实现 `inspection.pen-note` / `inspection.pen-log` | 后端提供 pen ID/code 范围内的当前备注与独立记录 ID、版本、编辑、关闭／恢复操作及按栏位查询日志；Android 已提供按账号／厂区／单元／栏位隔离的本机备注草稿。草稿不进上传队列、不自动提交，不当作当前服务端备注；正式保存、关闭和历史仍未接，也不把旧单元备注冒充栏位备注。 | 已核对旧单元备注的范围；本机 SQLite 重开、范围隔离、状态恢复和页面流程定向通过。真实逐栏接口、正式写入及历史待后端。 |
| 巡检扫码跨单元转舍 | 巡检 → 扫码／查找 → 其他单元猪只 → 转入当前舍／转入并选择。选登记目标栏位，确认转舍成功后再更新选择。 | 用户已确认沿用旧版的“转入当前舍／转入当前舍并选择”两种选择；`OutboundSearchResultPopup.tsx` 提供两动作，调用方 `OutboundFieldDetailPopup.tsx` 把目标栏位 ID 传给 `locatePig`，仅成功后把猪只加入选择。现有 `NativeTransferPig` 输入只有猪只 ID 和 `destination`，GraphQL `transfer_pig` 的 `recordId` 是猪只 ID，非本次操作 ID（`production_v2/entrypoints/graphql/pig.py`） | 响应丢失后不能按这次操作核对是否成功；自动重放可能重复转舍，且旧位置的异步快照不能证明“转入并选择”已生效。只选单元而不选栏位也不能构造旧命令 | 增加客户端操作 ID、幂等、按操作 ID 查询结果、目标栏位/权限核验，以及源位置或版本前置条件。Android 先展示两种动作与已登记目标栏位选择；提交禁用，不更新位置或巡检选择。契约齐备后先确认转舍，再按最新范围加入选择 | 旧 Web、原生 GraphQL 和后端入口源码已核对；真实发送未执行，离线行为待新契约验证 |
| 健康病症记录身份与结果 | 巡检 → 单猪／多猪操作 → 记录、编辑、解除病症（写入拟接）。独立保存每条病症，区分康复与误录并保留历史。 | `PigProfile` 疾病/症状关联有目录 ID、描述和创建时间，但没有逐条记录 ID、Care、恢复/误录状态；`pig_health.py` 的增删回执 `recordId` 实际是猪只 ID | 新版 `health-record.finding`、`edit-finding`、`resolve-finding` 无法定位、修改或核对某一条发现；离线重试也不能凭猪只 ID 判定具体写入结果 | 为每条发现提供稳定 `findingId`、病症类型、Care、状态和版本；增删改回执返回该 ID，并支持按客户端操作 ID 查询结果。当前界面只读显示已记录病症，不把旧 `recordId` 当发现 ID | 旧源码与原生契约已核对；部署环境未验证 |
| 健康病症重复写入 | 巡检 → 多猪记录病症、离线恢复重试（写入拟接）。已有病症保持原开始时间，不因重复点击／重试重复创建。 | 旧版 `healthy.py` 对重复疾病抛错，对重复症状直接成功但不新增；`pig_health.py` 回执没有说明是否发生变化 | 同一种离线重试可能分别变成错误或虚假的“保存成功”，无法一致确认用户新建的发现 | 后端统一重复/幂等规则；回执带具体发现 ID、是否新建及客户端操作 ID。语义确定前不从成功回执推断新发现已产生 | 旧源码已核对；真实环境未验证 |
| 统一病症目录 | 巡检 → 病症目录及记录／治疗中的病症选择器。统一搜索疾病与症状，支持分类、别名、子类型和厂区自定义条目。 | 当前 `NativeHealthReferenceCatalog` 只有 disease/symptom 两类平面项目，字段为名称、简写、单一分类/部位、描述；Sentri-UI `features/conditions/PRD.md` 设计统一搜索、事件类、别名、多分类、症状子类型和疑似报告疾病提示 | 旧目录无法准确生成新版选择层级，也不能把“发热/热感”等别名或一病多系统归类；按旧字段硬编码会在真实目录变化后失真 | 明确目录 schema、项目唯一 ID、类型、别名、多个分类、子类型、报告属性和适用猪群；本阶段读取现有平面目录供核对，不把它标为新版完整选择器 | 新设计为 skeleton；旧查询字段已核对，需产品定稿与后端协作 |
| 临时治疗创建与离线确认 | 巡检已选猪只／单猪操作／病症详情 → 记录临时治疗。按猪保存实际剂量，离线恢复后按原操作确认，不生成计划疗程任务。 | 旧 `CreatePrescription(is_adhoc=true)` 在 GraphQL 入口自行生成 `serial_number`，输入没有客户端操作 ID 或处方 ID；`CreatePrescriptionInput` 的一条 therapy 参数只有一个共享剂量、方法 ID、单位和可选 `medication_catalog_id` | 断网后初次发送可排队，但响应丢失时无法按客户端 ID 查询是否已创建，不能安全自动重发；同批不同猪剂量无法在一条旧命令中表达 | 增加客户端幂等/操作 ID 与按 ID 查询结果；支持每头猪的给药剂量；用户已确认最终对象全体成功或全部不写，冲突返回具体猪只。增加前端给药时间字段并按传入值保存，接收时间另存。未补齐前不把旧处方 mutation 接成新版临时治疗的离线保存 | 旧 Web 表单及后端 GraphQL/命令源码已核对；部署环境未验证 |
| 临时治疗药品与休药期 | 治疗表单 → 药品／品牌选择；首页 → 工具箱 → 药品。维护目录、品牌与休药期，治疗记录保留当时的名称和休药快照。 | 当前 `NativeTherapyMedications`、`NativeTherapyMedicationCatalog` 只返回平面药名及制剂名；`CreatePrescription` 没有品牌独立身份、药品分类/别名、默认/品牌休药天数及记录时休药快照 | 新版药品分类选择、品牌、按标签计算休药截止日及历史记录快照不能由旧字段无损产生；不能使用原型样例天数当正式兽药数据 | 后端提供药品与品牌目录关系、可核实休药期、编辑权限和治疗记录时的快照字段；Android 当前从巡检可进入只读目录支持的表单预览，给药方式复用旧目录的名称、具体方式和稳定 ID，单位仍标为原型样例，保存禁用；正式治疗写入等契约明确 | 新版 `treat` 为 in-design；2026-10-22 当前 dev 厂区真实读取旧药品 57 项、旧制剂 0 项且缓存恢复通过；2026-10-26 dev 给药方式目录 22 项读取及缓存读回通过；方式目录并非新版按药记忆或厂区配置，单位仍为前端样例；品牌／休药期新契约与真实治疗写入未验证 |
| 巡检跨单元查找的全局排序 | 巡检 → 查找猪只。跨页保持当前单元优先、耳标前缀优先于包含／栏位匹配，并返回准确总数。 | `inspection.find-pig` 设计要求当前单元优先、耳标前缀优先于包含匹配、最后才是栏位匹配；现有 `NativePigProfiles` 按厂区与关键词过滤，但 `GraphqlPigProfiles.order(LOCATION_IDENTITY)` 只有位置、耳标和 ID 升序，`offset/limit` 在客户端分组前执行 | 当前单元的匹配可能落在后续页；仅对已取的 30 条排序不能保证整个结果集的先后，也不能证明耳标前缀优先。把当前页标题写成全结果顺序会误导 | 后端提供可分页的匹配来源／相关性排序并支持当前单元优先，同时稳定返回总数和位置身份。Android 先按厂区完整搜索，30 条一页，页内按单元分组、当前单元组在前，再按耳标前缀／包含／栏位匹配排列；明确保留分页，不声称全结果排序已完成 | Sentri-UI `main@717beb3`、原生 GraphQL 构造和 MockWebServer 已核对；真实 dev 只读查询通过，排序新契约未验证 |
| 巡检批次关系与批次筛选 | 巡检 → 筛选 → 批次、猪只当前批次信息；栏位 → 设置头数的批次名称；后续批次任务跟踪。显示同厂可核实批次，按批次过滤完整名单。 | `NativePigProfiles.current_batch_id` 是猪只字段，`current_batch` 关系可返回批次 `id/sn/farm_id`；此前 Android 未读取关系的 `farm_id`。同类关系在 dev 免疫读取中曾出现跨厂批次。Sentri-UI `inspection.filters` 内部有 batch filter，但入口依赖仍为 `placeholder` 的 `inspection.batch-detail` | 只凭批次序号或 ID 相等不能证明关联批次属于当前厂区；旧本机快照也没有 `farm_id`。跨厂序号若进入筛选会显示错误批次信息，按批次推断任务进度更无依据 | 后端保证猪只当前批次关系的厂区一致性，并提供可核对的批次任务范围与进度。Android 新读取 `farm_id`，只有 `id == current_batch_id` 且 `farm_id == pig.farm_id` 的关系才显示批次序号和筛选项；旧快照缺归属证明时保留猪只档案但不提供该批次筛选。盘点另复用 `NativeProductionBatches` 按当前厂区＋准确批次 ID 补读，独立缓存并标识旧资料；查无、归属不符或读取失败时保留组数但不开放该组调整，不用档案序号补猜。批次任务跟踪仍不展示 | 本地 Schema、GraphQL 映射、旧快照兼容与 MockWebServer 跨厂／ID 不一致测试通过；API 36 筛选及缓存定向 3 项通过；2026-10-26 真实 dev 只读核对：3 个单元共 163 头，94 头有当前批次 ID；这 94 条关系均存在且 ID 一致，但 farm_id 全为空，可核实批次为 0，故筛选不出批次选项；后端需补齐厂区归属。随后对 Unit 2 Pen 2 的 1 个已关联批次组做当前厂区＋ID 定向查询，返回空；只证明当前身份／厂区下不可读取，未证明批次被删除或具体故障原因。需同时核对批次投影、厂区关系与读取权限；盘点写入仍禁用 |

新增缺口需附页面、输入/返回字段、源码或线协议证据、安全降级、验证环境和结果。不要以假数据填补接口缺口。

## 免疫目标身份冲突的后端定位

`healthy.py` 中 `project_vaccination_task_created` 同时写任务头和第 1 剂目标的疫苗、品牌；`project_vaccination_task_modified` 目前只更新 `MatvVaccinationTask`，未见同步修改仍为 `PENDING` 的目标行。这是冲突的一个**可能成因**，尚未从事件历史证明当前 dev 的异常就是由该路径造成。后端应核对领域修改事件、投影和实际记录，明确任务修改后未执行剂次如何保持一致，并保留已完成剂次的历史身份。

在对应厂区的数据库中可用以下只读查询定位受影响目标；`:farm_id` 由后端安全传入，勿在仓库记录真实标识。修复数据及投影后，重跑显式的 `LivePigReadTest#developmentVaccinationHomeCatalogRead` 验证完整目录。

```sql
SELECT t.id AS task_id, d.target_id, d.dose_index,
       t.vaccine_id AS task_vaccine_id, d.vaccine_id AS target_vaccine_id,
       t.brand_id AS task_brand_id, d.brand_id AS target_brand_id
FROM farm.view_vaccination_task AS t
JOIN farm.view_vaccination_task_target AS d
  ON d.task_id = t.id AND d.farm_id = t.farm_id
WHERE t.farm_id = :farm_id
  AND t.status IN ('PENDING', 'IN_PROGRESS')
  AND d.status = 'PENDING'
  AND (t.vaccine_id IS DISTINCT FROM d.vaccine_id
       OR t.brand_id IS DISTINCT FROM d.brand_id)
ORDER BY t.id, d.target_id, d.dose_index;
```

<a id="backend-contract-proposal"></a>

## 新契约提案（评审稿）

2026-10-26。依据：Android 当前实现、Sentri-UI `main@f66affd`，首页连接式任务设计 `codex/workbench-home-split@3b2d137`，只读后端 `4c1a2ff4`。下列名称是逻辑接口建议，后端可沿用现有 GraphQL namespace；不得把它们当作当前 Schema 已有字段。新增接口通过后端评审和部署后，Android 才生成实际 operations 并开放相应写入。

### 1. 共用写入与结果确认

应用位置：所有需要离线保存的业务按钮，包括记录病症、治疗、报障、解决故障、栏位备注、盘点和转入当前舍。功能：同一次保存只产生一次业务结果，断网重开或响应丢失后仍能核对。

| 建议接口 | App 应用位置 | 功能与最小契约 |
| --- | --- | --- |
| `operationResult(client_operation_id)` | 业务页的本机记录状态、后台同步 | 返回这次操作的持久结果；只允许原账号／厂区作用域查询。不能用“当前值相同”替代这次操作的回执 |
| 各写入共用 `command_context` | 每次用户点击保存时生成并持久化 | `client_operation_id`、`command_type`、`schema_version`、`payload_hash`、`client_recorded_at`、时间来源标记、稳定业务 payload。首次保存和所有重试沿用同一 ID 和内容 |
| `mobileCapabilities` | 登录／选择厂区后，业务入口准备 | 返回协议版本、各写入能力及数量／附件限制。未声明能力时保持只读，不用异常文本猜是否支持 |

共同约束：

- 厂区、记录人和权限由资源 JWT 决定，不由 App 添加管理角色或厂区 Header。输入的猪只、栏位、病症、药品、附件均须在服务端校验作用域。
- 后端在同一事务中保存业务变更和操作回执。`(farm_id, client_operation_id)` 唯一，并绑定原记录人；重复相同请求返回原回执，不再次生成事件。相同 ID 携带不同内容返回 `OPERATION_PAYLOAD_MISMATCH`，不得覆盖原操作。
- 回执至少含 `client_operation_id`、`command_type`、`farm_id`、`actor_id`、`payload_hash`、`status`、`accepted_at`、`applied_at`、`results[]`。每个 result 含对象类型／ID、业务记录 ID、结果版本及 `APPLIED` 或 `UNCHANGED`；无新增事件的 UNCHANGED 明确返回 null 及原对象身份。不能只返回任务 ID。
- `payload_hash` 对双方约定的规范业务内容计算，由后端独立重算核对；编码规则与一致性样例随协议一起交付。数组顺序、十进制字符串及记录时间在入队后冻结。令牌、语言、重试次数不属于业务内容。
- 服务端操作状态建议为 `PENDING`、`APPLIED`、`REJECTED`、`CONFLICT`。`PENDING` 只是已受理；`APPLIED` 才表示事务已提交。查询暂无结果必须与拒绝、无权限和暂时查询失败区分。
- 投影若异步更新，回执带 `projection_revision` 或每对象版本；读取提供可比较的版本。Android 在回执与定向读取均一致后才记服务端确认，不能因投影暂未更新重发写入。
- 响应丢失后先按操作 ID 查询。只有该接口已证明按 ID 去重时，才允许按相同 ID 重试；查询暂无结果本身不足以批准旧接口自动重放。
- 操作去重记录的保留期必须覆盖客户端可能恢复的未完成队列；过期操作应明确拒绝或进入人工处理，不能当成全新操作再次执行。
- 冲突返回结构化 `code`、受影响对象、期望／当前版本和可展示原因；至少区分位置变化、对象已不在群、版本变化、目录条目已删除、附件未就绪、已有开放故障及内容不匹配。不依赖 `err_msg` 文本推断成功。

2026-10-26 用户已确认：首版多猪记录病症和临时治疗采用原子保存，最终确认对象要么全部成功，要么全部不写；冲突返回具体猪只和原因，保留草稿供调整。用户可在提交前明确排除对象，不能在提交时静默缩小范围。相同内容的重试沿用原操作 ID。只有原命令尚未发送且已在本机原子撤销，或服务端已确认冲突／拒绝且全部未写，才能修改目标或内容后用新操作重提；原结果不明或 PENDING 时先核对，不能靠换 ID 绕过。已 APPLIED 的记录需要领域更正流程，不能通过再次新建冒充修改。其他批量操作的事务范围另按领域评审，不能自动套用“允许部分成功”。

### 2. 健康记录与病症目录

应用位置：巡检猪只行 → 病症详情；已选猪只 → 操作 → 疾病或症状／护理／解除；只读病症目录及治疗中的病症关联。功能：每头猪的每条病症有独立身份、护理状态和历史，重复记录不重建。

| 建议接口 | App 应用位置 | 输入／返回要点 |
| --- | --- | --- |
| `conditionCatalogue` | 病症目录、记录病症及治疗关联的选择器 | 稳定条目 ID、种类、双语名称／简称／别名、分类 ID 集合、可见子类型、适用猪只类型、目录版本；分页搜索与分类读取保持同一条目身份 |
| `createFarmCondition` | 选择器无匹配 → 添加病症 | 名称、分类 ID、共用操作上下文；返回厂区共享条目。权限与类别合法性由服务端核对 |
| `pigFindings` / `findingHistory` | 猪只病症列表、单条详情与历史 | 独立 `finding_id`、猪只／厂区 ID、目录条目／子类型、`care`、起始时间、关闭结果、记录人、版本及关联治疗／饲喂调整 |
| `recordHealthFindings` | 单猪／多猪 → 记录病症 → 保存 | 明确猪只 ID、病症条目／子类型、Care、可选备注／附件 ID；返回每头新增或未改变的 finding ID，不另建已有开放病症 |
| `setFindingCare` | 病症 → 护理；多猪统一护理 | `finding_id`、`expected_version`、新 Care、备注；返回新版本及真实变更。不能修改旧猪只级 triage 来替代 |
| `resolveHealthFindings` | 单条／多猪 → 解除病症 | finding ID、期望版本、`RECOVERED` 或 `ENTERED_IN_ERROR`、备注；关闭后保留历史，不删除原记录 |

`care` 对应已设计的四个选项：`NO_ACTION_NEEDED`、`MONITOR`、`TREAT_IN_PLACE`、`MOVE_TO_HOSPITAL_PEN`。转医院栏只是护理要求，不自动转舍；关注状态从 Care 派生。2026-10-26 用户已确认：新增病症 Care 不设默认，保存前必须主动选择；写接口要求显式且有效的 Care，不得把缺省值自动填成 NO_ACTION_NEEDED。明确选择“无需处理”仍合法；查看／编辑已有记录时保留原 Care，不重置为空。

同一头猪已有同一开放病症时，重复“记录”返回 `UNCHANGED`，保留原开始时间和原 Care；修改护理使用独立操作。康复后再次出现应形成新的 finding，而不是复活旧病例。子类型是否替换同一病例须随目录身份规则一并确认。

`RECOVERED` 若要结束关联饲喂调整，后端应在同一业务事务完成并返回相关调整结果；不能由 App 先解除、再盲发第二条结束命令。误录关闭如何处理关联调整需要明确规则。

旧健康数据的迁移须保留原来源、时间及记录人。没有逐条 Care 的旧记录仍返回未知／旧数据标记，不能批量伪造为“无需处理”；原猪只级 triage 可保留独立只读字段。

无耳标群体、后续数量覆盖／累加、病症折叠、报告类疾病后续流程仍属于设计未决项。接口结构可预留群体目标，但首版不能把群体数量展开成虚构 pig ID，也不能直接开放未确认的群体写入。

### 3. 临时治疗及药品／品牌

应用位置：巡检已选猪只／单猪操作／病症详情 → 记录临时治疗；首页工具箱 → 药品。功能：准确记录已经给了什么，每头猪可有不同剂量，离线后能确认原记录。它不创建计划疗程或疫苗任务。

| 建议接口 | App 应用位置 | 输入／返回要点 |
| --- | --- | --- |
| `medicineCatalogue` | 药品选择器、工具箱药品页 | 分类、稳定 medicine ID、双语名称／别名、内置或厂区新增标记、可编辑权限、可核实的默认休药期及版本 |
| `medicineBrands` | 治疗表单 → 品牌；药品页 → 品牌 | 品牌 ID、所属 medicine ID、名称、可空的休药期覆盖值、版本与是否仍可选择；旧制剂 ID 不自动变成品牌 ID |
| `farmMedicineRecents` / `medicineDefaults` | 药品选择器最近使用；选药后预填 | 厂区共享、去重的最近使用药品，以及上次成功使用的方法／单位；不能把未保存草稿当作最近使用 |
| `treatmentMethodsAndUnits` | 给药方式、剂量单位选择 | 稳定 code／ID、双语名称、是否可用及版本。现有方法目录可保留 ID；原型单位需正式配置或明确认可后才用于保存 |
| `saveMedicine` / `saveMedicineBrand` / `archiveMedicineEntry` | 药品页新增／修改／删除 | 共用操作上下文、期望版本和可编辑字段。内置药名不可改删；删除应退出选择器，历史名称不变 |
| `recordAdhocTreatment` | 治疗表单 → 保存 | 见下面输入；返回每头治疗记录 ID、实际关联 finding、记录版本及休药快照，不生成开放待办 |

建议保存输入：

```json
{
  "command_context": {
    "client_operation_id": "<stable-client-operation-id>",
    "command_type": "RECORD_ADHOC_TREATMENT",
    "schema_version": 1,
    "payload_hash": "<hash of frozen canonical business content>",
    "client_recorded_at": "<UTC timestamp fixed when saved locally>",
    "time_source": "DEVICE"
  },
  "administered_at": "<UTC timestamp fixed on the client when saved locally>",
  "medicine_id": "<medicine-id>",
  "brand_id": null,
  "method_id": "<method-id>",
  "unit_code": "<approved-unit-code>",
  "catalogue_revision": "<revision>",
  "targets": [
    {
      "pig_id": "<pig-id>",
      "expected_unit_id": "<unit-id>",
      "dose": "<positive decimal string>",
      "finding_ids": []
    }
  ],
  "note": ""
}
```

共享剂量只是表单交互；入队时展开成每头确定的正剂量。备注最多 200 字；品牌和病症关联允许为空。2026-10-26 用户已确认：首版只可从已选猪只已有病症中选择一项关联，也可不关联；治疗内不新增病症。所选病症只关联实际具有该 finding 的猪只，其余猪只仍接受治疗且关联为空；从单条病症详情进入时固定关联。移除唯一有该病症的猪只后清除可选关联。客户端不能以病症名称或旧目录 ID 代替 finding ID，后端需验证逐猪关联存在且仍有效；不允许保存治疗时顺便创建 finding 或补默认 Care。服务端须确认 finding 属于同猪同厂，不能仅按名称关联。

每头记录保存当时的 medicine／brand 名称、实际给药方式、单位和剂量；休药快照包含生效天数、来源（药品默认／品牌覆盖／未设置）、基准时间与截止时间。未设置与 0 必须不同。目录后续修改不回算过去记录；任何样例休药天数不得随部署自动当正式值。

2026-10-26 用户确认：服务端以前端传入的给药时间为准。建议明确字段 `administered_at`：首次本机保存时固定，进入队列、重启与重试均保留原值；同一次给药因明确未写的冲突而调整后重提，也保留原给药时刻，新操作的本机记录时间可另记；服务端按此字段保存给药时间，并作为休药期计算的时间基准。`accepted_at`／`applied_at` 另记服务端接收／提交时刻，不替换或向后推迟给药时间。不得仅因离线晚到自动改用联网时间，也不新增“手机错钟须人工核对”的业务门槛。格式、时区偏移及权限仍需验证；发现客户端取时错误属于客户端需修复的问题，不能由服务端悄悄改写。表单不增加任意补录日期控件。

### 4. 栏位备注、盘点与历史

应用位置：单元存栏／巡检 → 栏位详情。功能：本栏备注独立于猪只，盘点有来源与原因，历史能看到真实当时位置。

| 建议接口 | App 应用位置 | 输入／返回要点 |
| --- | --- | --- |
| `penWorkspace` | 栏位详情首屏 | 厂区／单元／登记栏位稳定 ID 与编码、当前备注、开放故障、数量版本、可执行能力；空栏同样可查 |
| `savePenNote` | 栏位备注 → 正式新增／编辑（拟接） | 栏位 ID、当前备注 ID／期望版本（新建核对栏位备注位版本）、内容和操作上下文；返回备注 ID、新版本、服务端时间／记录人及历史事件 ID。每栏只允许一条当前备注 |
| `closePenNote` / `reopenPenNote` | 当前备注 → 关闭；关闭结果 → 撤销关闭（拟接） | 备注 ID、期望备注版本、栏位备注位版本和操作上下文；原子关闭／恢复并返回历史事件。撤销时若已有新备注，返回冲突，不能覆盖新内容 |
| `penLog` | 栏位记录 | 稳定事件 ID、类型、时间、记录人、对象／照片和分页游标；猪只事件按事件发生时的栏位归属，不按猪只现在的位置回填 |
| `penPopulation` | 存栏／当前头数／设置头数 | 已建档个体数量、各批次及未关联批次的未标识群体（稳定 group ID、可空 batch ID、批次序号与数量）、总数、统计版本；提供同厂同生产线可选批次，不能把档案行数当实际全部存栏 |
| `reconcilePenPopulation` | 设置头数 → 原因 → 保存 | 栏位 ID、期望数量版本、按稳定群体 ID 的批次／未关联批次数量、可空原因 code、Other 必填说明；原有组保留或显式传 0，新建的空组不提交；返回原值、新值、差值、审计事件 ID 和新版本 |

全为已建档个体的栏位不通过盘点直接改数量；混合栏位只编辑无耳标余量，总数不能低于已建档个体数。不能因盘点差值自动删除猪只、登记死亡或转舍。未关联批次不能被过滤或默认归到其他批次；总数相同但批次分配不同也属于真实变更，应独立核对和保留历史。2026-10-26 用户已确认：调整后一次保存；原因选填，Other 必须补说明。加减和关闭不写服务端，整次调整用一个操作身份；原有组降至 0 需显式提交 0，不能靠遗漏表达清零。原因 code 和批次变更规则仍需在开放提交前确定。

旧单元备注不能直接迁移成本栏备注；没有可靠栏位归属的旧事件应保留为单元历史，不编造具体栏位。备注历史保存每个版本的内容、原记录人与实际操作者；不能只在当前卡片上覆盖作者而丢失旧版本。Android 目前只保存本机草稿，不从草稿时间或当前登录人生成服务端作者／历史。

### 5. 照片、设备故障与解决

应用位置：栏位 → 报告设备故障 → 选图／移除／提交；故障详情 → 标记已解决；栏位记录 → 查看照片。健康记录需要照片时复用附件协议。

| 建议接口 | App 应用位置 | 输入／返回要点 |
| --- | --- | --- |
| `prepareAttachment` / `completeAttachment` / `attachmentResult` | 选图后持久保存、后台上传 | 稳定客户端附件 ID、用途、媒体类型、字节数、校验摘要；返回同厂附件 ID、持久状态和可核验摘要。上传中断或回执丢失可按 ID 查询 |
| `penDevicesAndFaults` | 栏位设备选择、开放故障列表／详情 | 稳定 device ID、类型／名称、故障实例 ID、状态、记录版本、上报／解决时间、记录人及附件引用 |
| `reportPenFault` | 报障表单 → 提交 | 登记栏位 ID、设备引用、必填描述、附件 ID 数组、共用操作上下文；返回新故障实例与栏位事件 |
| `resolvePenFault` | 故障详情 → 标记已解决 | 故障实例 ID、期望版本、可选解决备注、操作上下文；返回该实例新状态和解决事件，不能只绑定设备类型 |

一台设备与一次故障是两个身份。设备可多次故障，每次生成新实例；不能继续用 `<pen>_feed` 当所有历史故障的身份。并发上报同设备开放故障应原子返回明确冲突及已有实例，不能覆盖旧历史。较早的离线解决只能命中原实例，不能解决后来新报的故障。

Feed／Water 由栏位配置返回稳定设备 ID。Other 可引用已有设备；新建时建议由一次报障事务接收 `new_device: {client_device_id, name}` 并创建稳定设备身份，不用自由文本名称或毫秒时间戳推断“同一设备”。同名设备的去重规则留待评审。

首版 App 当前上限为 12 张，本机复制另有单张 20 MiB 存储保护；最终以能力接口给出的限制为准；后端需明确允许类型、单文件大小和总大小。Android 本机草稿已把选图复制到私有持久存储，并和表单一起保存；正式提交仍需原子保存业务命令及附件依赖，不能只把临时选择器 URI 当作离线照片。

附件先被确认可用，业务命令才发送；报障回执和读回须包含相同附件 ID，只有上传成功不能显示“报障已同步”。附件访问需要作用域校验；如采用临时 URL，读取须支持重新获得地址。未被业务引用的文件清理期限与失败恢复规则由后端明确，不能删除尚在离线队列中等待引用的文件。

### 6. 扫码转入与选择

应用位置：巡检扫码／查找 → 同厂其他单元猪只 → 转入当前舍／转入并选择 → 目标登记栏位。功能：移动正确的猪只，成功后按用户原选择决定是否加入巡检选择。

建议 `transferPigWithinFarm` 输入：共用操作上下文、pig ID、期望来源单元／位置版本、目标单元／登记栏位 ID；回执返回本次转舍记录 ID、原位置、新位置和新版本。权限、在群状态及目标栏位由服务端核对。

`select_after_transfer` 是 App 的本地后续意图，随命令持久化，不作为后端“已选择”的业务事实。本机保存不改变服务端位置，也不把猪只提前加入当前舍已选。原操作确认后再刷新位置；只有仍在原账号／厂区与当前巡检单元时才执行本地选择，不在后台切换用户的页面。

### 7. 首页与巡检读取补充

| 建议补充字段／读取 | App 应用位置 | 用途与返回口径 |
| --- | --- | --- |
| `read_context { server_now, farm_timezone, snapshot_revision }` | 首页日期、任务入列、阶段日数 | 复用当前 dev 已支持的 `farm_info.timezone`；补可信当前时间和快照基准，其他部署先核对支持情况。离线保留快照时间，不继续把旧快照描述成当前值 |
| 任务目录的 `work_type`、`section_ids`、`unit_ids` | 总览／单元任务、选择器计数 | 明确任务实例类型与稳定范围；转出或目标暂空不能导致任务丢失。计划治疗明确 `is_adhoc=false` |
| 任务开始／结束与排程、明确截止、结束原因 | 今日工作、未来七日、置灰结束卡片 | 生产任务已读 `actual_start_time`／`actual_end_time` 和计划起点，不重复造字段；补目前缺失的明确截止、结束原因及各来源一致口径。到开始时间入列、按明确截止判断逾期；进行中保留，不因当前待做数为 0 被隐藏。今日结束还需厂区日期基准与可靠范围 |
| 按任务类型提供当前动作／逐次记录身份、窗口与聚合 | 各类任务卡和详情 | 分开返回成员数、当前待处理数、逾期数和已记录数；未知返回 null，不把成员总数叫待处理数 |
| 分页稳定快照、完整实例总数／各类总数 | 多任务折叠、分区／单元选择 | 总数是去重任务实例数；全结果排序后分页。跨页应可核对同一快照，不把第一页当完整总数 |
| `healthAttention` 按逐条 Care 汇总并按猪只去重 | 首页 Health 关注数和跨单元名单 | 返回需处理／观察的实际猪只和单元；同猪多个 finding 不重复算猪只，旧 triage 不参与新版计数 |
| `effectivePigFeed` / `effectivePenFeed` | 巡检 Feed 视图和栏位饲喂 | 返回当前生效计划、固定量／系数／停喂、有效期和计算时间；不是单独返回历史调整行 |
| `stage_started_at`；可靠的批次厂区关系 | 巡检猪只行、阶段／批次筛选 | 提供真实阶段起点；批次 ID、关系 farm ID 与猪只一致。重复修改同一阶段不能重置起点 |
| 查找的当前单元优先／匹配级别排序与稳定游标 | 巡检 → 查找猪只 | 在服务端对全结果排序，区分耳标精确／前缀／包含／栏位匹配，返回当前厂区准确总数 |

这部分优先扩展现有读接口；只有现有投影难以表达时再增加聚合接口。免疫任务头与待接种目标的疫苗／品牌不一致，以及当前批次 `farm_id` 为空，是已有数据／投影问题，先修复它们，不必为修数据另造业务状态。

各生产任务的具体检查、配种、采样、出栏等执行命令还需按前表逐项形成领域契约；共用操作协议只解决重试与回执，不能代替本次检查、剂次或转出／接收目标的业务身份。

### 8. 交接顺序与尚需确认

建议后端按可交付链路推进：

1. 共用操作去重／结果查询与能力版本，同时修复免疫数据身份和批次厂区字段。
2. 独立 finding／Care／历史与统一目录；先给可靠读取，再接健康写入。
3. 药品／品牌／单位及休药快照，接临时治疗逐猪创建。
4. 附件、栏位备注／历史、独立设备故障实例与解决，再接盘点和跨单元转入。
5. 按任务类型补首页权威范围、动作窗口和执行记录；补 Feed 与阶段起点。

已确认：多猪病症记录／临时治疗原子保存；新增病症 Care 无默认且必选。健康与疾病 PRD、元数据及巡检原型已同步到本任务已有设计工作分支 `codex/inspection-cross-unit-scan` 的未提交改动；Sentri-UI main 未修改。

评审仍需明确：病症子类型与重复病例身份；无耳标群体数量语义；报告类疾病后续流程；休药天数的正式来源及跨日／时区计算细节（给药时间基准已确认以前端传入值为准）；治疗后是否联动原 finding 的 Care；盘点原因 code 和批次变更语义；Other 设备的同名处理。这些不会因接口草案列出字段就被视为已经定稿。

每条新链路的后端验收至少包含：同 ID 同内容重复请求不重复写、同 ID 不同内容被拒绝、响应丢失后查回原结果、版本冲突不覆盖较新修改、跨账号／厂区不能读取或处理旧操作、附件与业务回执一致。Android 再按已确认契约做必要的队列／页面定向检查，完整设备回归仍按交付优先清单安排。

### 9. 首批写接口的联调样例

本节把第 1–3 节变成可讨论的输入与回执。全部 ID、时间、剂量和目录都是固定示例，不对应真实账号、猪只或药品；接口名称／字段与摘要方案仍是**提案，未部署、未接入 Android**。数量不代表推荐剂量。先确认协议，再生成正式 GraphQL operations。

#### 9.1 固定内容与摘要

建议协议版本 1：对 `{command_type, schema_version, client_recorded_at, time_source, payload}` 做 [RFC 8785 JCS 规范化](https://www.rfc-editor.org/rfc/rfc8785.html)，再对 UTF-8 字节计算 SHA-256，输出小写十六进制 `payload_hash`。这里的 `payload` 是请求去掉 `command_context` 后的完整业务内容。服务端从调用的操作推导并核对 `command_type`，按原始请求内容独立重算摘要，不能先把剂量字符串转为浮点再生成摘要；摘要只用于内容一致性，不代替鉴权。

入队后保留字符串原值、数组顺序与显式 null；不在发送时重写时间、排序目标、trim 备注或把 `1.20` 转成 `1.2`。同一批次保存前可以整理草稿，冻结后修改须生成新操作。业务十进制用字符串，版本用十进制整数字符串；拒绝重复 JSON 属性、非法 Unicode 和超出约定的值。正式实现应使用经核对的 JCS 实现，本节针对固定样例的摘要校对不证明任意 JSON 编码都正确。

`client_operation_id` 不计入内容摘要，因此相同内容的新操作也可能有相同摘要；后端去重依据操作身份及其作用域，不能按摘要合并两次真实记录。`payload_hash` 本身、Token、语言、重试次数、服务端接收时间均不计入。厂区和操作者仍由 JWT 核实，不能从样例输入获得身份。

#### 9.2 两头猪记录病症

应用位置：巡检 → 选中猪只 → 操作 → 记录病症 → 保存。示例主动选择 Monitor；一个目标可列多个已选病症。后端在一次事务里核对最终目标集合和目录身份，再新增所需 finding；不得逐头提交并把前半段成功留在库里。

以下是 `recordHealthFindings` 的逻辑输入：

```json
{
  "command_context": {
    "client_operation_id": "00000000-0000-4000-8000-000000000101",
    "command_type": "RECORD_HEALTH_FINDINGS",
    "schema_version": 1,
    "client_recorded_at": "2026-10-26T01:30:00Z",
    "time_source": "DEVICE",
    "payload_hash": "f3fa3c4de9ad76b70d1a14db7fe2888ace9d06920c2e3772d4618edceb0ef457"
  },
  "targets": [
    {
      "pig_id": "sample-pig-a",
      "expected_unit_id": "sample-unit-7",
      "conditions": [
        {
          "kind": "SYMPTOM",
          "catalogue_id": "sample-poor-appetite",
          "subtype_id": null,
          "care": "MONITOR"
        }
      ],
      "note": "食欲下降",
      "attachment_ids": []
    },
    {
      "pig_id": "sample-pig-b",
      "expected_unit_id": "sample-unit-7",
      "conditions": [
        {
          "kind": "SYMPTOM",
          "catalogue_id": "sample-poor-appetite",
          "subtype_id": null,
          "care": "MONITOR"
        }
      ],
      "note": "食欲下降",
      "attachment_ids": []
    }
  ]
}
```

假设 A 尚无这条开放病症，B 已有同一病症且原 Care 为 No action needed。期望回执：A 新增；B 返回原 finding，不覆盖原 Care、不重置起始时间，也不新增重复历史事件。页面应说明已保留的既有记录，不能把所选 Monitor 显示为已应用到 B。开始时间由后端核定并保留来源；以下时刻是样例，不将手机记录时间自动视为真实发病时刻，也不决定设备错钟时的核定政策。

```json
{
  "client_operation_id": "00000000-0000-4000-8000-000000000101",
  "command_type": "RECORD_HEALTH_FINDINGS",
  "farm_id": "sample-farm",
  "actor_id": "sample-worker",
  "payload_hash": "f3fa3c4de9ad76b70d1a14db7fe2888ace9d06920c2e3772d4618edceb0ef457",
  "status": "APPLIED",
  "accepted_at": "2026-10-26T01:35:00Z",
  "applied_at": "2026-10-26T01:35:01Z",
  "results": [
    {
      "pig_id": "sample-pig-a",
      "catalogue_id": "sample-poor-appetite",
      "finding_id": "sample-finding-a",
      "action": "APPLIED",
      "entity_version": "1",
      "business_record_id": "sample-event-a",
      "care": "MONITOR",
      "started_at": "2026-10-26T01:30:00Z",
      "kind": "SYMPTOM",
      "subtype_id": null
    },
    {
      "pig_id": "sample-pig-b",
      "catalogue_id": "sample-poor-appetite",
      "finding_id": "sample-finding-b",
      "action": "UNCHANGED",
      "entity_version": "3",
      "business_record_id": null,
      "care": "NO_ACTION_NEEDED",
      "started_at": "2026-10-24T02:00:00Z",
      "kind": "SYMPTOM",
      "subtype_id": null
    }
  ],
  "conflicts": []
}
```

`results` 的 `(pig_id, kind, catalogue_id, subtype_id)` 组合必须与请求逐项对应，不多、不漏、不重复；疾病和症状即使名字或旧 ID 相同也不能串项。`UNCHANGED` 只适用于已经存在的同一开放 finding；缺目标、无权限或已转出不能用它掩盖。`subtype_id` 如何参与同一病例身份仍是第 8 节待评审项，未明确前不开放子类型替换写入。

#### 9.3 一头猪冲突，全体不写

应用位置：单猪／多猪病症保存、临时治疗保存的冲突返回。若 B 已转到其他单元，整次请求返回冲突，A 也不得新增 finding；不能先改 A 再把 B 标为失败。下面是假设同内容使用另一个操作 ID 的冲突结果，与上面的成功示例互斥：

```json
{
  "client_operation_id": "00000000-0000-4000-8000-000000000102",
  "command_type": "RECORD_HEALTH_FINDINGS",
  "farm_id": "sample-farm",
  "actor_id": "sample-worker",
  "payload_hash": "f3fa3c4de9ad76b70d1a14db7fe2888ace9d06920c2e3772d4618edceb0ef457",
  "status": "CONFLICT",
  "accepted_at": "2026-10-26T01:35:00Z",
  "applied_at": null,
  "results": [],
  "conflicts": [
    {
      "pig_id": "sample-pig-b",
      "code": "LOCATION_CHANGED",
      "expected_unit_id": "sample-unit-7",
      "current_unit_id": "sample-unit-9"
    }
  ]
}
```

冲突是该操作的持久结果，同 ID 再提交仍返回这个结果，不随猪只后来转回而重新执行。服务端明确确认该次全未写后，用户可排除 B 或修改内容并创建新操作；不能把服务器排除 B 当作用户确认，也不能在原结果仍未知时直接换 ID 重提。返回中的当前位置只提供给有相应读取权限的账号；无读取权限时返回可展示的拒绝代码，不泄露其他范围资料。

#### 9.4 两头猪临时治疗，只关联实际已有病症

应用位置：巡检已选猪只／猪只详情／已有病症详情 → 记录临时治疗 → 保存。示例 A、B 都接受治疗，只有 A 关联已有 finding；共享剂量在冻结前展开为逐猪值，不能让后端再读客户端的“跟随共享”状态。

以下是 `recordAdhocTreatment` 的逻辑输入，`sample-approved-unit` 表示未来由正式目录返回并核实的单位，当前 App 的样例单位不能用于此提交：

```json
{
  "command_context": {
    "client_operation_id": "00000000-0000-4000-8000-000000000201",
    "command_type": "RECORD_ADHOC_TREATMENT",
    "schema_version": 1,
    "client_recorded_at": "2026-10-26T01:30:00Z",
    "time_source": "DEVICE",
    "payload_hash": "25477259b4f4d8f8f494c54bf2b9fb127e8172a2560ccaf7477cdfc93c0f41fd"
  },
  "administered_at": "2026-10-26T01:30:00Z",
  "medicine_id": "sample-medicine",
  "brand_id": null,
  "method_id": "sample-method",
  "unit_code": "sample-approved-unit",
  "catalogue_revision": "sample-catalogue-v7",
  "targets": [
    {
      "pig_id": "sample-pig-a",
      "expected_unit_id": "sample-unit-7",
      "dose": "1.20",
      "finding_ids": [
        "sample-existing-finding-a"
      ]
    },
    {
      "pig_id": "sample-pig-b",
      "expected_unit_id": "sample-unit-7",
      "dose": "0.80",
      "finding_ids": []
    }
  ],
  "note": ""
}
```

后端逐猪核对位置、在群状态、药品／品牌关系、方式／单位、有效 finding 及调用权限，全部成立后一次提交。品牌为空不等于默认品牌，`finding_ids: []` 不等于新增病症。首版最多关联一个所选目录病症对应的本猪 finding，不能利用数组一次关联多个不同病症；从病症详情进入的关联固定。与记录病症不同，新的真实给药不应因既往药品／剂量相同而合并，只按相同操作 ID 去重。治疗后是否改变原 finding 的 Care 仍是 Sentri-UI 的未决项，本节不把自动解除 Care 当作已确认的保存副作用；如后续决定联动，需明确事务和回执。

成功回执复用共用外层；每头至少返回 `treatment_record_id`、`pig_id`、记录版本、药品／品牌／方式／单位／剂量快照、实际 `finding_ids` 及休药快照。病症关联无效不能静默丢掉后仍返回成功。

2026-10-26 用户确认服务端以前端传入时间为准。下面假设两头猪在 26 日记录，手机在 27 日才联网；给药时间与休药基准仍是 26 日。示例药品配置假定为 2 天，展示本例的时间关系；不是正式目录值，不决定其他药品或跨时区的计算细节。没有正式休药期配置时返回未设置，不能套用这个样例的 2 天。

```json
{
  "client_operation_id": "00000000-0000-4000-8000-000000000201",
  "command_type": "RECORD_ADHOC_TREATMENT",
  "farm_id": "sample-farm",
  "actor_id": "sample-worker",
  "payload_hash": "25477259b4f4d8f8f494c54bf2b9fb127e8172a2560ccaf7477cdfc93c0f41fd",
  "status": "APPLIED",
  "accepted_at": "2026-10-27T01:35:00Z",
  "applied_at": "2026-10-27T01:35:01Z",
  "results": [
    {
      "pig_id": "sample-pig-a",
      "action": "APPLIED",
      "business_record_id": "sample-treatment-1",
      "treatment_record_id": "sample-treatment-1",
      "entity_version": "1",
      "administered_at": "2026-10-26T01:30:00Z",
      "medicine_snapshot": {
        "medicine_id": "sample-medicine",
        "name": "示例药品",
        "brand_id": null,
        "brand_name": null,
        "method_id": "sample-method",
        "method_name": "示例方式",
        "unit_code": "sample-approved-unit",
        "dose": "1.20"
      },
      "finding_ids": [
        "sample-existing-finding-a"
      ],
      "withdrawal_snapshot": {
        "days": 2,
        "source": "MEDICINE",
        "basis_at": "2026-10-26T01:30:00Z",
        "until_at": "2026-10-28T01:30:00Z",
        "catalogue_revision": "sample-catalogue-v7"
      }
    },
    {
      "pig_id": "sample-pig-b",
      "action": "APPLIED",
      "business_record_id": "sample-treatment-2",
      "treatment_record_id": "sample-treatment-2",
      "entity_version": "1",
      "administered_at": "2026-10-26T01:30:00Z",
      "medicine_snapshot": {
        "medicine_id": "sample-medicine",
        "name": "示例药品",
        "brand_id": null,
        "brand_name": null,
        "method_id": "sample-method",
        "method_name": "示例方式",
        "unit_code": "sample-approved-unit",
        "dose": "0.80"
      },
      "finding_ids": [],
      "withdrawal_snapshot": {
        "days": 2,
        "source": "MEDICINE",
        "basis_at": "2026-10-26T01:30:00Z",
        "until_at": "2026-10-28T01:30:00Z",
        "catalogue_revision": "sample-catalogue-v7"
      }
    }
  ],
  "conflicts": []
}
```

`administered_at` 必须表示冻结请求的同一绝对时刻；输出时可规范化时区格式，但原始请求与摘要不重写，核对时不能只比字符串。服务端不得以 `accepted_at` 重建它。同一次给药因明确未写的冲突而重提仍保留原 `administered_at`，只有真正新记录一次给药才重新取时。未设置休药期返回 `days: null`、`source: "UNSET"`、`until_at: null`，保留前端传入的 `basis_at`；0 天返回 0，不能与未设置混淆。正式天数、品牌覆盖及计算规则必须来自已核实目录；保存后修改目录不回算旧快照。

#### 9.5 查询与读回如何判断

应用位置：业务页本机状态、后台同步，以及保存后刷新病症／治疗记录。它不要求先建设 Data sync 列表。

| 服务端证据 | Android 处理 |
| --- | --- |
| `operationResult` 尚无该操作 | 保留本机原操作，不等同于拒绝或取消；只有新接口已验证按 ID 去重时才允许原 ID／原内容重试 |
| `PENDING` | 已受理，仍未服务端确认；继续查同 ID，不生成新 ID |
| `APPLIED`，回执完整 | 逐项核对操作 ID、类型、厂区、操作者、摘要和目标；再按业务记录 ID／finding ID 定向读回，不凭当前数值相同认定成功 |
| 投影尚未跟上 | 保留已提交但读回待核对，不再提交写入；读取须给出版本或可等待的投影进度 |
| finding 已被后续操作改过 | 通过原事件 ID／版本历史确认本次变更，再展示最新状态；不能要求当前 Care 永远等于原回执，也不能因记录已关闭而重建病例 |
| `UNCHANGED` 没有新增事件 | 回执保留当时已存在的 finding ID、版本和原值；按 ID 可读到包括已关闭记录在内的历史身份，不能只查当前开放列表 |
| `CONFLICT`／`REJECTED` | 显示具体对象和原因；原子操作没有部分业务写入。草稿可调整，新内容使用新 ID |
| 同 ID 不同内容 | 本次请求以 `OPERATION_PAYLOAD_MISMATCH` 拒绝，不改原操作的状态／回执；允许查询原结果，不能把原 APPLIED 覆写成 REJECTED |
| 查询无权限／账号厂区变化 | 不读取或发送旧作用域操作；保留本机隔离数据，不把无权限当“服务器未执行” |

新增写入和原操作回执必须一并提交；不能先返回成功再异步补回执。业务回执与投影属于不同证据，后端需分别说明强一致读取方式和异步投影的核对方式。操作 ID 不应在原队列仍可能恢复时失去去重记录；过保留期须返回明确结果，不得当新操作执行。

#### 9.6 后端首批验收清单

以下验收由后端实现后再联调；本次只校对文档样例，不计作后端已通过：

| 场景 | 必须成立的结果 |
| --- | --- |
| 首次两猪保存 | 目标、业务事件与回执在同一事务提交；回执集合完整 |
| 两个相同 ID／内容并发到达 | 只执行一次，后到请求得到同一个持久结果 |
| 服务端已提交但响应丢失 | 按原 ID 查回原回执，再读回原业务记录；不重复新增 |
| 同 ID 修改剂量／Care／目标 | 拒绝内容不匹配，不改变原记录或原回执 |
| 单头位置／版本／权限冲突 | 明确具体猪只，整个多猪保存不写任何业务记录 |
| 病症重复记录 | 原 finding／Care／起始时间不变，不追加重复病症事件 |
| 新病症未选 Care／空值／未知值 | 明确拒绝，不自动填 No action needed，也不先写其他目标 |
| 同内容、不同 ID 的第二次治疗 | 按新的真实给药处理，不能按摘要误去重；是否属于重复给药由临床业务规则另行核对 |
| 多个本机操作依赖同一 finding | 先确认依赖操作及稳定 finding ID，再发送后继；依赖结果不明时不得用目录 ID 顶替 |
| 一个字段缺省、null、空列表不同 | 按约定解析并核对原摘要；不在重试时悄悄补默认 Care 或关联 |
| 后续 Care 修改／病症已关闭 | 仍能查到原操作及版本历史，不把读回变化误判为未提交 |
| 离线记录次日才上传／重启后发送 | 给药时间与休药基准保持前端冻结值；只增加服务端接收时间，不重新取时 |
| 原操作仍 PENDING 或结果不明，用户想修改后重提 | 先核对原操作，不得新建另一 ID 造成双写；只有明确全未写才进入修改／新操作路径 |
| 跨账号／厂区或对象越权 | 服务端拒绝且不泄露原操作，客户端不切换作用域发送 |
| 事务中途异常 | 全部业务变更回滚；客户端可区分未确认、冲突和已确认，不接受混合成功结果 |
