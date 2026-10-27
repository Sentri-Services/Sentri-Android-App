# 全业务非 UI 基础任务表

2026-10-09。目标：基于旧工程逐项补齐原生 GraphQL、输入与读取模型、离线 payload、依赖与持久化适配、中英文和验证。此文件是本阶段执行状态的权威来源；业务范围与产品缺口继续见 docs/migration.md。

不定义页面、样式或组件规范。沿用当前登录、签名、版本及发布模型，不触发发布。mutation 定义和本地持久化完成不等于真实写入/自动同步已启用；真实重放仍需具体操作的后端幂等及结果确认依据。

每行依次完成：核对旧 services/handler/adapter → 原生 operation 和 Apollo 生成 → 独立业务输入/读取模型 → 版本化 payload/依赖/持久化 → 文案与枚举 → JVM/适用 SQLite 设备验证 → 更新证据。复合命令要记录子步骤结果；精液需原子本地引用映射；设备动作不自行改成离线自动重放。

| 顺序 | 业务 | GraphQL／模型 | 离线／依赖／持久化 | 中英文 | 状态与验收重点 |
| --- | --- | --- | --- | --- | --- |
| 0 | 会话、厂区、猪只/位置基础读、语言、契约检查 | 已完成基础范围 | 作用域快照与调度门禁已完成 | 基础完成 | 前阶段证据在 verification/pig-read 与 language-offline；不代表全业务完成 |
| 1 | 查情 CheckEstrus / SubmitEstrusStatus | 两种输入/operation 与任务读取已完成 | 两种 payload、自动提交依赖、schema 1→2、任务快照与重启恢复已验证 | 结果与两种操作已完成 | 基础完成；跨任务预览见第 8 项，完整组装仍待回归；真实写入未启用 |
| 2 | 配种 MateWithBoar / MateWithSemen | 两种输入/operation、任务/记录/材料读取已完成 | 两种服务端 ID payload、任务快照；查情/配种同猪顺序已验证 | 两种操作已完成 | 基础完成；本地记录预览见第 8 项，公猪完整数据依赖第 9 项，精液列表/本地引用依赖第 16 项 |
| 3 | 复情 CheckReEstrusPig / SubmitReEstrusStatus | 两种输入/operation、任务/结果/位置及独立 recheck view 已完成基础 | 单元轮次依赖、两种 payload、任务快照/辅助视图缓存与新进程恢复已验证 | 两操作/三结果已完成 | 基础完成；本地预览见第 8 项；完整猪只数据在第 9 项统一读取后回归组装 |
| 4 | 孕检 CheckPregnancyPig / SubmitPregnancyStatus | 两种输入/operation 与任务/结果/位置读取已完成 | 单元轮次依赖、两种 payload、复查天数、任务快照与新进程恢复已验证 | 两操作/三结果已完成 | 基础完成；预览见第 8 项，保留服务端复查日期，完整关联组装待回归；后端没有检查方式字段 |
| 5 | 分娩 SubmitFarrowingPiglet / FosterPiglet | 输入/operation、任务/统计/身份/记录读取已完成 | 两种 payload、双猪顺序、任务快照及新进程恢复已验证 | 两操作/三状态/六仔猪类型已完成 | 基础完成；辅助列表、本地投影与真实写入待补；旧 handler 调用统一 FosterPiglet，后端按任务类型分发，两个阶段专用接口仅保留定义及线协议测试 |
| 6 | 仔猪 SubmitPigletHandlingItem / Info / TotalWeight | 三输入/operation、任务/身份/处理项目/数量/Decimal 读取已完成 | 三种 payload、每猪独立命令、精度/null/空列表及任务快照已验证 | 三操作/八处理项目已完成 | 基础完成；本地预览见第 8 项，完整关联查询与真实写入待补 |
| 7 | 产后、哺乳、保育、育肥 | 四 operation/输入/任务读取及独立数据模型已完成 | 四种 payload、母猪/栏位顺序、四任务快照及新进程恢复已验证 | 四操作/八检查枚举组已完成 | 基础完成；保育/育肥每任务栏位一个汇总命令，育肥没有独立栏位汇总 view；完整关联查询、本地投影与真实同步待补 |
| 8 | 任务通用 MarkTaskPig / CompleteProductionTask、任务列表与生产任务本地投影 | 两输入/mutation、目录/元数据、批次列表/详情、独立复情视图、单任务与同批活动类型组装及完整档案上下文基础已完成 | 两 payload、结束屏障、目录/元数据/辅助读取缓存、十九生产命令及属性/角色预览规则与新进程恢复已验证 | 两操作/十任务类型/三冲突原因/十一预览状态/七批次产线状态已完成 | 进行中；完整猪只健康/历史/位置已按稳定 ID 分组接入任务工作区，保留各次来源和时间。同批任务元数据、列表/详情及材料身份预览一致性仍需回归，精液关联依赖第 16 项。来源 breeder/新身份缺失仍明确报告。成功读取反映证据未自动判定；未开放离线结束入口 |
| 9 | 猪只状态、角色、死亡及完整读取 | 四写操作/输入及旧完整列表/详情/单猪/任务猪字段并集读取、独立模型已完成基础 | 四 payload、图片子命令回执、原子入队/取消、两完整档案快照、属性/角色/死亡原子预览基础、实际猪及母猪同组顺序与新进程恢复已验证 | 四操作/四动作、来源/二十二品种/四采精控制状态/五死亡待确认字段已映射 | 进行中；属性/角色预览及档案工作区接入已完成基础，死亡预览基础已完成，包含跨批次/PLANNED 成员及并行窝；未公开的转出状态、死亡统计和服务端图片元数据保留缺上下文/待确认，不猜默认值。十类任务猪身份关系及已有元数据按角色命令实际写入字段更新；批量任一缺档案/非法角色/重复目标导致转移失败时整条预览不应用。真实写入未启用 |
| 10 | 医嘱、治疗目标、终止与跳过 | 六实际写 operation/独立模型及创建声明/Decimal 输入已完成基础；治疗列表/详情、处方上下文及药物/规格/治疗方式读取与独立模型已接入 | 六 payload/SQLite 登记、同处方及跨治疗依赖、原子步骤入队、严格逐步回执、部分成功重试和新进程恢复已验证；六类读取快照与六操作本地预览基础已接入 | 七操作/五处方状态/五治疗目标状态及预览状态已映射 | 进行中；完整处方成员与分页治疗行组装、逐目标下一轮和跨治疗预览已用 mock/SQLite 验证。成功读取反映证据、无目标任务可见性及真实后端读取仍待；创建声明无旧实际调用，返回 serial 非处方 ID，不启用离线创建 |
| 11 | 疫苗执行与完成 | 两写 operation/独立输入与类型化回执；完整任务列表/详情、逐目标剂次及豁免记录读取模型已接入 | 两 payload 已登记 SQLite；同任务剂次显式排序、结束屏障与错误回执拒绝、列表/详情两类快照及新进程恢复已验证；逐剂本地预览基础已接入 | 两操作、任务/目标状态与类型、接种方式、疫苗/品牌状态、十二项豁免规则、屏障及预览状态已映射 | 进行中；执行按目标推进一剂，人工结束不伪造未接种剂次；旧缓存的豁免状态标为未知。自动读取反映证据、豁免当前有效性与结算副作用、真实后端联调待做；旧结束入口仍是在线操作 |
| 12 | 采样 | SubmitSampled mutation/独立输入与严格回执、完整列表/详情及猪只/环境/人员/物资/其他目标、采样试验与报告模型已接入 | 单 payload 登记 SQLite；同任务重复未确认提交门禁、全量/日窗口列表与详情快照、本地预览及新进程恢复已验证 | 操作、五目标/五样本/五项目/三容器/两结果、队列与预览状态已映射 | 基础完成；首页预取窗口与完整列表分键，操作人/时间不写入后端输入。真实业务读写与自动成功反映未验证；简化入场采样留第 17 项 |
| 13 | 巡检、设备报障/解决、健康/测量/盘点 | 设备两写/一读、单元两写/两读、健康六写与疾病症状目录、图片上传、体重/背膘/体温三写及当前测量读取、栏位盘点一写一读已接入 | 设备本地 ID、单元/同猪/同栏位依赖、十五种 payload/回执、六类快照已验证；图片大 payload 与盘点快照重开恢复已验证 | 十五操作与设备/单元/健康/测量枚举、双语目录已映射 | 进行中；后端巡检处理器未保存 cleanliness/compliance，健康入口未传递 image_urls，盘点处理器未保存 reason。盘点成功只回栏位编码，未知结果不可自动重发；真实后端读写待验证 |
| 14 | 转舍及完成 | 待做 | 待做 | 待做 | 转出/转入、任务条目、作用域位置缓存适配 |
| 15 | 出栏及装车票 | 待做 | 待做 | 待做 | 复合执行子步骤回执、撤销、部分成功恢复 |
| 16 | 精液采集/质量/稀释/储位/标签/库存 11 类 | 待做 | 待做 | 待做 | 本地批次/储位→服务器 ID 原子替换、配种关联 |
| 17 | 简化入场采样、设备消毒、文档下载 | 待核对 | 仅实现已明确语义的适配 | 待做 | 简化入口保留待产品确认；解锁/消毒保持在线边界 |
| 18 | 汇总、全业务契约/持久化验证与覆盖审计 | 待做 | 待做 | 待做 | 明确已实现、定义完成、模拟通过、真实通过、产品/后端缺口 |

