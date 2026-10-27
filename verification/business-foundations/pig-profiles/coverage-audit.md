# 完整猪只读取覆盖核对

第 9 项进行中。本批完成旧完整读取字段并集的原生查询/模型/缓存；本地预览与生产任务关联组装未完成。

- 来源：旧 pig/fullDetailQueries.ts、fullListQueries.ts、singleQueries.ts 与 task/productionPigFields3.ts。GraphQL AST 对照六个定义，覆盖完整详情 120、列表 120、单猪 124、任务猪片段 95、PigTasks 6、count 2 个所选路径，无缺失；新档案共 157 路径。各定义存在重叠，不能相加当作不同字段数。
- 一条 NativePigProfiles 查询复用同一内联响应模型，避免通过 fragment Map 丢失自定义 scalar 精度。MockWebServer 验证完整关系、精度/scale、原始时间、统计缺失、空/不可见关系、ID 集合与过滤排序、count 关闭、厂区/关系 ID 拒绝、重复行、协议错误及上下文切换。
- 发现并修正未知读取枚举折叠：Apollo enum class 原先将 FUTURE_ROLE 转成 UNKNOWN__；配置十一类读取枚举 sealed 生成后，未知 rawValue 原值保留。Production mutation 枚举未变，既有已知筛选协议回归通过。initial-enum-regression.txt 是修复前复现；最终结果以 verify.txt 与 XML 为准。
- 独立 PigProfile 全字段 codec、format=1 信封、两个新快照 kind 实际接入 AppContainer.pigProfiles；schema=2 不变，旧基本详情无法满足完整读取。严格字段/类型/时间/JSON、请求身份和作用域验证；只在网络/AuthUnavailable 回退。
- 完整关系 null/空列表及统计 null/零分别恢复，Decimal 使用字符串保留 scale。大历史 JSON 2700013 Java 字符（含补充平面字符）分段读取后原值一致；两类快照参加 256 条容量裁剪，清理缓存不影响队列。
- verify.sh、130 JVM、97 常规 Android 通过，12 opt-in 默认跳过。pigProfileStage prepare/force-stop/restore 各一项显式通过，PID 31603→31643，三快照新进程恢复，远端读取 0，精确 Decimal/JSON、缺失详情、双语名称和作用域隔离通过。
- 来源/品种/采精控制新增 28 个中英文映射；YLL/YLP 保留缩写，语言设备测试明确两项可相同，其余映射继续检查语言变化。当前 262 文案/176 映射、46 operations/449 消费字段、23 离线命令。
- 仍需第 9 项属性/角色/死亡预览、第 8 项档案关联/元数据一致性/成功读取反映确认、第 10–18 项业务适配与全量审计。完整读取的分页 API 不等于自动预取全部厂区数据，也不代表所有 SDL 猪只关系已实现。
- 未新增真实后端读取/写入或后台重放，UI 八文件哈希一致；版本/发布五文件无差异，参考仓库干净。未推送或发布。
