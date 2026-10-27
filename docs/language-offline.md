# 中英文基础、GraphQL 契约与 CheckEstrus 队列

2026-10-09。这是已完成的中英文/CheckEstrus 基础阶段记录，权威任务状态见 [.scratch/language-offline/spec.md](../.scratch/language-offline/spec.md)。后续全业务扩展以 [新任务表](../.scratch/business-foundations/spec.md) 和 [当前实现说明](business-foundations.md) 为准。本阶段不修改页面/组件/样式；数据和文本 API 留给后续 UI 使用。前一阶段的读链路与证据见 [进度](status.md)。

## 中英文基础

AppContainer.language 提供持久化 SYSTEM/CHINESE/ENGLISH 选择与 StateFlow。调用 select 完成同步持久化后才发布状态；current 重新检查系统 Locale。中文（含台湾等 zh Locale）解析为 zh-CN，其余语言回退英文，与旧 Web 的中/英分支一致；不在本轮改变 Activity 配置或添加选择页面。需要 UI 时由正式组件消费该 API，不能自行使用系统 Locale 另建请求语言。

AppContainer.businessText 根据有效语言读取原生资源；BusinessEnumKeys 明确映射当前猪只角色、性别、生命周期、生产状态、分诊、查情结果、任务状态与命令状态。来源：旧 services/pig.ts 的角色/性别字典、lang/{zh-CN,en}/offline.ts 与猪只/查情词库。未知代码保持原值；null 使用缺失文案。健康事件等自由字符串没有明确枚举契约，不推测翻译。数量用 plurals；BusinessFormatter 保留 Decimal 精度，只有调用方指定小数位才舍入，HALF_UP 是该显示 API 的舍入方式，不改变保存值；时间显示精度到秒，存储和网络仍保留原始小数精度。调用方必须传业务时区；语言不能改变日期所代表的时刻。

所有实际 GraphQL 仓库共用 AppLanguage 的有效 tag，单次请求（含认证重试）固定其 tag；返回前语言不同则取消旧结果。账户里的单语服务端名称按 current:zh-CN/current:en 分别缓存；用户绑定变化清掉旧用户的所有语言 account 元数据，不影响其隔离的业务快照。旧 current 账户记录没有语言元数据，因此不会自动冒充新语言缓存；保留原记录，升级后需成功在线读取一次对应语言才能离线恢复账户。猪只/位置的稳定代码、双语名称及业务 ID 不按语言重复缓存，队列 payload 不含翻译文本。

scripts/verify-language.py 检查中英文键、类型、格式占位符、复数 other 和业务枚举资源；AndroidLanguageStore 与 BusinessText 通过设备数据层测试，不创建页面。

## GraphQL 契约

新增 Estrus.graphql：NativeEstrusTask 按 task ID 与 batch.farm_id 查任务，猪只关系再次限制 farm_id；GraphqlEstrusReads 校验回包任务、厂区、类型 ESTRUS、猪只及查情记录归属。无记录返回 null，非法结构/未知查情状态返回 Protocol。返回 disabled 和任务状态，后续业务判断沿用具体服务端规则，不发明状态转换。

NativeCheckEstrus 仅是编译过的 mutation 定义：输入为 task_id、pig_id、check_status、nullable check_at，错误以现有 err_msg 返回。ProductionPigId 映射 String，ProductionDatetime 使用严格 TimestampAdapter；check_at null 显式发送 null（沿用旧 handler），不混用“未提供”和空字符串。CheckEstrusInput 是业务/持久化输入，通过 graphql() 转生成类型，不保存 Apollo 类型。

旧源码依据：services/task/productionQueries.ts、productionMutations.ts 和 lib/offline/estrusCommandHandler.ts。真实幂等仍未证明，见 [离线调查](offline-devices.md)。AppContainer 只暴露读取仓库和队列，不注册 mutation transport 或后台发送。

工具位于 tools/graphql-contract，锁定 graphql-js 版本及 npm lock。日常检查只读取本地 SDL，无 introspection、凭证或线上写入。首次 verify 通过 npm ci 安装工具依赖；以后可在本地依赖存在时离线运行。开发环境需要 Node.js/npm；工具不是 APK 依赖。

```bash
node tools/graphql-contract/check.cjs
node tools/graphql-contract/test.cjs
node tools/graphql-contract/check.cjs --schema /absolute/path/candidate.graphqls
```