物资购买页面与打卡目前缺真实后端契约，记录为产品输入缺口，不生成虚构接口。正式 UI 验收独立于本阶段。所有待做项逐项实施，不能以搭好共用框架替代业务适配。

## 当前覆盖事实

第二十批完成第 12 项采样基础：原生 SubmitSampled 仅发送任务 ID 和可空备注，成功回执核对 recordId，resolver 可空 record 不误判失败；同任务重复/结果未明提交被本地队列拦截，其他任务独立。完整任务列表与指定日窗口内已采样/全部未采样预取分键，详情按 farm+ID；模型保留五种目标的原始 ID、匹配猪只/人员关系、试验目标 JSON、报告索引及详情。两类快照与未知命令可在新进程恢复，QUEUED 预览不伪造服务端采样时间/操作人，确认快照不改。`verify.sh`、201 JVM、138 常规 Android 通过，18 opt-in 跳过；显式 PID 18485→18525 恢复三快照、一命令，远端读取及未知重发 0。契约 63 operations/672 字段、425 双语条目/281 映射，队列 32 种；见 [采样证据](../../verification/business-foundations/sampling/results.json)。真实后端读写和自动读取反映待验证，简化入场另属第 17 项。第 8–11 项剩余与第 13–18 项继续按表推进。

第十九批补第 11 项豁免读取：利用目标行已有的 `vaccination_exemption` 关系读取命中、规则码、原因及评价/有效/修改时间；与逐剂行一起缓存，校验厂区、目标 ID 和同目标各剂记录一致。旧 format-1 快照未含该字段时保持 `exemptionQueried=false`，不把未知误判为未豁免；新查询确认的空关系才表示该次读取未返回记录。十二个后端规则码已映射中英文，但豁免有效性可能变化，任务结算也由后端独立重算，本地不据此修改确认数量。`verify.sh`、191 JVM、133 常规 Android 通过，17 opt-in 默认跳过；显式新进程恢复三快照、一命令和豁免记录，远端读取 0。契约 61 operations/592 字段、402 双语条目/253 映射；见 [豁免读取证据](../../verification/business-foundations/vaccination-exemption/results.json)。自动成功读取反映、豁免结算副作用及真实联调仍待。

