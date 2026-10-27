# 中英文、GraphQL 契约与 CheckEstrus 离线持久化

Status: complete

2026-10-09，来源为当前持续目标：完成中英文基础能力；完善 GraphQL 契约与变更检查，补齐一个生产任务切片；完成该切片离线持久化和恢复，以模拟传输验证。明确不做 UI，真实自动重放等待服务端协议。

## 验收

- 持久化 SYSTEM/CHINESE/ENGLISH；跟随系统可重新解析；统一请求语言、业务枚举、数量/日期/名称格式化；账户的服务端文本按语言隔离，稳定代码及双语数据复用。
- 当前 operations 通过 schema 校验；已消费字段、输入、默认值、枚举与 operation 变更可以检测；候选 schema 的无关扩展不阻止构建。生成 CheckEstrus 查询/输入，保留实际时间与错误契约。
- SQLite 持久化 CheckEstrus payload、归属、依赖、状态、尝试和回执；发送前原子 claim，明确失败、冲突、不发送、未知结果分开；进程恢复保持结果未知（UNCERTAIN），不自动重放。
- 模拟传输、数据库重开、归属、重复入队、迟到回执、事务回滚、认证/网络门禁有行为测试。
- 执行 verify.sh 与适用 Android 数据层测试；不改页面、设计、旧参考工程、版本、签名或发布模型，不发布。

进度与证据见 docs/language-offline.md、verification/language-offline。本阶段不增加语言选择页面或同步页面，也不接真实 CheckEstrus mutation 发送器。


## 完成证据

67 JVM 测试通过；30 常规 Android 测试通过，3 个 opt-in 测试默认跳过（2 个历史真实后端、1 个新进程测试）。新进程测试 prepare/restore 各显式执行一项通过：强制停止后 PID 不同、英文设置恢复、IN_FLIGHT→UNCERTAIN、自动发送次数为 0、确认回执可完成原尝试。GraphQL 检查覆盖 9 operations/115 消费字段，8 个变化场景；中英文 127 资源条目/41 枚举映射检查通过。verify.sh、三环境 Debug 构建通过。

逐项核对见 verification/language-offline/completion-audit.md，计数及原始输出见 results.json。本阶段 UI 源码未修改，旧参考工作区干净，版本/发布流程未修改，没有真实业务提交、自动重放、推送或发布。
