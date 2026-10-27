# 第 10 项医嘱与治疗：写入基础与后续读取

2026-10-10。参考 Sentri HEAD 4c1a2ff49e8d938e1af053d844018cc140c63f38。旧服务、离线 adapter/handler 与后端规则及哈希见 verification/business-foundations/therapy/source-rules.json。

## 已实现代码范围

六个实际操作的原生 operation、独立输入、format-2 codec 和 SQLite 登记：CompleteTherapyTarget、SkipTherapyTask、AddPrescriptionTarget、RemovePrescriptionTarget、TerminateTherapy、TerminatePrescription。执行/跳过传 scheduled task ID、有序目标和完成原始时间/原因；prescriptionId/therapyId 是本地协调上下文，不混入执行/跳过请求，也不冒充生产任务 taskId。空列表、重复选择、null/空字符串及症状/疾病列表保留。

同处方治疗执行会修改处方聚合，增删目标会修改全部进行中的治疗任务，终止单个治疗也可能终止处方。因此所有处方写入共享 prescription 分组，并在入队事务依赖已有未决处方操作，设备时间回拨仍保持已明确的依赖顺序。已列目标参与 pig 分组，与生产/角色/死亡同猪互相等待。不同处方、猪和四维作用域保持独立。

SqliteCommandQueue.enqueueTherapySteps 原子入队整个步骤列表，每步一个 operationId、一份输入和独立尝试/回执。长度前缀命名避免批次字符串边界碰撞；复用批次必须保持数量、顺序、全部输入及调用方依赖一致，数据库拒绝中间插入时整批回滚。每次领取仅一个远程操作，已成功步骤不因后续拒绝/未知而重放。没有后台真实发送。

TherapyWriteReceipt 严格 format-1 保存 operation/recordId，拒绝缺字段、多字段、重复键、尾随数据及类型转换。执行/跳过绑定 task ID，其余绑定 prescription ID；错误回执不能将队列尝试变成成功。GraphqlTherapyGateway / TherapyTransport 仅显式构造用于 MockWebServer 和模拟 dispatcher，不注册到 AppContainer。

CreatePrescription 是旧服务中无实际入口调用的声明，已保留原生 operation 与独立创建输入（精确 Decimal、治疗列表、目标、症状/疾病、adhoc）。没有创建离线队列或伪造新建引用映射：后端此操作 recordId 是随机 serial_number，实际 prescription ID 由生成器另行决定，不能把 serial 当作处方 ID。

## 第 10 项读取进度与剩余

1. 完整治疗列表/详情与药物、药物规格、治疗方式参考数据的原生读取、独立模型和五类缓存已接入；读取新增 farm_id/prescription_id/therapy_id 归属验证，包含旧 prescription.ts 字段并补齐详情中的乳头数量。待真实后端读取验收。
2. 首页/离线预取的 active-status OR executed_at 指定日窗口已与完整列表、按 prescriptionId 读取分开；缓存 key 包含模式、时间、过滤、排序和分页，结果保留来源、获取时间与刷新失败原因。真实后端读验收仍待做。
3. 处方上下文（目标和治疗完整成员及总数）与全部治疗分页组装已接入；六操作本地预览按后端逐目标下一轮、全处方增删目标及终止规则实现，使用私有副本原子计算。服务端生成的时间、操作者与 ID 保持未知，预览行以命令 ID 标识。已发送、未知、失败、依赖未决和待刷新分别标记，不改写确认快照。自动成功读取反映证据仍未实现；`reflectedCommandIds` 仅表示调用方显式提供证据。仅靠物化治疗行不能发现无目标却仍活动的任务，这类场景的服务端结果仍待确认；本地预览不能充当权威状态。旧前端等待本轮全部目标结束后再建下一轮的算法不能照搬。
4. 创建声明的实际产品入口及离线创建引用语义未获新需求；不自行扩大为新建功能。删除/手动调度仅后端有定义，旧 Web 无实际调用，最终覆盖审计需记录范围。

本批设备恢复测试按 operation/步骤身份查找，不依赖同毫秒创建的数据库列表排序。最终验收见 verification/business-foundations/therapy/results.json：164 JVM、118 常规 Android 通过，15 opt-in 默认跳过；显式 prepare/force-stop/restore 各一项通过，PID 4960→4996，三步骤恢复，未知重发 0，明确确认后仅模拟执行终止步骤。第 8/9 项剩余以及第 11–18 项继续执行。

2026-10-18 读取批验证见 [therapy-reads/results.json](../../verification/business-foundations/therapy-reads/results.json)：`verify.sh`、173 JVM、124 常规 Android 通过，16 opt-in 默认跳过；读取缓存的 prepare/force-stop/restore 显式通过，PID 5231→5271、六份快照、恢复时远端读取 0。四查询采用一个治疗行操作复用到分页与按 farm+ID 详情，参考数据各自独立查询。该批无真实后端联调。

2026-10-18 预览批见 [therapy-preview/results.json](../../verification/business-foundations/therapy-preview/results.json)：新增处方上下文查询与第六类读取快照，工作区按处方治疗成员精确分页，六操作本地预览及双语状态映射。179 JVM、126 常规 Android 测试通过，16 opt-in 默认跳过；显式 prepare/force-stop/restore 在不同 PID 6169→6208 恢复八快照和一排队命令，远端读取 0。GraphQL 契约 58 operations/518 字段。仍是 MockWebServer/SQLite 验证，无真实后端联调、业务 UI 或发布。