第十八批补第 11 项逐剂本地预览：重放同作用域排队的 Execute/Finish，按后端每目标仅推进一剂并生成未知服务器时间的下一剂本地行；自然完成与人工结束分别处理，原确认快照不改。未知、失败、已发送待刷新和依赖未决有独立状态；`reflectedCommandIds` 仍仅接受调用方显式证据。`verify.sh`、190 JVM、132 常规 Android 通过，17 opt-in 跳过；显式新进程恢复三快照、一命令并重建完成预览，远端读取 0。契约 61 operations/582 字段、390 双语条目/241 映射；见 [疫苗预览](../../verification/business-foundations/vaccination-preview/results.json)。豁免完整上下文、自动读取反映判定和真实后端联调仍待。

第十七批推进第 11 项读取基础：完整任务列表与旧首页 active/当日完成预取分键，详情按 farm+ID 独立快照；模型保留每个 `(target_id,dose_index)` 行、剂量 Decimal、JSON 和原始时间精度。读取核对目标行聚合数量、剂次连续性、关联疫苗/品牌/猪只厂区，不能将首剂完成视作整个目标完成。Apollo fragment 路径在测试中舍入剂量，已改为完整字段单查询供列表/详情复用。`verify.sh`、186 JVM、132 常规 Android 通过，17 opt-in 跳过；显式 prepare/force-stop/restore 新进程恢复三份快照、一命令且远端读取 0。契约 61 operations/582 字段、390 双语条目/233 映射；证据见 [疫苗读取](../../verification/business-foundations/vaccination-read/results.json)。逐剂本地预览和真实联调仍待。

