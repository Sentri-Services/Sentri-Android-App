# Sentri 原生 Android

本项目以 Kotlin + Compose 重构现有 UI 与架构。业务规则与后端契约沿用参考项目。当前阶段是工程基础、GraphQL 与 Logto 登录接入，正式设计规范尚未提供。

## 开始工作

- 先看 [README.md](README.md) 的命令和目录；认证或接口改动再读 [接入说明](docs/integration.md)。
- 技术决定见 [ADR](docs/adr/0001-foundation.md)，当前结果和剩余项见 [进度](docs/status.md)。
- `../Sentri-Android` 和 `../Sentri/mobile_webapp` 只读参考。不要把旧 Web 的样式/代码约定直接套给新原生工程。
- 使用标准 Compose 组件验证接入，等待产品给出设计规范后再建立设计系统。

## 实现边界

- `auth/` 拥有会话、PKCE、持久化与刷新；`network/` 拥有 GraphQL 和后端模型转换；`ui/` 消费状态与接口。构造函数注入依赖。
- GraphQL operations 放在 `app/src/main/graphql/`；生成文件在 `app/build/`，禁止手工维护。
- 当前后端从资源 JWT 获得厂区上下文，不自行添加 Hasura 管理员、角色或厂区头。
- mutation 不自动重放；认证刷新使用互斥和已拒绝 Token 比较；网络错误不能清空仍可恢复的会话。
- 用户文案使用资源。日志只记录无凭证的事件与错误类别；不记录 Token、授权码、密码或完整认证 URL。
- 不复制账号、Token、私钥或旧签名配置进仓库；本地覆盖配置见示例，实际文件忽略提交。
- 常规可逆实现自主完成。业务语义、生产身份及外部认证配置变更先提出具体方案。当前不推送、不发布。

## 完成标准

- 执行 `scripts/verify.sh`；涉及 Android 会话存储、WebView 或 UI 时，执行适用设备测试与运行验证。
- 如本机全局 Gradle 代理不可用，用 `SENTRI_GRADLE_DIRECT=1` 运行脚本，勿修改全局配置。
- 真实登录和后端接入与 Fake 测试分别报告；缺失、未执行或失败不能记为通过。
- 更新与本次改动相关的说明和进度；避免重复文档与无依据的完整性声明。

## Agent skills

完整上游集合安装在 `.agents/skills/`，来源、版本和资源哈希见 `.agents/skills-lock.json`。按需使用，不强制跑规划流程；不自动启动长期循环、远端发布或多代理实现。

### Issue tracker

本地 Markdown 是权威来源，位于纳入 Git 的 `.scratch/`；见 [任务跟踪](docs/agents/issue-tracker.md)。

### Triage labels

保留上游五个默认角色；见 [状态映射](docs/agents/triage-labels.md)。初始化已获授权的选择无需重复采访。

### Domain docs

单一上下文，按需维护根目录术语表与 `docs/adr/`；见 [领域文档](docs/agents/domain.md)。

Android 验证和上游使用适配见 [Skills 说明](docs/agents/skills.md)。不要修改 vendor 文件来写项目规则。
