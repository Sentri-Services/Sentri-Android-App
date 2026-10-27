# 首页任务卡示例与旧版信息对照

> 2026-10-12 · 配套[首页工作台 PRD](home-prd.md)。下图是按目标规则绘制的 **SVG 内容示意**，不是旧版截图、Android 已实现页面或真实农场数据。批次、时间和数字只为检查字段层次；颜色、图标占位字及尺寸不作为视觉验收。每张图片均有独立文件，可放大查看。

图片的虚构“今天”统一为 10 月 12 日，仅用于理解相对时间。

这份早期图稿曾把旧 `LACTATION` 与新版断奶误拆成两类，并为旧 Web 没有任务页的结束育肥画了示意；这些图仅作历史排版参考。当前卡片和类型以[生产任务首页卡片总览](home-production-task-cards.md)为准。旧版采精另列为工具入口。

本页每类只放一张早期基准图；旧版首页信息的对照仍可用。当前生产卡片集中看[生产任务首页卡片总览](home-production-task-cards.md)，其中链接了各专题证据。[宽范围场景图](home-card-variants.md)是早期探索，若与聚焦稿冲突，以聚焦稿为准。返情暂选的旧版口径见[专题稿](home-return-heat-card-revision.md)。

## 卡片总览

### 生产任务：八类主卡

![生产任务八类主卡](home-card-examples/production-core.svg)

### 业务任务与采精入口

![治疗免疫转舍出栏采样及采精入口](home-card-examples/operations.svg)

## 对照口径

旧版首页的生产任务入口由 [TaskRenderer](../../Sentri/mobile_webapp/src/pages/home/components/TaskRenderer.tsx) 分发八种类型；总览卡主要显示任务名、位置、任务进行日数与一种累计比例，单元卡另算当前单元信息。多数旧卡在比例达到 100% 时附“结束任务”入口，首页按生产任务计划开始时间排序。新版把**当前值得进入的工作**放在主信息位：有明确窗口时显示当下到期的去重对象，没到期但仍进行中时显示下次计划；进度明确写出统计对象与本次范围。记录比例不会改变任务状态，也不生成单独的“待复核完成”卡。

