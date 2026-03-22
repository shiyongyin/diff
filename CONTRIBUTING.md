# Contributing

感谢你关注 `Tenant Diff`。

本项目当前处于 `0.x` 开源化完善阶段，欢迎通过 Issue、文档修正、测试补充、功能改进和插件示例等方式参与贡献。

## 贡献方式

你可以通过以下方式参与：

- 报告缺陷或文档问题
- 提交功能建议或架构优化建议
- 补充测试、示例和运维文档
- 扩展新的业务插件与 Apply 支持

## 开始之前

在投入较大开发工作前，建议先创建 Issue 讨论以下内容：

- 需求边界是否清晰
- 是否与既有路线图冲突
- 是否涉及破坏性 API / SPI 变更
- 是否需要同步更新文档和 Demo

## 本地环境

- JDK `17+`
- 使用仓库自带 Maven Wrapper

常用命令：

```bash
./mvnw test
./mvnw -pl tenant-diff-demo -am package -DskipTests
java -jar tenant-diff-demo/target/tenant-diff-demo-0.0.1-SNAPSHOT.jar
```

## 提交要求

请尽量保证每次变更具备以下特征：

- 变更范围聚焦，不顺手修改无关代码
- 新功能或行为变化附带测试
- 文档、示例、接口说明与代码保持一致
- 如涉及破坏性变更，在 PR 描述中明确说明影响面
- Git 提交说明默认使用中文描述，便于仓库历史统一检索与团队协作
- 建议使用简洁中文前缀，如 `修复：`、`测试：`、`文档：`、`重构：`、`构建：`

## Pull Request 清单

提交 PR 前，请自查：

- [ ] 已说明变更目的与动机
- [ ] 已执行 `./mvnw test`
- [ ] 已补充或更新相关测试
- [ ] 已更新 `README` / `docs` / 示例（如适用）
- [ ] 已说明兼容性影响与迁移方式（如适用）

## 代码风格

- 以现有代码风格为准，优先保持一致性
- 避免不必要的复杂抽象
- 优先修复根因，而不是做表层补丁
- 对外 API / SPI 变更请补充文档说明

## Breaking Change 规范

当变更涉及以下内容时，必须在 PR 描述和 CHANGELOG 中标注 `BREAKING CHANGE:`：

- **SPI 接口变更**: `StandaloneBusinessTypePlugin`、`BusinessApplySupport` 等扩展点的签名变更
- **Domain 模型变更**: `ApplyPlan`、`BusinessDiff`、`SessionStatus` 等对外模型的结构变更
- **数据库 Schema 变更**: `xai_tenant_diff_*` 表结构变更
- **REST API 变更**: 端点路径、请求/响应结构变更
- **配置属性变更**: `tenant-diff.*` 配置项的重命名或移除

### 标注格式

在 commit message 中:
```
重构：调整 Apply 计划构建器

BREAKING CHANGE: PlanBuilder.build() 签名变更，移除了 deprecated 的 options 参数
```

普通提交示例:
```text
修复：加固 apply 与 rollback 边界场景
测试：新增 MySQL 端到端覆盖
文档：优化 GitHub 首页 README 展示
```

在 CHANGELOG.md 中:
```markdown
### Breaking Changes

- **SPI**: `StandaloneBusinessTypePlugin.schema()` 返回类型从 `Map` 改为 `BusinessSchema` (#123)
```

## 安全问题

请不要通过公开 Issue 披露安全漏洞。

安全问题请先阅读 `SECURITY.md`，按其中流程私下报告。
