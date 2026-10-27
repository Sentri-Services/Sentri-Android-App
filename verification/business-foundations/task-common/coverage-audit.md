# 通用任务与目录基础验收（第 8 项部分完成）

2026-10-09。整体目标仍进行中；本批不代替生产本地投影或后续业务。

| 要求 | 证据与范围 |
| --- | --- |
| 两通用契约与输入 | TaskCommon.graphql / TaskCommonModels.kt；Mark 保存 nullable remark 与 batch/pig/task，Complete 保存 batch/task/kind/nullable end，MockWebServer 验证空值、空字符串与精确时间 |
| 实际命令持久化 | CareCommandPayloadCodec 登记两操作；TaskCommonQueueTest 在 SQLite 写入/重开验证输入、分组与身份，当前共 19 种命令 |
| 结束屏障 | 入队推导全部同任务未决操作，claim 事务追加迟到依赖并存事件；FAILED/UNCERTAIN 阻塞、确认后解除，不同 scope 排除；整栏命令保持原子性 |
| 关闭竞态与重复 | 开始/未知/确认结束后拒绝新同任务写入；重复结束、直接/传递结束依赖形成的环被拒绝；同 operation ID 重复输入仍返回原记录，已知失败允许修复及明确确认后重试 |
| 旧入口规则 | completion.ts 要求 good network 且无未决任务命令；底层 adapter/handler 定义单独保留。本批没有离线结束入口或改变该门禁 |
| 任务目录与元数据 | TaskCatalog.graphql / GraphqlTaskCatalog；单查询用于目录和 exact ID；验证 farm/task/batch/line/operator/pig/boar 等嵌套归属、枚举、唯一项及统计；保存 mark、批次配置、计划、同批任务、材料/统计/保育汇总 |
| 筛选和排序 | 首页 SCHEDULED/RUNNING 或指定 [from, until) 已完成；时间偏移和亚毫秒边界不舍入，其他过滤条件仍生效；created/id DESC 与 planned/id ASC，offset/limit 和总数 |
| 目录持久化 | TaskCatalogCodecs / TaskCatalogReads / AppContainer.taskCatalog；元数据与页面独立格式 1、作用域和完整 request key；写入/重开/损坏拒绝、cache null/miss、错误边界、延迟上下文切换、256 条共享读容量 |
| 精度修复 | 新目录以及原猪只 list/detail 改为直接字段查询；MockWebServer 长小数/大整数 JSON、重量/体温及健康记录回归，避免 Apollo fragment 缓冲的浮点舍入；快照 schema 未改 |
| 新进程恢复 | TaskCommonProcessRestoreTest prepare/force-stop/restore 各显式一项通过，不同 PID；两命令、一元数据/页、迟到依赖、Decimal/JSON 精度恢复，未知标记重发 0，结束等待确认，开始结束后新写入被拒绝 |
| 多语言 | 两操作、十任务类型、三冲突原因新增映射；209 资源/123 映射，已有全映射设备检查通过 |
| 验证 | verify.sh、99 JVM、69 Android 通过；7 opt-in 常规跳过，新过程测试已显式执行；JUnit/XML、分组计数、原始构建和过程日志保存于本目录 |
| 边界 | UI 八文件哈希不变，版本/发布配置 diff 空，两个旧参考仓库干净；未注册真实业务 transport、未触发 mutation/推送/发布 |

GraphQL 39 operations/345 消费字段是目前原生契约数量，不是旧服务 54 query/100 mutation 的覆盖率。服务声明清单仍需逐项审计。

剩余：第 8 项生产待同步投影、完整目录/十类详情组装、辅助关联查询；第 9–18 项猪只状态/医嘱治疗/疫苗/采样/巡检/转舍/出栏/精液等适配和最后全范围验收。真实发送与自动重放需具体后端幂等及结果确认依据。
