# 中英文、GraphQL 与离线持久化完成核对

目标来自本轮用户持续目标的三项要求；额外约束为不做 UI，真实自动重放等待服务端协议。此前未提交的页面改动属于前一阶段，本轮保留而不新增/修改；boundaries.json 记录 8 个当前 UI 源文件及其早于本轮目标的修改时间和哈希。

| 要求 | 当前实现与证明 |
| --- | --- |
| 语言选择与持久化 | AppLanguage、AndroidLanguageStore；JVM 测试：SYSTEM/中文/英文、系统变化/未知语言回退、写入失败不发布新状态；LanguageFoundationTest：持久化重开及双语资源；分阶段新进程测试：强制停止、PID 不同、英文恢复 |
| 业务枚举 | BusinessEnumKeys/BusinessText：41 个角色、性别、生命周期、生产、分诊、查情、任务、命令状态映射；旧字典来源写入 docs/language-offline.md；两语言实际资源读取，未知/空值回退，复数 1/2 测试；自由字符串保留，不伪造 enum |
| 格式化 | BusinessFormatter：Decimal 不经 Double；显示小数位显式且有舍入测试；日期要求显式时区、同一时刻不同 offset 格式一致、非法日期拒绝；名称主/次语言/ID 回退测试。存储保留原始时间精度，显示到秒 |
| 请求语言一致性 | AppContainer 对身份、猪只和查情仓库注入同一 AppLanguage；GraphqlExecutor 捕获单次 tag，并在回包/认证重试时检查；MockWebServer 验证请求头与迟到语言回包取消、会话仍保留 |
| 缓存语言一致性 | CachedFarmRepository/SqliteSnapshotCache：本地化账户按语言 key，单用户绑定贯穿所有语言；LanguageFoundationTest 验证网络→语言缓存→缺少另一语言时不错误回退→双语言离线恢复→迟到响应隔离；原 PigCacheTest 10 项继续通过。稳定代码/双语猪只位置快照复用，不将语言写入命令 payload；旧无语言账户缓存保留，需对应语言首次在线读取 |
| GQL 契约与变更检查 | graphql-js AST 校验+contract.json：9 operations、115 消费字段；字段/参数类型、输入/default、枚举、scalar specifiedByURL、operation/fragment 定义。8 个场景证明无关扩展允许，删除字段/类型空值/必填输入/枚举/default/operation/fragment 变化拒绝。verify.sh 集成，不自动刷新 manifest，不在线 introspection |
| 生产切片查询和输入 | Estrus.graphql 的 NativeEstrusTask/NativeCheckEstrus；Apollo 生成成功；GraphqlEstrusReads/CheckEstrusInput 转换。4 个 JVM 契约测试：task/pig scope、缺失/非法响应、日期/枚举、显式 null 输入、真实 HTTP 格式（MockWebServer）、无假设的 Idempotency-Key |
| 离线持久化 | SqliteEstrusQueue 独立 schema 1、commands/attempts/events、外键、作用域+operation 唯一性、payload 版本/摘要。6 个 SQLite Android 测试验证重复入队/不同 payload 拒绝、作用域、原子 claim、多 helper、成功回执/执行码、延迟、损坏阻止、取消/依赖/冲突、入队/完成事务回滚 |
| 离线恢复与模拟闭环 | EstrusDispatcher 在 IO dispatcher 单条执行；确认成功、明确拒绝/冲突、明确未发送和未知结果分别保存。异常/取消、切换作用域后回执归原命令、旧尝试不覆盖新尝试、重开 IN_FLIGHT→UNCERTAIN、有确认才可重试均通过；新进程分阶段运行证明实际 PID 改变后命令恢复、不发送、确认可完成 |
| 真实自动重放边界 | AppContainer 注册读取与存储，未注册真实 transport、后台 worker 或 CheckEstrus mutation sender；EstrusTransport 仅在 Android 测试内实现模拟结果。生成 mutation 用 MockWebServer 验证，未向真实后端提交。已有服务端去重调查及后续门槛继续保留 |
| 不做 UI/保留发布和旧数据 | boundaries.json：UI 文件早于本目标，.github/版本/发布脚本无 diff；参考工程 worktree 为空。只新增文本资源/API、数据层和测试，没有页面或样式设计。未导入/清理 IndexedDB、未推送/Tag/上传/发布 |
| 验收与交付 | 最新 verify.sh、67 JVM、30 常规 Android、三环境 Debug 成功；3 个 opt-in 测试默认跳过，新进程测试 prepare/restore 显式各一项成功。spec/status/技术说明/README 已更新，原始日志和计数在本目录 |

限制：本轮无真实接口写入/真实自动重放，其他 Android 系统或硬件未验证；GraphQL 静态检查不能识别同名 scalar 的服务端语义变化；无语言元数据的旧账户记录不能误当新语言缓存。实际 UI 语言切换、同步页面与真实 transport 留待规范/后端协议明确后接入。

验证过程中曾出现 schema 错误字段和英文 Healthy 的不正确测试断言，均已修正后重新检查。失败尝试未计入最终通过数。
