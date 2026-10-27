# 剩余生产任务：首页卡片评审稿

> 2026-10-14。接着[查情](home-heat-card-revision.md)、[配种](home-breeding-card-revision.md)和[返情检查](home-return-heat-card-revision.md)的讨论，补齐孕检、分娩、产后检查、仔猪处理、断奶检查及结束保育。结束育肥暂留空。下方批次、日期、头数只为看卡片排版，不是当前农场数据，也不是 Android 已接入的业务卡。设计依据为 Sentri-UI `de7ab8e7768b3482812740cd622a53530f4cd160` 加当时未提交的 Home 规则、工作台 PRD；各任务 feature 仍有草稿和未决项。

## 先看这几条

- 首页按**任务实例**出卡：分区总览一项任务一张；进入单元仍是同一张任务，只收窄对象。任务进行中，即使今天没有动作，也留在“今日工作”；真正结束由任务状态决定。
- 主句只说最值得进任务处理的一件事：目前待检查的母猪、待处理的窝、已记录在产的母猪，或有依据的下一次日期。一个数字只数一次猪／窝。辅助句最多一行；详细结果、原因、记录人和逐栏分布留在任务内。
- 进度条必须写出“做了什么／总共多少”。进度满格只说明这组记录已齐，不出现首页结束按钮。单元做完，也不能代替整项任务结束。完成与提前结束的卡统一使用[首页 PRD 的收据规则](home-prd.md#3-任务进入首页的规则)，不再为每类画一张重复的完成卡。
- **“逾期”要有配置的期限。** 过了预计分娩日、计划开始日，不能一概写成任务逾期；卡片可准确写“预产日已过”。任务日期、个体检查窗口与日龄护理窗口各算各的。
- 总览整卡进任务概况，单元整卡进对应名单；**分娩采用新 feature 的特例：总览和单元都进分娩房名单**。只有权威已关闭状态才进首页“已关闭”。图中没有任何点击即提交或快捷结束动作。

## 孕检

任务身份是任务 ID、批次，以及本次检查的明确次序。首次、二次检查和“不明确后复查”不能靠同一个 `submitted_at` 混成一轮。主数为窗口已开、这次还没有有效检查记录的去重母猪；明确安排且已到日的复查也在其中。进度只算**本次**检查的记录，不把“不明确”当作最终已孕，也不把后续复查算成已经做完。实际录入从母猪的当前任务进入；总览可进任务概况，单元进检查名单。

| 场景 | 卡上怎么说 | 依据与边界 |
| --- | --- | --- |
| [今天有检查](home-remaining-production-cards/pregnancy-due.svg) | `5 头待孕检`；其中复查 2 头。 | 到期对象与复查按猪只 ID 去重。 |
| [有明确过期项](home-remaining-production-cards/pregnancy-overdue.svg) | 仍报 5 头待检，辅句写其中 3 头已过检查期限。 | 3 头是 5 头的子集，不能相加。 |
| [本单元等待](home-remaining-production-cards/pregnancy-next.svg) | 有排程才写下次日期，仍显示本次记录进度。 | 日期来自个体配种时间、任务配置和复查安排。 |
| [检查信息待核对](home-remaining-production-cards/pregnancy-unknown.svg) | `检查信息待核对`，进入任务查看对象与安排。 | 对象、排程或记录无法核实时，不猜测到期人数。 |

![孕检：今天有检查](home-remaining-production-cards/pregnancy-due.svg)
![孕检：过期子集](home-remaining-production-cards/pregnancy-overdue.svg)
![孕检：单元等待](home-remaining-production-cards/pregnancy-next.svg)
![孕检：检查信息待核对](home-remaining-production-cards/pregnancy-unknown.svg)

旧首页总览算 `submitted_at` 人数／批次容量与任务猪只数的较小值，满条会露出结束入口；单元卡用任务 ID 索引找配置，并把缺 `mating_at` 的距离设为 `-1`，会混进已到期。本稿改为逐头算本次窗口和明确复查，无法核定检查信息时给通用提示。新版孕检 PRD 将“未孕”母猪转回待配、复查保持任务打开；何时移出批次、跨窗口复查归谁、检查轮次尚未决定，**首页不替这些流程做决定**。

## 分娩

每头母猪的预产日由自己的配种时间和农场配置计算。Home 卡优先顺序是已记录正在分娩、预产日已过且尚未分娩、今天预产、下一次预产。开始分娩以首次记录为准；没有首次记录不等于母猪没有开始。进度写“已分娩 X/Y 头”，分子只数已分娩记录，分母只数当前仍在任务中的应分娩母猪。死亡和流产母猪直接退出批次与任务，不留在卡上，也不计入分子或分母。这是本轮产品决定；Sentri-UI 当前分娩 feature 的房间行设计需另行对齐。

| 场景 | 卡上怎么说 | 点击后 |
| --- | --- | --- |
| [在产](home-remaining-production-cards/farrowing-live.svg) | `2 头正在分娩`；若还有预产日已过对象，只带一行数量。 | 分娩房名单，可继续现有记录。 |
| [预产日已过](home-remaining-production-cards/farrowing-past-due.svg) | 写`预产日已过`和`尚无分娩记录`。 | 核对母猪和记录；不替她判断生理状态。 |
| [今天预产](home-remaining-production-cards/farrowing-today.svg) | 未有更高优先状态时显示。 | 分娩房名单。 |
| [下一次预产](home-remaining-production-cards/farrowing-next.svg) | 今天没有上述对象且日期可靠时显示。 | 分娩房名单。 |
| [预产日缺数据](home-remaining-production-cards/farrowing-unknown.svg) | 说清需要核对，不给假的到期头数。 | 分娩房名单的需核对范围。 |

![分娩：已记录在产](home-remaining-production-cards/farrowing-live.svg)
![分娩：预产日已过](home-remaining-production-cards/farrowing-past-due.svg)
![分娩：今日预产](home-remaining-production-cards/farrowing-today.svg)
![分娩：下一次预产](home-remaining-production-cards/farrowing-next.svg)
![分娩：日期待核对](home-remaining-production-cards/farrowing-unknown.svg)

旧首页总览只看 `farrowing_pig.status=Completed` 的比例，单元另列今日预产、超预产、在产，还用 `last_farrowing_at` 距当前 45 分钟算一类关注。它没有把“最后一次记录较久”证明为异常；首页不自动报警。旧代码对 `after_mating_days` 的缺省判断也需修正后才能信任预计日。总览与单元入口沿新版分娩房名单设计；死亡／流产退出规则按本轮决定调整。

## 产后检查

只有**实际已有分娩记录**的母猪才进入检查范围，按每头的分娩时间和农场检查窗口算到期日；后续新增分娩时分母会增长。复查必须有**明确排程**，异常答案本身不自动生成一个首页“今日复查”。任务能否结束还受分娩任务状态约束，但这个判断留在任务详情。

| 场景 | 卡上怎么说 |
| --- | --- |
| [到期且含过期](home-remaining-production-cards/postpartum-due.svg) | 3 头待检，其中 1 头过期。 |
| [明确复查](home-remaining-production-cards/postpartum-recheck.svg) | 2 头待检，其中 1 头是已有排程的复查。 |
| [下次检查](home-remaining-production-cards/postpartum-next.svg) | 当前没有到期检查，显示可靠的最近日期。 |
| [等待分娩](home-remaining-production-cards/postpartum-wait.svg) | 暂无分娩记录，不显示 `0/0` 或“已做完”。 |

![产后检查：到期](home-remaining-production-cards/postpartum-due.svg)
![产后检查：复查](home-remaining-production-cards/postpartum-recheck.svg)
![产后检查：下次日期](home-remaining-production-cards/postpartum-next.svg)
![产后检查：等待分娩](home-remaining-production-cards/postpartum-wait.svg)

旧首页总览用`check_at`数量／批次容量，单元算法却先过滤 `!is_farrowed`，并把距分娩的分钟差当成是否可查；两边的范围和窗口都不一致。新版研究提出异常后出现症状与复查，但 feature PRD 又把复查触发、合格判定和必填项列为未决；首页只采纳**已安排复查**这件确定的事。

## 仔猪处理

主数按**窝**算：一窝只要有一个必做护理项目已进入计划窗口且未记录，就计一窝；两项也只计一窝。过期窝是这个主数的子集。进度是“**整个日龄计划**的必做护理都完成”的窝数，不是“今天的项目做完”的窝数。每窝的计划日龄依据实际出生／分娩时间和农场配置；母猪死亡但仔猪仍在窝里，护理不消失。项目的免做、过窗失效和身份规则没有确定时不能靠前端猜。

| 场景 | 卡上怎么说 |
| --- | --- |
| [今天有项目](home-remaining-production-cards/piglet-due.svg) | 4 窝今天有护理要做；其中 2 窝各有两项。 |
| [有项目已过窗口](home-remaining-production-cards/piglet-overdue.svg) | 3 窝有护理未完成，其中 2 窝过窗；不把护理项目数当窝数。 |
| [下个护理日](home-remaining-production-cards/piglet-next.svg) | 今天无到期项目，仍保留整项任务卡和全计划进度。 |
| [等待新窝](home-remaining-production-cards/piglet-wait.svg) | 当前没有可计算护理时间；不写整项完成。 |
| [本单元已做完](home-remaining-production-cards/piglet-unit-done.svg) | 单元全计划已做完，但任务状态仍进行中。 |

![仔猪处理：今天有护理](home-remaining-production-cards/piglet-due.svg)
![仔猪处理：已过窗口](home-remaining-production-cards/piglet-overdue.svg)
![仔猪处理：下一次护理](home-remaining-production-cards/piglet-next.svg)
![仔猪处理：等待新窝](home-remaining-production-cards/piglet-wait.svg)
![仔猪处理：本单元已做完](home-remaining-production-cards/piglet-unit-done.svg)

旧首页总览把 `todo_items.every(completed_at)` 当整窝已完成：空项目表也会得到 `true`；单元卡取一类项目的**最晚**分娩时间找下次日期，还会在缺时间时从今天加计划天数。这些都不能用于新卡。新 feature 的按栏、按日龄、一项一勾并一次提交，是设计优先级更高的工作方式；它对身份、跨设备冲突和可选项目仍有未决简化，详情实现前须敲定。

## 断奶检查

新版把这项检查放在**窝／栏**上：记录断奶头数、平均重量、留种数和母猪状况。卡片只数进入配置窗口、尚未有本次检查记录的窝。检查完成不等于仔猪已经转舍或母猪已经进入下一批。仔猪处理的结束交接只给它提供进入名单的事实，不替它完成检查。

| 场景 | 卡上怎么说 |
| --- | --- |
| [今天有检查](home-remaining-production-cards/weaning-due.svg) | 3 窝待断奶检查，已记录 5/8 窝。 |
| [有明确过期](home-remaining-production-cards/weaning-overdue.svg) | 只有配置了检查期限，才提示已过期。 |
| [下次检查](home-remaining-production-cards/weaning-next.svg) | 当前没有到期检查时显示下次日期。 |
| [安排不清](home-remaining-production-cards/weaning-unknown.svg) | 不从仔猪日龄样例里的“21 天”硬算出今日待办。 |

![断奶检查：今天有检查](home-remaining-production-cards/weaning-due.svg)
![断奶检查：已过配置期限](home-remaining-production-cards/weaning-overdue.svg)
![断奶检查：下次日期](home-remaining-production-cards/weaning-next.svg)
![断奶检查：安排待核对](home-remaining-production-cards/weaning-unknown.svg)

旧 `LACTATION` 的页面标题就是“断奶检查”，不是另一个“哺乳检查”任务。旧记录落在母猪 `check_at`，新版设计改为按窝／栏，因此任务身份可以沿用，但记录对象需要明确迁移。新的检查、实际断奶转移、选留种猪三者的职责仍在 Sentri-UI 的开放决定中。上线时若只有旧 `LACTATION` 实例，就先保持该任务身份，不要凭同一批次又生出一张断奶卡。

## 结束保育与结束育肥

旧 Web 有“结束保育检查”任务页，按栏展示检查状态；弹层提交 `location_code`、`check_at` 和健康等结果。首页旧版未渲染该任务，但可以从任务页事实生成新卡：当前任务有 8 栏，5 栏已记录检查，就显示“3 栏待结束保育检查”，进度“已检查 5/8 栏”。单元范围只筛选本单元栏位。`check_at` 只表示已记录检查，任务结束仍看权威状态。栏位集合若发生变化，分子与分母必须来自同一份任务快照；不能把全场当前栏位数直接当任务分母。未拿到可靠栏位集合时用“检查栏位待核对”入口卡。

![结束保育：有待检查栏位](home-remaining-production-cards/nursery-pending.svg)
![结束保育：单元已检查完](home-remaining-production-cards/nursery-unit-done.svg)
![结束保育：栏位待核对](home-remaining-production-cards/nursery-unknown.svg)

旧后端存在按栏 `check_by_location` 操作，Android 有 `NativeFatteningTask` 查询和检查 mutation；但旧 Web 没找到结束育肥任务页或首页卡，Android 查询也没有保育那样的 `fattening_locations.check_at` 列表。按本轮约定，**结束育肥首页卡暂留空**，等业务流程和新版 feature 补齐后再设计，不以后台命令推断首页文案或进度。

## 要改的接口和数据口径

下表说的是**目标卡片不能靠当前 Android 读取和旧算法可靠做出的部分**，不是声称后端数据库一定没有这些事实。实施时可扩展原查询、增加投影，或由服务端直接给出按任务实例／单元计算的 Home 摘要；无论哪种做法，总览与单元必须使用同一套资格和去重规则。

| 任务 | 当前可读 | 还缺什么；落地前的显示 |
| --- | --- | --- |
| 孕检 | `NativePregnancyTask` 有 `mating_at`、`check_status`、`submitted_at`、`re_check_at`，目录有批次配置。 | 要有本次检查身份、每次记录与复查活动、有效窗口／截止、复查归属；单行最新结果不足以复原多次检查。未补齐时仅显示任务状态、可靠的单次记录事实，不给“本次待检 N”。 |
| 分娩 | `NativeFarrowingTask` 有任务母猪、`status`、配种与分娩时间、记录。 | 需要死亡／流产退出后的当前任务名单，以及已分娩记录；进度只按这批当前对象计算。预产配置与农场时区须按个体核算，不能沿用旧缺省判断。 |
| 产后检查 | `NativePostpartumTask` 只有单个 `check_at`／`check_status` 和 `farrowing_at`。 | 要有配置窗口的起止／截止、复查的独立排程和记录、明确的纳入／退出资格；一个最新检查时间无法区分初检与复查。未补齐时可显示进行中和已记录事实，不给复查或逾期数。 |
| 仔猪处理 | `NativePigletTask` 有每头关联的 `todo_items.plan_days/completed_at`、`last_farrowing_at`。 | 要有稳定窝身份与跨栏转移关系、**每窝完整必做计划**、项目窗口终点、可选与免做状态、实际护理覆盖和冲突处理；只有一日计划值不能断言过期或全计划完成。未补齐时不报“今天 N 窝”“完成 X/Y 窝”，只进任务名单。 |
| 断奶检查 | 当前只有 `NativeLactationTask`，没有单独 `WEANING` 任务目录及窝级检查查询。 | 明确新检查的任务 ID、窝／栏 ID、排程窗口、记录与实际转舍的不同状态，以及旧 `LACTATION` 如何迁移。未建立映射前不额外生成新版卡。 |
| 结束保育 | `NativeNurseryTask` 有 `nursery_locations.location_code/check_at`；旧 Web 有按栏提交。 | 需稳定任务栏位快照和单元归属，确保待检数、进度分母一致；未核定时用入口卡。 |
| 结束育肥 | 后端有按栏检查操作，Android 有任务读取；旧 Web 无对应任务页。 | 暂留空，待任务页与 Home 口径确定。 |

另外，`NativeProductionTaskCatalog` 当前只给任务身份、状态、猪只和部分类型资料；它不是可直接画上述主数的 Home 摘要。需要全量分页与**按分区／单元统一过滤**，并由农场时区判断今天。任务已完成时立即换成收据，不能让旧缓存中的到期数复活。真实接口接入前，卡片只能作为设计样例。优先验收：一窝多项目只计一窝、复查与初检不合并、死亡／流产退出分娩任务、旧 `LACTATION` 与新版断奶不重复出卡、保育栏位快照一致、进度 100% 不改变任务状态。

## 还需要确认的业务规则

1. **孕检**：首次／二次检查的任务实例怎样标识；窗口外复查归哪项任务；“未孕”母猪何时从原批次移出。卡片不凭任务 ID 字符串拆轮次。
2. **产后检查**：哪种异常必须复查、复查何时到期、可否由检查者自行安排。未确定前只有实际存在的排程才显示在卡上。
3. **仔猪处理**：每个农场的必做与可选项目、完整日龄窗口、免做和过窗规则、同窝多个猪只的记录覆盖怎么算。原型中“未来没项目的栏也算 Done”属于待确认简化，本稿不采用。
4. **断奶检查**：旧 `LACTATION` 任务的母猪记录如何迁移为新版窝级检查；实际断奶转移、留种身份分别归哪一步。决定前不能双卡并列。
5. **结束保育**：任务栏位快照、单元归属和有效检查记录口径；结束育肥任务页与首页展示另行设计。

## 来源

- 新版：[Home 展示规则](../../Sentri-UI/ux/research/home/HOME-TASK-DISPLAY-RULES.md)、[任务卡组件](../../Sentri-UI/ux/research/home/HOME-TASK-CARD-COMPONENT.md)、[工作台 PRD](../../Sentri-UI/features/workbench/PRD.md)、[孕检](../../Sentri-UI/features/pregnancy/PRD.md)、[分娩](../../Sentri-UI/features/farrowing/PRD.md)、[产后](../../Sentri-UI/features/post-farrowing/PRD.md)、[仔猪处理](../../Sentri-UI/features/piglet-processing/PRD.md)、[断奶](../../Sentri-UI/features/weaning/PRD.md)。
- 旧版：[首页任务计算](../../Sentri/mobile_webapp/src/pages/home/components/hooks/useTaskData.ts)、[孕检计算](../../Sentri/mobile_webapp/src/pages/home/components/hooks/useReproductiveTaskData.ts)、[任务分发](../../Sentri/mobile_webapp/src/pages/home/components/TaskRenderer.tsx)、[断奶页面文案](../../Sentri/mobile_webapp/src/lang/zh-CN/task/lactation.ts)、[保育任务页](../../Sentri/mobile_webapp/src/pages/task/nursery/index.tsx)、[保育任务栏位逻辑](../../Sentri/mobile_webapp/src/pages/task/nursery/assists.tsx)；本仓库[目录查询](../app/src/main/graphql/TaskCatalog.graphql)、[孕检查询](../app/src/main/graphql/Pregnancy.graphql)、[分娩和仔猪查询](../app/src/main/graphql/FarrowingPiglet.graphql)、[产后和阶段任务查询](../app/src/main/graphql/ProductionStages.graphql)。
- 图片由 [`generate_home_remaining_production_cards.py`](../scripts/generate_home_remaining_production_cards.py) 生成。卡片采用 Home 组件的信息层次，图标是占位符；真实图标、文案资源与各 feature 的详情页另行实现。