第十六批启动第 11 项疫苗写入基础：ExecuteVaccinationTask / FinishVaccinationTask 两 mutation、独立输入、严格任务 ID 回执及 format-2 payload 已接入。接种保留有序目标、重复值和可空时间；同一任务的剂次按入队顺序依赖，结束操作等待所有未决剂次并阻止后续接种，设备时钟回拨不改变顺序。后端支持多剂次，旧 Web 的“执行目标即完成目标”本地乐观更新不能复用。`verify.sh` 最终通过，183 JVM、130 常规 Android，16 opt-in 跳过；契约 60 operations/523 字段，388 双语条目/225 映射，31 队列命令。当前只完成写入基础，列表/详情读取及逐剂预览仍待；验证见 [疫苗写入证据](../../verification/business-foundations/vaccination-write/results.json)。

第十五批补第 10 项处方上下文读取和六操作本地预览：先校验处方目标与治疗成员总数，再分页读取全部治疗并重放同作用域排队命令。执行/跳过按目标立即形成下一轮；增删目标与终止操作按处方范围预览，未知、失败及已发送待刷新的命令保持独立状态，不写回服务端快照。`verify.sh`、179 JVM、126 常规 Android 通过，16 opt-in 默认跳过；显式新进程恢复八快照、一命令且远端读取 0。见 [预览证据](../../verification/business-foundations/therapy-preview/results.json)。自动读取反映判定、零目标活动任务的后端可见性和真实后端联调仍待。

第十四批补治疗完整列表/详情及三类参考数据的读取模型、四查询和五类 SQLite 快照；完整列表与日窗口预取分键并校验时区感知的窗口成员，嵌套厂区和外键校验，保留 Decimal/JSON/时间及未知状态原码。`verify.sh`、173 JVM、124 常规 Android 通过，16 opt-in 默认跳过；新进程恢复六快照且远端读取 0。见 [读取证据](../../verification/business-foundations/therapy-reads/results.json)。第 10 项逐操作预览和真实后端读取仍待做，任务 11–18 未开始。

第十三批开始第 10 项医嘱/治疗：六个实际写操作（执行、跳过、增删目标、终止治疗/处方）的原生定义、独立输入和 format-2 持久化已完成基础。每个远程步骤独立保存尝试和绑定 operation/recordId 的严格回执，整个批次原子入队；修改数量/顺序/输入/调用方依赖不能复用原批次身份。后续步骤失败只重试该步骤，不重放成功步骤；同处方及跨治疗共享依赖，设备时间回拨仍按明确顺序，其他处方和作用域独立。创建医嘱仅保留旧声明与精确 Decimal 输入，不把返回 serial 当成新处方 ID，也未开启离线创建。

verify.sh、164 JVM、118 常规 Android 通过，15 opt-in 默认跳过；therapyStage prepare/force-stop/restore 各一项显式通过，PID 4960→4996，三步骤恢复，成功步骤未重放、未知重发 0，显式确认后仅模拟执行 TerminatePrescription。新增 8 JVM / 6 常规 Android / 1 opt-in；首轮回执 JVM 环境失败及进程测试误用同毫秒列表顺序的日志保留，已改为通用 JSON writer 和持久化步骤身份核对。当前 53 operations/462 字段、285 文案/199 映射、29 队列命令；schema 2/payload format 2 未变。证据见 verification/business-foundations/therapy/results.json。医嘱完整读取/参考数据缓存/本地预览及第 8/9 项剩余、第 11–18 项仍待做。无真实后端读写、UI、版本或发布变更。

