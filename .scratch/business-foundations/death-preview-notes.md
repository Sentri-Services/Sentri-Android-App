# 第 9 项死亡预览：后端核对与实现边界

2026-10-10。第十二批已实现死亡/图片资源预览、跨批次成员上下文及设备恢复验证。以下后端规则继续约束实现；SDL 未公开状态保持明确缺口，不据此宣称完整服务端语义已复现。证据见 verification/business-foundations/death-preview/results.json。

参考 Sentri HEAD 为 `4c1a2ff49e8d938e1af053d844018cc140c63f38`。源文件哈希见 verification/business-foundations/profile-workspace/source-rules.json。

## 已核对规则

- `domain/models/pig.py`：`report_death` 对已持久化 DEAD 猪无操作；ACTIVE 转 DEAD，其余生命周期拒绝。新建 DEAD ghost 仍需要一次死亡事件。同条报告重复实际猪由共享 aggregate 防重复事件。
- `domain/services/pig_lifecycle.py` 的 `PigDeathService.report_pig_death`：有 pigId 但查不到实际猪时跳过；无 pigId 时创建后端 ID 的 ghost，出生/入场/退出等时间由后端决定。不能创建虚构本地服务端 ID 或时间。
- `service_layer/handlers/commands/pig.py`：先对所有实际猪且 motherId 为 null 的行调用 `assert_sow_litter_resolved_before_death`，再应用任何死亡行；前置条件不能因同一报告中较早的仔猪死亡预览而绕过。
- `service_layer/handlers/commands/utils/pig_task_membership.py`：死亡关联任务状态包含 PLANNED/SCHEDULED/RUNNING。查情、配种、复情、孕检和保育/育肥通过 remove 方法禁用目标；母猪阶段任务通过 disable 方法禁用，阶段中已禁用可能拒绝。
- 有 motherId 的死亡事件要调整同窝多个并行 LACTATION/FARROWING/PIGLET_PROCESSING 聚合。身份选择用非空耳号 OR 非空耳缺，移除首个匹配身份；无身份数量每行至多减一并下限为零。禁用母猪条目不调整。
- 母猪死亡前置条件读取 lactation 的 `is_exported` / `is_disabled` 和身份/无身份仔猪数。当前 LactationResult 未保存 `is_exported`，不能仅凭有仔猪就判定服务端拒绝，也不能凭字段缺失当作已处理。
- 死亡统计还使用 `alive_baseline_received`、`piglets_exported`、`record_piglet_death` 等写侧状态；当前读取模型不足以完整计算统计。需要核对现有 JSON/SDL 可读字段，再决定新增读取或明确等待确认的本地 intent，不能猜测默认值。
- 上报时的图片保持队列里的有序 DeathImage.Remote / Uploaded 引用。成功上传已有 DeathImageReceipt(key,url)；真实死亡图片 JSON 的文件 key/URL 由后端生成，预览不得将 operationId 或 data URL 伪装成 URL。

## 与旧前端的差异

旧 `mobile_webapp/src/db/task/production/deathReport.ts` 本地适配主要更新生命周期、禁用任务猪，并只处理分娩/仔猪处理窝；没有完整的后端哺乳并行窝调整和统计。身份过滤还与后端首个匹配移除不同。实现依据应以现有后端为准，不照搬前端简化行为。

## 第十二批实现与剩余范围

跨批次 PLANNED/SCHEDULED/RUNNING 成员完整分页、缺失与不存在区分、匿名操作行身份、全行预检和原子投影、有效图片资源回执、实际猪/母猪共同分组均已实现。覆盖十四项死亡 JVM、六项设备回归、一项显式双阶段进程恢复；另加一项目录 pigIds 线协议回归。完整结果为 156 JVM / 112 常规 Android，14 opt-in 默认跳过。成功上报回执单独不能改写确认快照。

仍缺后端公开 lactation.is_exported、alive_baseline_received、piglets_exported 等写侧状态。母猪死亡检查遇到未决窝保留 MISSING_CONTEXT/SOW_EXPORT_STATE，死亡统计保留 MORTALITY_COUNTERS；不推断服务端通过/拒绝或伪造统计。当前实际猪同类多活动任务的 backend query 无排序且取首项，预览明确缺上下文，不能任意挑一个。

第 8 项同批元数据、完整列表/详情/材料一致性和自动读取反映证据继续待补；精液引用依赖第 16 项；第 10–18 项继续逐项实施。CheckGrowingInput 的实际猪组继续保留。死亡报告在第十二批增加 motherId 修改组（即使已有 pigId），上传目标身份和原 payload 不变。
