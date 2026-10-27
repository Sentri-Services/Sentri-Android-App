# 生产辅助读取覆盖核对

整体目标进行中；本批补第 8 项辅助读取，不能代表第 8 项或全业务完成。

- NativeProductionBatches 对应旧 services/batch.ts 列表/详情，保存批次号/状态、产线 ID/名称/状态及任务计划；单 ID 查询复用相同操作。
- active 保持旧 shared/referenceHooks.ts 的 PLANNING/STARTED 和 LINE 默认批次排除；unfinished 与旧 BatchSelectPopup.tsx 一样排除 COMPLETED，保留 SKIPPED。默认批次仍可按 ID 读取，不篡改真实身份。
- NativeProductionRecheckPigs 对应旧 services/task/reEstrusQuery.ts 的独立视图；另保留任务/批次/厂区归属、检查结果、配种/提交时间和操作者。该旧声明没有发现业务调用方，因此不能据此宣称旧页面实时调用已验证。
- 两操作均强制厂区关联过滤并核对响应身份。分页以 limit+1 判断 hasMore，无虚构总数或额外 aggregate 权限假设。
- 缓存有独立 format-1 schema、完整请求 key、四维归属和 256 条共用读取容量；null 详情与未读取区分，仅连接/AuthUnavailable 回退，拒绝损坏/错域数据。
- 精确时间、false/null、双语名称和行号字符串 01 保留；七个批次/产线状态新增中英文映射。
- verify.sh、117 JVM、81 常规 Android 通过，9 opt-in 默认跳过。辅助恢复测试另显式 prepare/force-stop/restore 各一项通过：不同 PID、四快照、远端请求 0、缺失详情和作用域隔离通过。
- 本批无真实业务请求、mutation、后台重放、UI、版本或发布变更；参考仓库保持干净。
- 生产页面所需完整猪只健康/历史/位置字段继续由任务 9 统一读取提供，再回归任务 8 组装；精液关联依赖任务 16。JoinBatch 实际也用于旧配种入口与巡检，需在对应命令适配中登记，不能由批次只读模型替代。