以下为历史批次的当时结果。

第十二批补死亡原子预览及按猪跨批次任务成员读取。实际猪死亡禁用十类任务，母猪引用仅调整三类并行窝；身份按非空耳号 OR 耳缺移除首项，匿名数量下限为零。包含 PLANNED 的成员查询完整分页后才形成上下文证据，缺页/变动总数不冒充无关联任务。实际猪与母猪共同参与队列顺序，已确认图片是就绪资源，未知/失败/取消图片仍阻塞上报；确认快照不被预览或回执改写。匿名猪使用操作/行身份，不生成服务端 ID/时间；SDL 缺少的母猪转出状态、死亡统计与图片元数据保持缺上下文或待确认。

verify.sh、156 JVM、112 常规 Android 通过，14 opt-in 默认跳过；deathPreviewStage prepare/force-stop/restore 各一项显式通过，PID 3869→3940，六快照/两命令/三个跨批次任务恢复，远端读取与未知重发均为 0。201 个关联任务分页及旧目录 format-1 兼容通过。当前 46 operations/449 字段、268 文案/182 映射、23 队列命令；schema 2/payload format 2 未变。证据见 verification/business-foundations/death-preview/results.json。第 8 项元数据/列表详情/材料完整一致性与自动读取反映判定、第 9 项后端未公开状态及其他档案副作用、第 10–18 项仍待做。无真实后端读写、UI、版本或发布变更。

以下是历史批次的当时结果。

第十一批已补属性/角色原子预览与完整档案工作区上下文；第 8/9 项仍进行中。verify.sh、141 JVM、106 常规 Android 通过，13 opt-in 默认跳过；profileWorkspaceStage prepare/force-stop/restore 各一项显式通过，PID 2778→2914，三快照/两命令恢复，未知重发和显式模拟刷新前远端读取均为 0。成功回执不会改确认快照，显式模拟读取加调用方证据后才允许后续角色预览。46 operations/449 字段、262 文案/176 映射、23 队列命令未变。证据见 verification/business-foundations/profile-workspace/results.json；实现范围和规则见 docs/business-foundations.md 的“属性/角色预览与任务档案上下文”。没有真实后端读写、UI 或发布变更。死亡预览、同批元数据/列表详情/材料一致性与自动读取反映判定，以及第 10–18 项仍待做。以下保留各历史批次的当时结果。

开始本阶段时，业务专属原生 operation/模型/队列仅 CheckEstrus 与查情任务读取。多语言框架、GraphQL 契约工具和基础读取缓存是共用平台，不是其余业务已接入的证据。

首批已补 SubmitEstrusStatus 与两类配种。验证见 verification/business-foundations/results.json：72 JVM、35 常规 Android 通过，3 opt-in 默认跳过；新的双类型命令进程恢复 prepare/restore 各显式通过一项。接口仅 MockWebServer、发送仅模拟 transport；未触发真实业务写入/发布。首批实现说明见 docs/business-foundations.md。

第二批完成复情/孕检基础与四类任务快照。证据见 verification/business-foundations/rounds/results.json：77 JVM、45 常规 Android 通过，4 opt-in 默认跳过；新的轮次/任务快照进程测试 prepare/restore 各显式通过一项。检查与提交按同任务/单元的轮次依赖；设备时间回拨时显式依赖优先，不形成同猪顺序死锁。

旧服务源中的具名声明清单见 [source-operations.json](source-operations.json)：54 query、100 mutation、5 fragment，固定参考 commit 和文件哈希。该文件是声明 token 清点，不能替代 schema 校验、实际调用核对或原生适配证明；后续逐项审计辅助查询和复合命令，未使用的服务声明也需明确范围结论。