| 类型与独立示例 | 旧首页实际展示的信息 | 新卡展示的信息与调整理由 | 旧版证据 |
| --- | --- | --- | --- |
| [查情](home-heat-card-revision.svg) | 任务名、位置、进行日数；`cqLength / allLength` 的累计发情／目标发情比例；满条时可出现快捷结束入口。 | 沿用同一结果口径，以新版卡片样式呈现；满条不加首页结束入口，结束影响及确认留在任务详情。旧仓库中残留的“第 N 轮查情”文案不进入首页卡。 | [查情首页封装](../../Sentri/mobile_webapp/src/pages/home/components/tasks/EstrusTask.tsx)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/cq/components/TaskProgress.tsx) |
| [配种](home-breeding-card-branches/overview-recorded.svg) | 已有至少一次配种记录的母猪／已标发情母猪，任务日数；满条可出现结束入口。单元卡按配种次数显示可配／未到期。 | 沿用总览 8/20 的一次覆盖率，统一分子分母过滤；单元另列当前可配和未到间隔，不把“一次配过”说成整套次数完成。首页满条不加结束入口。见[全部分支](home-breeding-card-revision.md)。 | [配种首页计算](../../Sentri/mobile_webapp/src/pages/home/components/hooks/useTaskData.ts)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/pz/components/TaskProgress.tsx)、[旧单元卡](../../Sentri/mobile_webapp/src/pages/task/pz/components/UnitDetailTaskProgress.tsx) |
| [返情检查](home-return-heat-card-branches/overview-no-stats.svg) | 固定七个位置，数组末尾七条中仅最后一条画比例饼点；旧查询无日期排序，其他小点把部分检查压成二值。另有“返情／未返情”数量，但旧代码的“未返情”实际只是“有配种时间数－已返情数”。单元显示预计日期已到和迹象数。 | 本轮暂沿旧版：总览保留已返情数及尚无返情记录数，单元保留预计日期和迹象；日统计快照口径未修复前不画饼点。详见[暂选卡](home-return-heat-card-revision.md)；[新版候选](home-return-heat-feature-alignment.md)待 feature 定稿后再决定。 | [返情首页计算](../../Sentri/mobile_webapp/src/pages/home/components/hooks/useReproductiveTaskData.ts)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/cfq/components/ReEstrusTaskProgress.tsx)、[旧单元卡](../../Sentri/mobile_webapp/src/pages/task/cfq/components/UnitDetailTaskProgress.tsx) |
| [妊娠检查](home-card-examples/pregnancy.svg) | 已检查／需检查的总量比例与任务日数；满条时有结束入口。 | **5 头本次窗口待检查**，其中复查 2 头；进度只算本次检查 8／20 头，避免不同轮次混成同一分母。 | [妊娠首页封装](../../Sentri/mobile_webapp/src/pages/home/components/tasks/PregnancyTask.tsx)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/yj/components/TaskProgress.tsx) |
| [分娩](home-remaining-production-cards/farrowing-live.svg) | 已分娩／任务母猪数比例、任务日数；单元详情还计算今日预产、超预产和正在分娩。 | **已记录正在分娩**优先，其次是预产日已过、今日预产；进度只数已分娩，死亡／流产退出任务及分母。总览与单元都进分娩房名单。见[分支说明](home-remaining-production-cards.md#分娩)。 | [分娩首页封装](../../Sentri/mobile_webapp/src/pages/home/components/tasks/FarrowingTask.tsx)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/fm/components/TaskProgress.tsx) |
| [产后检查](home-card-examples/postpartum.svg) | 已检查／已分娩母猪数比例与任务日数；满条可出现结束入口。 | **3 头已进入检查窗口且未记录**，其中明确逾期 1 头；进度 6／9 头，分母仅为当前已分娩且纳入检查的母猪。 | [产后首页封装](../../Sentri/mobile_webapp/src/pages/home/components/tasks/PostpartumTask.tsx)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/postpartum/components/TaskProgress.tsx) |
| [仔猪处理](home-card-examples/piglet.svg) | 已处理／任务总数比例与任务日数；没有把“今天无护理”与“整个计划完成”清楚分开。 | 示例为**当前无到期护理、明天继续**，仍保留在主列表；进度明示全日龄必做护理 1／8 窝。多护理项目按窝去重。 | [仔猪首页封装](../../Sentri/mobile_webapp/src/pages/home/components/tasks/PigletTask.tsx)、[旧总览卡](../../Sentri/mobile_webapp/src/pages/task/piglet/components/TaskProgress.tsx) |
| [断奶检查](home-card-examples/weaning.svg) | 旧 `LACTATION` 页面标题就是“断奶检查”，旧首页已有对应卡；旧记录按母猪，新版目标按窝／栏。 | **3 窝到检查窗口**，已记录 5／8 窝；检查记录与实体转舍分开。需建立新旧记录对象映射，同一任务不重复出卡。 | [旧分发范围](../../Sentri/mobile_webapp/src/pages/home/components/TaskRenderer.tsx)、[旧断奶卡](../../Sentri/mobile_webapp/src/pages/task/lactation/components/TaskProgress.tsx) |
| [结束保育检查](home-remaining-production-cards/nursery-pending.svg) | 旧首页未分发；旧任务页按栏提交检查结果，记录 `check_at`。 | 按任务栏位快照显示“3 栏待结束保育检查”和“已检查 5/8 栏”；范围收窄到单元时用同一口径。 | [旧保育任务页](../../Sentri/mobile_webapp/src/pages/task/nursery/index.tsx) |
| 结束育肥检查 | 后端有按栏检查能力；旧 Web 无对应任务页或首页卡。 | 按本轮决定暂留空。 | [旧分发范围](../../Sentri/mobile_webapp/src/pages/home/components/TaskRenderer.tsx) |
| [治疗疗程](home-card-examples/treatment.svg) | 以药品名和治疗名作标题，另列药品类别、涉及单元、剂量、总次数、猪数与按全部给药记录计算的百分比。 | **本次轮次 4 头待给药**、下一次时间；进度明确是本次已记录 6／10 头。药品、剂量等执行细节留在医嘱页，首页不以疗程百分比代替当前工作。 | [旧首页入口](../../Sentri/mobile_webapp/src/pages/home/components/HealthList.tsx)、[旧治疗卡](../../Sentri/mobile_webapp/src/pages/task/advice/components/HealCard.tsx) |
| [免疫](home-card-examples/vaccination.svg) | 疫苗名、剂量、“已下发 N 天”、已接种／目标数、进度条和“执行任务”按钮。 | **本次排程 6 头待接种**、疫苗名和已记录 10／16 头；使用计划时间，而非创建后天数判断今日或逾期。首页整卡只负责导航。 | [旧免疫卡](../../Sentri/mobile_webapp/src/pages/home/components/vaccine/VaccineTaskCard.tsx) |
| [转舍](home-card-examples/transfer.svg) | 旧首页 `TransferTaskList` 在未选单元时直接返回空；选单元后，同一转舍单分别显示转出、转入卡及各自数量。另有未在该总览入口使用的整单卡，列区域和按钮。 | 总览按**一转舍单一张卡**显示 8 头待确认、路线和已确认 12／20 头；进入单元仍是同一任务 ID，只按方向过滤，不把一单拆成两张首页任务。 | [旧首页转舍列表](../../Sentri/mobile_webapp/src/pages/home/components/TransferTaskList.tsx)、[旧整单卡](../../Sentri/mobile_webapp/src/pages/transfer/components/TransferTaskCard.tsx) |
| [出栏](home-card-examples/outbound.svg) | 标题、对象群、出栏比例、已离场／目标头数、分区单元列表和“执行”按钮。 | **9 头本单待确认出栏**、已确认 11／20 头；无计划时间字段时仅在已指派且可执行的情况下标“未设截止时间”，不从创建日推断逾期。 | [旧出栏卡](../../Sentri/mobile_webapp/src/pages/home/components/pigOutbound/PigOutboundTaskCard.tsx) |
| [采样](home-card-examples/sampling.svg) | 首页把**最近七天创建**的采样任务合为一张卡，只展示 `sampled_at` 数／任务总数，点击进入采样列表。 | **每个采样任务一张卡**，示例是 3 个猪只目标未采、已采 2／5 个目标；创建日不是执行日。无地点目标进入全场入口，不复制到每个分区。 | [旧首页过滤](../../Sentri/mobile_webapp/src/pages/home/index.tsx)、[旧采样汇总卡](../../Sentri/mobile_webapp/src/pages/home/components/OtherList.tsx) |
| [采精入口](home-card-examples/semen-entry.svg) | 旧首页按有公猪的单元生成采精卡，展示已采／需要采的数量、待处理标记和“执行任务”；它不依赖独立任务实例。 | **单元工具入口**，只写“进入采精”。在有独立采精任务 ID、计划与状态前，不因公猪存栏产生今日任务和待办数，因此不计入上面的任务卡总数。 | [旧采精生成逻辑](../../Sentri/mobile_webapp/src/pages/home/components/CollectionTaskList.tsx)、[旧采精卡](../../Sentri/mobile_webapp/src/pages/task/semen/collection/components/CollectionSummaryCard.tsx) |

## 使用这些示例时

- 图中均为分区总览卡；切入单元后沿用同一任务 ID，缩小数字与范围。跨分区转舍按各分区方向呈现，采精只出现在适用单元的工具入口。
- “进行中”来自任务权威状态；“可处理”代表已指派且当前可执行、没有明确时间的任务。二者都不能由卡片进度条推断。
- 计划时间、窗口、分母或任务对象不可靠时，使用[PRD 的安全降级文案](home-prd.md)，不按示例数字填充真实页面。
- 图片源由 [生成脚本](../scripts/generate_home_card_examples.py)维护；修改示例字段后运行 `python3 scripts/generate_home_card_examples.py` 再检查 SVG。
