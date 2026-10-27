# 分娩、统一寄养、仔猪处理基础验收

2026-10-09。当前整体目标仍进行中。本批完成任务表第 5/6 项的基础实现；后续模块、完整列表、本地投影和跨模块引用未完成。

| 要求 | 当前证据 |
| --- | --- |
| GraphQL/输入 | FarrowingPiglet.graphql 的两查询、五当前 mutation、两历史阶段 mutation；FarrowingPigletModels 独立输入，CareContractTest 验证七 mutation 的实际请求/响应线协议 |
| 读取模型 | GraphqlCareReads 校验 farm/task/pig 及嵌套母猪归属；保留状态、身份、分娩统计/记录、处理项目、时间、数量、Decimal 和 JSON；拒绝未知枚举、重复身份和负数量 |
| 实际离线注册 | CommandPayloadCodec 委派 CareCommandPayloadCodec，新增五类型；SQLite 实际入队/重开/同 operation ID 校验，不仅是实现接口 |
| 精度/空值 | 高精度重量保持 BigDecimal precision/scale，1E+3 重复入队不改变输入身份；显式 null/空列表/0 区分；请求按后端 FloatString 字符串输入，身份空列表仅在发送时按旧 adapter 转 null |
| 寄养顺序 | 来源和接收母猪两个 pig 组；未知结果阻塞两者，其他猪独立领取；改变 stage/接收母猪不能重用 operation ID；旧 handler 统一 FosterPiglet、后端任务类型分发 |
| 持久化快照 | AppContainer.farrowing/piglet 接入 ProductionTaskReads 和 codec；设备测试写入 SQLite→重开→网络失败回退比较完整 DTO；作用域不同拒绝借用 |
| 新进程恢复 | CareProcessRestoreTest 显式 prepare/强制停止/restore，各一测试通过，PID 不同，五命令/两快照/Decimal 恢复，未知寄养不自动重发，明确结果后解除阻塞 |
| 多语言 | 五操作、三状态、六分类、八处理项目，163 资源/77 映射；静态检查与既有全映射设备语言切换测试通过；断脐带沿用旧文案 |
| 验证 | verify.sh 成功；84 JVM、51 常规 Android 通过，5 opt-in 默认跳过；原始日志、JUnit 和逐 suite 计数已保存 |
| UI/发布/参考工程 | boundaries.json 的 8 文件哈希一致；版本/发布配置与脚本无 diff；两个旧工程保持干净；无 push、发布、真实业务 mutation |

所有新增 GraphQL 验证使用 MockWebServer，队列发送使用模拟 transport。本批没有注册真实发送器或后台重放；客户端携带幂等 header 不等于服务端去重已验证。后续完整列表、待同步本地投影、业务联动与真实结果确认仍必须补齐。