第三批完成分娩、统一寄养与仔猪处理基础：新增 2 个查询、7 个 mutation 定义、5 种离线输入/payload，以及分娩/仔猪两类任务读取快照。当前共 13 种命令、6 类快照；寄养同时约束来源/接收母猪，其他猪独立执行。重量用 Decimal 字符串保存精度和 scale，null/空列表/零分别恢复；ProductionFloatString 请求使用普通十进制字符串。verify.sh、84 JVM、51 常规 Android 通过，5 opt-in 默认跳过；新增 careStage prepare/force-stop/restore 各显式通过一项，新 PID、五命令/两快照、Decimal 精度及未知寄养结果阻塞验证通过。契约 28 operations/243 消费字段，中英文 163 条目/77 映射。证据见 verification/business-foundations/care/results.json；没有真实 mutation/自动同步/UI/发布变更。

第四批完成产后/哺乳/保育/育肥基础：四查询、四 mutation/输入/离线 payload，四任务快照。产后/哺乳按母猪串行，保育/育肥按 task/location 串行，不把整栏汇总拆成多猪命令。脱垂字段 null/false、身份/列表、JSON、重量 precision/scale 均恢复。verify.sh、91 JVM、57 常规 Android 通过，6 opt-in 默认跳过；stageRestore prepare/force-stop/restore 各显式通过一项，不同 PID、四命令/四快照恢复，未确认产后不重发并阻塞哺乳，两个独立栏位命令模拟执行成功。当前共 17 种命令、10 类生产任务快照；契约 36 operations/310 消费字段，中英文 194 条目/108 映射。静态多语言校验修复了数字键漏计/漏检并加入回归。证据见 verification/business-foundations/stages/results.json；没有真实业务写入/自动同步/UI/发布变更。

整体目标仍进行中。第 8 项继续生产类型本地投影、完整列表与详情组装及辅助查询；辅助关联列表继续补齐，精液本地引用在第 16 项闭环后回归配种。最后按第 18 项审计全部模块，不能把生产类型的基础完成标为全业务完成。

第五批完成第 8 项的通用命令与目录基础，未完成整项：新增 MarkTaskPig / CompleteProductionTask 两操作/输入/payload，以及任务目录和按 ID 元数据读取。目录保持旧首页 SCHEDULED/RUNNING 或给定时间窗口内 COMPLETED 的筛选，并支持配种任务计划排序；缓存包含原始备注 mark、计划/批次/负责人/猪只引用/材料/复查统计/保育汇总，与十类详情快照分别保存。结束命令领取事务追加同任务迟到未决操作，开始或未知/确认成功后拒绝新的同任务操作；重复结束及依赖结束形成的环被拒绝。旧 completion.ts 实际入口在线且无未决命令，与保留的离线 adapter 分开记录，未增加离线结束入口。

verify.sh、99 JVM、69 常规 Android 通过，7 opt-in 默认跳过；taskCommonStage prepare/force-stop/restore 各显式通过一项，新 PID 恢复两命令、一元数据和一页，未确认重发 0、确认后结束可领取。当前 19 种命令、10 类生产详情快照加目录/元数据缓存，39 operations/345 消费字段，209 文案/123 映射。新查询及原猪只 list/detail 去除会触发 Apollo 5.2 数字缓冲舍入的 fragment 路径，精确长小数/JSON 回归通过。证据见 verification/business-foundations/task-common/results.json。完整投影与后续业务仍待做，无真实 mutation/自动同步/UI/发布变化。

第六批补充十九命令本地预览分支与单任务/同批活动详情组装，未完成第 8 项全部范围。快照与队列不被投影改写，未知结果阻塞依赖预览，失败/取消/缺关联数据有独立状态；跨任务修改原子应用。SYNCED 等待读取反映证据，当前只有显式调用者证据参数，未自动证明或启用真实同步。寄养缺 breeder、保育/育肥新身份上下文、生产辅助查询与元数据完整预览仍待深化。verify.sh、112 JVM、75 常规 Android 通过，8 opt-in 默认跳过；projectionStage prepare/force-stop/restore 各一项通过，新进程恢复两详情/一元数据/两命令，原缓存不变，未知自动重发 0。39 operations/345 字段，219 文案/133 映射；证据见 verification/business-foundations/projections/results.json。第 9–18 项仍待逐项执行。

