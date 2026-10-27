# 猪只属性与角色覆盖核对

第 9 项进行中，本批只完成两种命令适配，不代表死亡与完整读取完成。

- NativeUpdatePigProperty 对应旧 services/pig/mutations.ts 与 transfer/inspection 两类实际入口。保留 lifecycle、sex、teats、remark、weight 的 null/空字符串/零，重量请求按 FloatString，离线保存 BigDecimal 字符串及 scale。
- NativeUpdateBreederRole 覆盖四动作、原标记时间、猪只列表顺序与重复项、身份/性别/父母 ID。与旧后端 command/common/shared 定义一致，未套旧本地更新的角色猜测。
- 两输入登记 format-2 显式 codec，schema-2 队列无格式含义变更；按被修改的猪分组，父母 ID 只为引用。共享既有生产同猪顺序；未知/拒绝结果阻塞同猪，其他猪/作用域独立。
- 多语言新增两操作、四角色动作，沿用已有五生命周期代码映射。
- verify.sh、120 JVM、85 常规 Android 通过，10 opt-in 默认跳过；显式 pigManagementStage prepare/force-stop/restore 各一项通过，三命令新 PID 恢复，未知自动重发 0、多猪角色等待、另一猪模拟成功、原操作确认后角色可领取。
- 原生死亡上报/图片上传、持久化子步骤回执与完整猪只读取尚未实现。本批没有通过底层队列或现有基础 PigDetail 冒充这些范围。
- 仍没有真实 mutation transport、自动后台发送、真实业务写入或发布；UI 八文件与前阶段哈希一致，两个参考仓库干净。
