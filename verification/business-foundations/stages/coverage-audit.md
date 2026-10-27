# 产后、哺乳、保育、育肥基础验收

2026-10-09。上一批完成分娩/寄养/仔猪基础，是具体实现进度。本批继续任务 7；整体目标仍进行中。

| 要求 | 当前证据 |
| --- | --- |
| 四操作/输入 | ProductionStages.graphql、ProductionStageModels.kt；SowAssessment 七必需枚举/三脱垂字段，哺乳可空母猪检查及身份/分类；保育/育肥整栏汇总输入 |
| 读取模型 | GraphqlStageReads 校验 farm/task/pig 和嵌套记录归属，拒绝负数量/重复记录/未知结构化枚举；保留 raw check_status JSON、身份 breeder、Decimal 和原始时间 |
| 实际持久化 | CareCommandPayloadCodec 注册四操作，SQLite 实际写入/重开/重复 operation identity 验证；null/空列表/0、Decimal scale 和脱垂 null/false 均不强转 |
| 顺序粒度 | 母猪检查与同猪哺乳/寄养串行；整栏汇总按 task/location 串行，其他栏位独立，含冒号的不同分段不会碰撞；不拆分原子汇总 |
| 四类快照 | AppContainer 及 StageTaskCodecs 实际接入 ProductionTaskReads；写 SQLite→重开→网络失败回退比较完整 DTO，跨作用域/类型拒绝借用 |
| 保育/育肥差异 | 保育使用 nursery_locations，育肥 SDL 没有同类 view，只保存现有 pig/result 数据，codec 拒绝伪造保育位置汇总进入育肥类型 |
| 新进程 | StageProcessRestoreTest prepare/强制停止/restore 各一项通过，不同 PID；四快照/四命令/精度/脱垂字段恢复；未确认产后不重发并阻塞哺乳，两个独立栏位模拟执行成功，确认后哺乳可领取 |
| 多语言 | 四操作和八检查枚举组；194 资源/108 映射；全映射设备语言验证通过；静态检查修复数字资源名漏检，隔离临时 fixture 证明双语言同时漏掉 score_1 会失败 |
| 验证 | verify.sh、91 JVM、57 常规 Android 通过；6 opt-in 默认跳过，新进程测试已显式执行；原始日志/JUnit/suite 汇总已保存 |
| 边界 | 8 UI/样式源哈希不变，版本/发布配置无 diff，两个旧工程保持干净；没有推送、发布、真实业务 mutation 或后台自动重放 |

原始接口测试为 MockWebServer，发送为模拟 transport。当前共 17 种命令和 10 类生产任务快照，不代表通用任务/列表/辅助查询/本地投影或其余业务完成。后端幂等与结果确认仍需具体证据，真实自动同步未注册。