检查器用 GraphQL AST 校验全部 operations，记录消费的字段类型/参数、输入类型/默认值、枚举值、scalar specifiedByURL 和 operation 定义。输入对象递归记录其可接受字段（包括递归关系），因此部分契约变更范围大于当前函数实际使用字段。它报告“需要审阅的契约变化”，不把所有差异都判为破坏性变更；无关输出类型扩展允许通过。scalar 同名但线协议变化无法静态证明，需要实际 adapter 测试。

确认变更的来源、兼容性和本地数据影响后，显式运行 node tools/graphql-contract/check.cjs --write 更新 contract.json，再生成 Apollo 并执行 verify.sh。不要为修复失败自动刷新契约。真实 schema 下载继续沿用 README 的安全环境变量命令，本轮未下载或修改旧 schema。

## 离线持久化与恢复

本阶段实现的 SqliteEstrusQueue 使用独立数据库 native-estrus-queue.db（schema 1）保存 commands、attempts、events；启用外键。它不参与只读缓存容量裁剪和 clearCache。该历史阶段仅 CheckEstrus，不扩展全部写操作，不包含精液等新实体引用映射。后续共用 API 与 schema 2 升级见 [当前实现说明](business-foundations.md)。

commands 包含 environment/OAuth/backend user/farm、operation ID、业务 payload/version/SHA-256、依赖、创建时间、状态、尝试数、下次允许时间和确认回执。相同作用域/operation ID 的重复入队返回原命令；输入或依赖不一致拒绝。payload 保留实际业务代码和原始时间，无凭证、翻译文本或 UI 状态；摘要用于损坏检查，不作为服务端安全或去重证明。

原子 claim 在事务中读取同作用域命令、执行 CommandPolicy 门禁，再同时写 IN_FLIGHT、attempt 和事件；两个数据库 owner 不会同时领取同一任务。同 task/pig 组串行，显式依赖成功或取消才解阻塞；失败/冲突/未知结果阻塞后继。

- Success：明确回执，状态和 attempt/event 在同一事务变为 SYNCED。
- Rejected：明确拒绝/冲突，保存 FAILED/CONFLICTED 与结构化错误码，不自动重试。
- NotSent：transport 明确保证尚未发送；按已有延迟规则回 QUEUED。不能把普通超时当作 NotSent。
- Uncertain：异常、取消或发送结果不明，保持 UNCERTAIN。

recoverInterrupted 显式将该作用域残留 IN_FLIGHT 转为 UNCERTAIN，并完成对应 attempt/event；只应在确认旧执行者已经停止的新进程恢复时调用，不能拿它取消仍活跃的 worker。未知结果不能 claim。确认未执行/支持具体去重后才允许 retryAfterConfirmation；真实去重协议未明确前不自动调用。确认成功/拒绝可以通过 finish 解决同次 UNCERTAIN，尝试编号检查阻止旧回执覆盖新的尝试。

EstrusDispatcher 由调用方显式驱动，一次 drainOne 在 IO dispatcher 上只执行一条；认证/网络与上下文检查发生在领取和发送前。发送后切换账号/厂区，已确认回执仍保存到原命令作用域，不写新作用域；异常取消记未知再传播取消。调用者必须从已授权账户和 ScopeGate 获取作用域，不能相信页面传入的任意用户/厂区字符串。cancel 只取消尚未确认成功的本地命令，不撤销服务端业务。没有常驻 worker、真实 HTTP/mutation transport 或生产自动重放。模拟 transport 仅在测试代码中存在。

未实施正式 IndexedDB 切换、生产写入、UI 选择器、同步页面或发布。旧 Web 数据保留；后续先明确服务端去重/结果查询，再逐操作接真实 transport。


## 验证与限制

最终 67 JVM、30 常规 Android 测试通过；常规运行另有 3 个 opt-in 测试跳过，不能计为通过。FoundationProcessRestoreTest 显式分 prepare/restore 两次运行，中间 adb force-stop，断言 PID 变化；持久化英文设置和未确认命令可恢复，恢复时自动发送为 0。结果及日志在 verification/language-offline。

本轮 GraphQL 请求只对 MockWebServer 执行，队列发送只对模拟 transport 执行；真实 dev 的读链路证据沿用前一阶段，本轮未重新登录或提交 CheckEstrus。设备测试为 API 36 模拟器，其他系统未执行。没有语言选择 UI、正式页面/样式变更或真实业务自动重放。真实写入接入仍需服务端持久化去重/结果确认机制，不能通过错误文案推断成功。
