# 第 11 项疫苗任务：写入规则与后续读取

2026-10-18。参考旧 `mobile_webapp/src/services/vaccine.ts`、`db/task/vaccination/`、`lib/offline/vaccinationCommandAdapter.ts`，以及后端 `domain/models/vaccine.py` 和 `adapters/hasura_matv/healthy.py`。确切资源哈希见 `verification/business-foundations/vaccination-write/results.json`。

旧 Web 实际操作是 ExecuteVaccinationTask 和 FinishVaccinationTask。执行提交 task_id、有序 target_ids 与 executed_at；结束提交 task_id 与 finished_at。旧页面结束入口 `completion.ts` 要求在线且无阻塞命令；离线 handler 虽登记结束操作，但不能据此把原生结束入口默认开放为离线。当前 Android 只完成 GraphQL 定义、类型化输入、SQLite payload/依赖与显式测试 adapter，没有注册真实发送、后台重放或结束入口。

后端执行会对输入目标去重，再逐目标将 `completed_dose_count` 加一；只有达到 `dose_count` 才完成目标，全部目标满足时任务才自动完成。每次执行事件在物化表完成当前剂次，并按目标立即插入下一剂的待接种行；服务端事件时间、操作人和下一剂 scheduled_at 不能从本地输入推断。后端 `finish()` 可人工结束尚有未接种目标的任务，且不等于给全部目标补执行剂次。旧 Web 的 `markVaccinationTargetsCompleted` 把选中目标立即标为 COMPLETED 并把完成数加一，在多剂次场景与后端不符；原生读取和预览要依据剂次行重建。

同任务的接种命令显式依赖之前未决接种，即使设备时钟回拨也保持入队顺序；结束命令再依赖该任务全部未决命令。已有结束操作的任务拒绝再插入剂次或重复结束，取消后可重新入队；成功/发送中/结果不明的结束必须先确认。接种命令还共享每头猪的现有队列分组。不同疫苗任务及四维数据作用域隔离。

2026-10-19：完整任务列表/详情查询与两类 SQLite 快照已接入。旧列表筛选为非 DELETED，并包含所有未完成或指定日窗口内修改过的 COMPLETED；完整列表与该窗口筛选分键。读取核对 farm、task/target/pig 关联、`targets_aggregate` 数量、目标数量与连续剂次，保留每个 `(target_id,dose_index)` 的历史行、Decimal、JSON 数字字面量及原始时间精度。Apollo fragment 查询路径在测试中把 `123456789.12345678900` 舍入为 `123456789.12345679`，改用完整字段的单一行查询供分页与 farm+ID 详情复用。显式新进程恢复三快照、一命令且不调用远端。豁免的完整业务上下文、逐剂预览及成功读取反映证据仍待；真实后端读写仍待授权开发会话验证。

同日逐剂预览基础已接入：`VaccinationProjector` 只重放 QUEUED 意图，重复目标按后端去重，当前剂次完成后才追加下一剂；自然完成要求所有目标到达 `dose_count`，人工 Finish 不补目标剂次。未来由服务器生成的下一剂调度时间、事件修改时间和操作人保留未知，本地新行以命令 ID 标记。`VaccinationWorkspaceReads` 用确认详情快照和持久化队列重建预览，原快照不修改；新进程显式恢复已验证。SYNCED 等待刷新，只有调用方显式提供 `reflectedCommandIds` 才解除依赖，尚无自动读取反映证据。豁免与结算重算可能在其他后端事件中发生，当前未将其当作执行/结束预览的已确认副作用。

同日补豁免关系读取：Hasura `farm.view_vaccination_task_target.vaccination_exemption` 提供目标猪的当前物化记录，字段含 `exemption_hit`、`rule_codes`、`reasons`、`evaluated_at`、`effective_until`、`modified_at` 与操作者。原生逐剂快照保存完整记录及“该快照是否查询过豁免”标记，旧快照恢复为未知；关系为 null 仅表示本次查询未返回记录，不是“当前绝不豁免”。后端评价器有出生、母源抗体、转群/运输、分娩、配种、健康、近期接种、断奶和仔猪处理十二个规则码，已配中英文资源。后端任务结算由另一个定时/事件流程重算；执行与人工结束不会同步提供所有豁免因果证据。原生不凭这份可能过期的记录重算确认快照的豁免数或自动解除 SYNCED 等待刷新。