第七批补批次列表/详情与独立复情视图，三种辅助缓存和七状态中英文；verify.sh、117 JVM、81 常规 Android 通过，9 opt-in 默认跳过，auxiliaryStage prepare/force-stop/restore 显式通过。四快照新 PID 恢复，精确时间、双语名称、缺失详情、四维作用域隔离通过。41 operations/360 字段，226 文案/140 映射。见 verification/business-foundations/auxiliary/results.json。第 8 项的完整猪只健康/历史/位置组装明确依赖第 9 项，精液关联依赖第 16 项；完成后回归，不因切换业务而标整项完成。

第八批推进第 9 项属性/角色：两 operation/输入/format-2 payload 已登记 SQLite，四动作、父母 ID、重复/有序列表、null/空/零和 Decimal scale 保留；同猪顺序与既有生产命令共享。verify.sh、120 JVM、85 常规 Android 通过，10 opt-in 默认跳过；pigManagementStage prepare/force-stop/restore 各一项通过，新 PID 恢复三命令，未知重发 0、独立猪模拟发送 1、原操作确认后多猪角色可领取。当前 21 命令、43 operations/366 字段、232 文案/146 映射。见 verification/business-foundations/pig-management/results.json；死亡图片子步骤、完整读取、本地预览及后续业务仍待做，无真实业务写入/UI/发布变化。

第九批推进第 9 项死亡上报与图片子步骤：NativeUploadPigDeathImage / NativeReportPigDeath、独立输入/草稿/图片引用与类型化回执、format-2 codec 已完成。草稿中的 data URL 拆为上传子命令，连同最终上报在同一 SQLite 事务入队；每猪图片上传保留显式顺序，最终上报依赖全部上传确认，跨报告/作用域/图片位置引用被拒绝。取消图片不满足上报依赖；取消上报原子取消尚未执行的子步骤，未知上传需先确认。成功上传保存 file_key/file_url，重试上报不再上传已确认图片。为避免照片超过 CursorWindow，队列大 payload 按 65536 字符分段读取，2700022 字节 fixture 重开完整恢复。schema 2 与 format 2 不变。

verify.sh、125 JVM、91 常规 Android 通过，11 opt-in 默认跳过；deathStage prepare/force-stop/restore 各一项显式通过，PID 30774→30811，恢复一成功/一未决上传及上报，未知/已确认图片自动重发均为 0；手动确认后两次模拟上报复用回执、图片顺序及作用域隔离通过。当前 23 命令、45 operations/372 字段、234 文案/148 映射。证据见 verification/business-foundations/death/results.json。第 9 项完整读取与属性/角色/死亡本地预览、第 8 项关联组装及第 10–18 项仍未完成，没有真实业务写入、后台重放、UI 或发布变化。

第十批推进第 9 项完整猪只读取基础：NativePigProfiles 以内联字段保留 Decimal/JSON 精度，覆盖旧 fullDetail/fullList/singleQueries 与 ProductionTaskPigFields3 的六个定义所选路径（包含 count 与 PigTasks）。独立 PigProfile 模型保留完整位置双语、健康操作人/疾病/症状/历史、医嘱及治疗引用、批次任务和采精控制；统计 null 与零、关系 null 与空列表、原始代码/时间/Decimal scale 分开保存。十一类读取枚举改为 Apollo sealed 生成，未知值保留原始代码；写入 Production 枚举未改。

AppContainer.pigProfiles 已接入；page/detail 独立 format-1 快照沿用 schema-2，不能从旧 basic PigDetail 恢复成完整档案，纳入共用 256 条读取容量。大健康历史分段读取，超过 2.7 MB 的 JSON 重开完整恢复。verify.sh、130 JVM、97 常规 Android 通过，12 opt-in 默认跳过；pigProfileStage prepare/force-stop/restore 各一项显式通过，PID 31603→31643，三快照、远端读取 0、缺失详情、Decimal/JSON/双语名称及作用域隔离通过。当前 46 operations/449 字段、262 文案/176 映射，23 命令未变化。证据见 verification/business-foundations/pig-profiles/results.json 与 source-field-coverage.json。第 9 项本地预览与关联组装、第 8 项完整组装/元数据一致性和第 10–18 项仍待做；没有新增真实后端读取/写入、UI 或发布变化。
