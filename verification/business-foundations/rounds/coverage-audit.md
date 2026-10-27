# 复情、孕检与生产任务快照覆盖核对

2026-10-09。本批前一 goal turn 为具体进度：补查情提交、配种、共用队列、任务表及验证。本批继续实现；全业务目标仍进行中。

| 要求 | 当前证据 |
| --- | --- |
| 复情 GraphQL/输入/数据 | NativeReEstrusTask/CheckReEstrusPig/SubmitReEstrusStatus；任务/位置/结果及两个独立输入，JVM 线协议/归属/枚举测试 |
| 孕检 GraphQL/输入/数据 | NativePregnancyTask/CheckPregnancyPig/SubmitPregnancyStatus；可空 Int 复查天数、nullable is_pregnant；没有虚构检查方式字段 |
| 离线 payload/持久化 | 四操作实际接入格式 2 codec 和共用 SQLite；保留 unit selector、ID 顺序、null/0；设备重开数据库及新进程恢复验证 |
| 轮次依赖 | 同 scope/module/task/原始 unit；提交依赖所有未决操作，包括未选中的猪；检查依赖前次提交；FAILED/UNCERTAIN 阻塞沿用共用状态规则 |
| 顺序 | 时钟回拨时显式传递依赖优先；设备测试证明检查→提交→下一轮检查可依次领取，无同猪死锁 |
| 四类任务离线读取 | 实际 AppContainer 接入 ProductionTaskReads 与四 codec；查询/保存/重开/离线读取比较完整 DTO，涵盖时间精度、枚举、位置、nullable 标志、配种材料记录 |
| 缓存边界 | env/OAuth/user/farm、task type/ID 隔离，缓存错误/缺失/合法 null 分开；业务/协议拒绝和延迟上下文切换不会被缓存掩盖；容量/clear 不删除队列 |
| 多语言 | 四操作、两组三结果；141 条资源/55 映射静态校验，既有设备资源切换测试覆盖所有映射 |
| 验证 | verify.sh 通过；77 JVM、45 常规 Android 通过，4 opt-in 默认跳过；productionStage prepare/restore 各另行执行一项成功 |
| 新进程 | 强制停止与不同 PID 断言；两任务快照、四命令及依赖恢复；未确认状态自动重发 0，明确回执后提交可领取 |
| 来源审计 | 固定旧仓库 commit/文件哈希的 54 query、100 mutation、5 fragment 声明清点；不作为 schema/原生实现完成证明 |
| UI/发布/旧工程 | 8 UI/样式文件哈希不变；版本/部署契约/发布脚本/workflow 无 diff；参考工作区干净；没有发布/推送/真实业务 mutation |

剩余范围：分娩/寄养/仔猪及后续模块；完整任务/关联列表、直接 recheck view 等辅助查询、本地待确认投影、精液本地引用与最终全业务核对。已发现孕检复查时间旧前端/后端条件不同，记录于 docs/business-foundations.md，当前以服务端值与原始输入为准。
