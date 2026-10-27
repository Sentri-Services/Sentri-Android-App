# 第 12 项采样业务基础

2026-10-19。只读核对旧 Web `services/task/samplingQueries.ts`、`samplingMutations.ts`、`lib/offline/samplingCommandAdapter.ts`，以及后端 `domain/models/sampling.py` 和 `entrypoints/graphql/sampling.py`；哈希见 `verification/business-foundations/sampling/results.json`。

主业务实际提交为 `SubmitSampled`，后端输入只有 `sampling_task_id` 与可空 `remark`。旧 Web 离线展示还保存操作时间、人员、车间和目标摘要，但这些不是 mutation 输入，不应伪造为服务端值。后端 `submit_sample` 检查任务存在、未禁用，设置 `is_sampled=true`；没有明确防重复提交条件。原生本地队列因此按采样任务阻止第二条未确认/已成功待刷新提交，取消未发送操作后可重新入队。结果不明不自动重发；成功回执核对 `recordId`，后端当前 resolver 的 `record` 可空。真实 mutation transport 尚未在应用中注册。

任务读取用完整字段的单一查询供全量分页和详情复用，不使用可能舍入 JSON 的 fragment。目标类型 PIG、PERSON、ENVIRONMENT、MATERIAL、OTHER 保留原始 target ID；仅相应类型使用 pig/user 关联。试验目标的 `target_ids` 和任务的 `test_report_ids` 保留 JSON 字面量，关联报告可缺详情但索引仍保存。校验各层 farm、task/test/report ID、聚合数量、重复行及时间。全量列表与首页预取按模式和窗口分别缓存；旧 `initialAdditionalTaskCache.ts` 使用“未采样或指定当日已采样”，不能套到完整列表。页面中其他近七天展示规则应由调用方提供窗口，不写死在读取服务。

本地预览只在 QUEUED 且任务未禁用时将 `isSampled` 和备注作为待确认状态；服务端生成的 `sampled_at`、`sampled_by` 保持未知，原确认快照不改。UNCERTAIN/IN_FLIGHT、失败与 SYNCED 等待刷新分别标记；成功写入自动反映尚无服务端命令关联证据。简化入场 `CreateEntrySampling` 归第 17 项，不与此离线操作混用。
