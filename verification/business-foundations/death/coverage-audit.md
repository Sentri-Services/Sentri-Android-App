# 死亡上报与附件持久化覆盖核对

第 9 项进行中；本批完成死亡接口与离线子命令基础，完整猪只读取及本地预览仍待实现。全业务目标仍未完成。

- 旧入口核对：mobile_webapp/src/services/pig/mutations.ts、services/resources.ts、lib/offline/deathReportCommandHandler.ts、deathReportCommandAdapter.ts、db/task/production/deathReport.ts。原生以已存 SDL 为公开契约；后端 media.py 的随机文件 key 不能证明客户端 header 幂等。
- NativeUploadPigDeathImage / NativeReportPigDeath 通过 Apollo 生成与 GraphQL 契约校验。MockWebServer 验证 data URL、null/空字符串/无身份猪、母猪引用、位置、图片顺序、本地 taskId 不进入 wire、服务端拒绝、非法成功回执及 mutation 认证失败不重放。
- 两输入实际登记 format-2 codec；SQLite schema=2 未变。enqueueDeath 以一事务创建图片子命令和报告，重复调用稳定，原草稿变化整事务回滚；每猪上传串行，报告显式依赖全部图片。跨报告/作用域/位置引用拒绝，取消图片不放行报告，取消报告原子取消待执行子步骤。
- 上传成功回执严格保存 file_key/file_url，未知上传不能自动重发或取消。上报重试只复用已确认 URL。大 payload 查询绕开单行 CursorWindow，2700022 字节图片重开完整恢复通过。
- verify.sh、125 JVM、91 常规 Android 通过；11 opt-in 默认跳过，不计为已执行。deathStage prepare/force-stop/restore 分别显式通过一项，PID 30774→30811。恢复三命令，其中一张图片成功、一张图片未决；未确认与已确认图片重发均为 0，手动确认后两次模拟上报使用相同 URL 和顺序，跨作用域隔离通过。
- 当前 23 种队列命令、45 operations/372 消费字段、234 文案/148 枚举映射。此数字包含前批，不是新完成的业务数，也不是全部旧接口覆盖率。
- 明确剩余：第 9 项完整猪只查询/模型/缓存及属性/角色/死亡本地预览；第 8 项关联组装、元数据预览与读取反映确认；第 10–18 项业务适配与汇总审计。
- gateway/transport 未注册 AppContainer，无真实业务写入或后台重放。八 UI 文件与既有哈希一致，版本/发布文件无差异，两个参考仓库干净。未推送、发布或变更设计规范。
