# Tenant Diff

[![CI](https://github.com/shiyongyin/diff/actions/workflows/ci.yml/badge.svg)](https://github.com/shiyongyin/diff/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Tenant Diff 是一个面向多租户场景的数据差异对比与选择性同步组件，提供 **Compare / Apply / Rollback / SPI 扩展 / Standalone REST API** 等能力。

Tenant Diff is a toolkit for comparing and selectively synchronizing business data across tenants, with rollback support and a standalone runtime.

当前仓库包含可运行示例、基础测试和最小运行时装配，适合用于：

- 标准租户 → 客户租户的配置差异对比
- 业务对象级别的选择性同步
- 同步前预览、同步后审计、失败后回滚
- 基于插件机制扩展新的业务类型

## 为什么是它

- 支持按 `business -> table -> record -> field` 输出结构化差异
- 支持选择性 Apply，而不是只能整批覆盖
- 支持对成功 Apply 生成快照并执行 Rollback
- 提供 `core / standalone / demo` 分层结构，便于嵌入或独立运行
- 提供最小可运行 Demo，便于本地验证和二次开发

## 当前状态

项目正在进行开源化完善，当前可视为 **0.x 阶段**：

- 已具备最小可运行 Demo 与基础测试
- 已形成核心领域模型、对比引擎与 Standalone Runtime
- Public API / SPI、模块边界与发布治理仍在持续收敛
- `0.x` 期间允许发生不兼容调整，请勿将当前实现直接视为长期稳定 API

## 模块说明

- `tenant-diff-core`：纯核心模块，包含领域模型、对比引擎与 SPI 合同
- `tenant-diff-standalone`：Standalone Runtime，包含自动配置、持久化、REST API 与运行时装配
- `tenant-diff-demo`：最小可运行示例，基于 H2 内存库和 `EXAMPLE_PRODUCT` 业务插件演示完整流程
- `docs/`：产品、设计、运维与机制说明文档

> 说明：仓库已完成 `core / standalone / demo` 第一轮拆分；后续仍会继续收敛 `standalone` 内部的自动配置、Web 与运行时边界。

## 仓库导航

- 文档入口：`docs/README.md`
- 产品说明：`docs/prd.md`
- 设计说明：`docs/design-doc.md`
- 运维说明：`docs/ops-guide.md`
- 演示脚本：`scripts/demo/README.md`

## 核心能力

- **多层级差异对比**：按 `business -> table -> record -> field` 输出结构化差异
- **选择性 Apply**：支持按计划执行 `INSERT / UPDATE / DELETE`
- **快照回滚**：对成功 Apply 生成目标侧快照并支持恢复
- **扩展式插件模型**：通过业务插件定义数据装载、业务键、Schema 与写入支持
- **Standalone 模式**：通过 Spring Boot 自动装配暴露 REST API，便于独立运行或嵌入业务系统

## 快速开始

### 环境要求

- JDK `17+`
- macOS / Linux / Windows（使用 Maven Wrapper）

### 1. 验证构建与测试

```bash
./mvnw test
```

### 2. 启动 Demo

```bash
./mvnw -pl tenant-diff-demo -am package -DskipTests
java -jar tenant-diff-demo/target/tenant-diff-demo-0.0.1-SNAPSHOT.jar
```

默认配置：

- 服务端口：`8080`
- 内存数据库：H2
- H2 Console：`http://localhost:8080/h2-console`
- JDBC URL：`jdbc:h2:mem:tenant_diff`

### 3. 创建一次差异会话

Demo 默认注册了 `EXAMPLE_PRODUCT` 示例业务类型，并预置了租户 `1` 与租户 `2` 的差异数据。也可以直接使用 `scripts/demo/` 下的脚本完成完整演示流程，脚本说明见 `scripts/demo/README.md`，更多背景见 `docs/prd.md`。

```bash
curl -X POST 'http://localhost:8080/api/tenantDiff/standalone/session/create' \
  -H 'Content-Type: application/json' \
  -d '{
    "sourceTenantId": 1,
    "targetTenantId": 2,
    "scope": {
      "businessTypes": ["EXAMPLE_PRODUCT"]
    }
  }'
```

### 4. 查询会话结果

```bash
curl 'http://localhost:8080/api/tenantDiff/standalone/session/get?sessionId=1'
```

```bash
curl 'http://localhost:8080/api/tenantDiff/standalone/session/listBusiness?sessionId=1&pageNo=1&pageSize=20'
```

```bash
curl 'http://localhost:8080/api/tenantDiff/standalone/session/getBusinessDetail?sessionId=1&businessType=EXAMPLE_PRODUCT&businessKey=PROD-002'
```

更多接口说明、架构信息和运维细节见 [docs/README.md](docs/README.md)。

## 开发约定

- 默认使用 `./mvnw test` 作为最小回归命令
- 修改行为时请同步更新文档与测试
- 保持变更聚焦，优先修复根因而非表层现象
- 在公开对外之前，请确认 `LICENSE`、版本策略和发布说明符合维护者最终决策

更多参与方式见 `CONTRIBUTING.md`。

## 路线图

近期优先事项：

1. 补齐仓库治理文件、CI、Issue / PR 模板
2. 收敛模块边界，拆分纯核心与运行时能力
3. 清理历史文档漂移并重建对外文档入口
4. 完善集成测试、异常路径测试与发布治理

## 许可证

本仓库当前默认采用 `MIT` 许可证，详见 `LICENSE`。
