# 首批覆盖核对（整体进行中）

2026-10-09。依据 results.json 与当前代码。全业务任务没有完成，不进行 overall completion 标记。

| 核对项 | 当前证据／边界 |
| --- | --- |
| 任务表 | .scratch/business-foundations/spec.md 列出全部业务，未实现项明确待做 |
| 查情 | 原 CheckEstrus 加 SubmitEstrusStatus；两个业务输入、生成 operation、版本化 payload、提交自动依赖 |
| 配种 | Boar/Semen 两种输入与 mutation；任务/汇总/记录/材料网络读取，归属校验；两种服务端 ID payload 可持久化 |
| SQLite 升级 | schema 1→2 追加显式依赖列，原 payload、命令、attempt/event 保留；设备 legacy fixture 验证 |
| 结果未知与阻塞 | 查情 UNCERTAIN 阻塞提交及跨任务同猪配种；明确回执解除，冲突/拒绝阻塞后继；模拟 transport |
| 进程恢复 | prepare、force-stop、restore；不同 PID，混合 Check/Submit payload 与依赖恢复，未确认命令自动重发为 0 |
| 中英文 | 查情/配种四操作文案加在资源及映射 API；语言持久化框架沿用前阶段；没有语言页面 |
| 验证 | verify.sh 通过，72 JVM / 35 常规 Android，3 opt-in 默认跳过；本批进程恢复另执行两阶段各一项 |
| 契约 | 13 operations / 145 消费字段；8 契约变化场景通过；只读本地 SDL，接口使用 MockWebServer |
| UI／发布／参考工程 | 8 个 UI/样式文件哈希与前阶段相同；版本、发布命令/workflow、部署契约无 diff；两个参考工作树干净；未推送或发布 |
| 未完成 | 复情及其余业务，任务读取离线快照、完整关联列表、精液本地引用映射与全业务覆盖审计 |
| 真实写入 | 未执行，未注册真实 mutation transport/worker；不能将模拟成功视为线上幂等/同步已完成 |

首次单独 instrument 因 Gradle 已卸载测试包而无法找到 runner；重装本地 Debug/测试 APK 后 prepare/restore 均通过。原始 setup failure 与成功输出分别保留，未将 setup failure 记成业务测试通过。
