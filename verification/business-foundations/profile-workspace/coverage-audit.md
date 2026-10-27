# 第十一批范围核对

2026-10-10。整体目标进行中；本批推进第 9 项属性/角色预览和第 8 项完整档案上下文。结果数值由最终 JVM/Android XML 的 testcase 计算，见 results.json / suites.json。Android Gradle 的 Finished 132 包含跳过重复计数，实际 XML 为 119：106 通过、13 opt-in 跳过。

| 要求 | 当前证据 | 结论 |
| --- | --- | --- |
| 属性原始 null/空/零/Decimal、ACTIVE 前置条件 | PigManagementProjectionTest、ProfileWorkspaceProcessRestoreTest | 已验证；生命周期改 DEAD 不代替死亡上报副作用 |
| 角色四动作、身份/性别条件、父母/时间原始 intent | source-rules.json、PigManagementProjectionTest | 已验证；不生成服务端时间或父母关系 |
| 批量角色原子性及重复目标 | 缺失目标/grower result、重复目标 JVM 回归 | 已验证；整条失败不留下部分 task/profile/metadata 变更 |
| 十类任务猪身份及已有元数据 | all-ten-task-relationships JVM 回归、ProfileWorkspaceTest | 已验证命令明确写入字段（含与档案相同的值）；同批元数据完整读取及材料身份一致性仍待补 |
| 生产命令和完整档案共用依赖顺序 | 未知属性→查情、grower↔角色 JVM 回归、SQLite 重开回归 | 已验证；十九生产规则+两管理规则支持预览，死亡规则尚未加入 |
| 保育/育肥跨模块同猪串行 | initial-growing-order-regression.txt 先失败，最终验证通过 | 已修复；原栏位分组保留，实际不同猪/栏位独立 |
| 档案关联上下文及分批 | 201 ID 按 100/100/1、批量/前置扩展、材料/记录/待提交公猪 Android 回归 | 已验证；父母只是引用，无关队列不触发档案读取 |
| 缺失/不可读、来源/时间、失败回退 | ProfileWorkspaceTest、最终 Android XML | 已验证；协议/业务/取消/损坏缓存/上下文错误不伪装成网络回退 |
| 重启恢复、未知不重放 | process-prepare.txt、process-restore.txt、process-events.txt | PID 2778→2914；三快照/两命令恢复，未知重发 0，刷新前远端读取 0 |
| 确认快照与待提交预览独立 | JVM 确认值断言、SQLite 直接读取、进程测试回执后断言 | 已验证；仅显式模拟确认读取+调用方证据解除 WAITING_REFRESH，自动反映判定未实现 |
| GraphQL、多语言 | verify.txt | 46 operations/449 字段、262 条目/176 映射；本批不增加新 operation/语言代码 |
| UI、发布、参考仓库边界 | results.json 中八哈希、五发布文件 diff、两参考树状态 | 本批无 UI、发布或版本修改；参考树干净 |
| 真实后端 | 本批仅模拟 remote/MockWebServer 和本地 SQLite | 未执行新增真实读写；不声称真实业务联调或自动同步完成 |
| 全业务覆盖 | 权威任务表第 8/9 项进行中、第 10–18 项待做 | 未完成；目标保持 active |

initial-stale-test-record.txt 保留新分组暴露的旧测试 fixture 错误：测试更换 input 目标猪后没有同步构造 record.groupKeys，先被一致性门禁拒绝。已改为按新 input 正常构造记录，仍验证原“缺失目标不部分应用”要求；最终全量验证通过。

接下来补死亡上报的本地预览，核对母猪未处理窝仔猪条件、实际猪与匿名/有身份仔猪、并行任务数量和死亡统计、图片子步骤。随后继续同批元数据及关联预览一致性；治疗、疫苗、采样、巡检、转舍、出栏、精液等第 10–18 项尚未实现。

initial-equal-value-write-regression.txt 保留独立快照值不一致的回归：命令值与 profile 相同，但任务值不同，仍需把明确写入字段应用到任务预览。新 JVM 测试先失败，修复后完整验证通过；未写入字段继续保留各自原值。

initial-queue-read-race.txt 在设备复现读取档案时命令已被领取却仍按旧 QUEUED 副本生成 PREVIEW。修复为先用队列发现关联档案，再在所有读取完成后重新获取队列生成投影；新增设备回归覆盖领取和取消，两者均使用最终队列状态。
