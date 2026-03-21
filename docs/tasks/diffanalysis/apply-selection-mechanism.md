# Apply 勾选机制 — 功能设计文档

> **版本**: v1.0 | 2026-03-12
> **Skill**: Feature Design Skill v1.1 (M1→M5)
> **复杂度**: B 级（新增对外 API 语义 + DDL 无变更 + 业务约束 + 排障需求）

---

## M1 需求对齐

### 结构化需求摘要

**核心问题**：Apply 流程在 preview → execute 之间存在粒度断层。preview 仅返回 businessType 维度的聚合统计，execute 仅支持 businessType/businessKey/diffType 粗粒度过滤，用户无法选择具体记录。

### In-scope（V1）

1. preview 返回 action 级明细列表，每条带唯一 `actionId`
2. execute 支持 `selectionMode=PARTIAL`，仅执行用户勾选的 `selectedActionIds`
3. `previewToken` 防陈旧机制（preview 与 execute 之间 diff 数据一致性校验）
4. V1 仅支持主表（`dependencyLevel=0`）选择，子表自动跟随
5. `previewActionLimit` 防止 preview 响应体积过大
6. 向后兼容：不传 `selectionMode` 等同 `ALL`（全量执行）

### Out-of-scope（V2/V3）

1. 子表 PARTIAL 选择（dependencyLevel>0）— V2，需解决 IdMapping miss 安全策略
2. `clientRequestId` 服务端幂等（DDL + selectOne）— V2
3. 持久化 decision 到 DB（PATCH 接口）— V3
4. AUTO_INCLUDE 自动补齐依赖闭包 — V3
5. Preview 分页 — V3

### 关键不变量

1. **确定性**：同一份 diff 数据，多次 preview/execute 计算的 `actionId` 必须完全一致
2. **AND 语义**：`selectedActionIds` 与 `businessKeys`/`businessTypes`/`diffTypes` 叠加（AND），不替代
3. **安全底线**：`selectionMode=PARTIAL` 时，`selectedActionIds` 中每条 action 必须是 `dependencyLevel=0`
4. **无状态**：selection 仅作为 execute 请求参数传入，不持久化到 DB

### 最关键的失败点

1. **数据陈旧**：preview 后 diff 数据变化，execute 时 `previewToken` 不匹配 → 用户操作的是过期数据
2. **IdMapping 静默污染**：若允许 PARTIAL 选择子表但跳过父表，子表 INSERT 会写入源租户原始外键值
3. **preview 体积爆炸**：大量 action 导致响应体过大，影响前端性能

---

## M2 代码库扫描

### 1. 命名/路径冲突检查

| 检查项 | 结果 | 说明 |
|--------|------|------|
| `actionId` 字段 | ✅ 无冲突 | `ApplyAction.java` 中无同名字段 |
| `SelectionMode` 类名 | ✅ 无冲突 | 项目中无同名枚举 |
| `previewToken` 字段 | ✅ 无冲突 | 无同名字段 |
| `selectedActionIds` 字段 | ✅ 无冲突 | `ApplyOptions` 中无同名字段 |
| API path | ✅ 无冲突 | 不新增接口，仅扩展现有 `/apply/preview` 和 `/apply/execute` |

### 2. 相似能力与复用点

| 现有组件 | 复用策略 |
|----------|---------|
| `PlanBuilder` | **扩展**：在 `build()` 中增加 actionId 计算 + selection 校验 + 过滤逻辑 |
| `ApplyOptions` | **扩展**：增加 `selectionMode`/`selectedActionIds`/`previewToken`/`clientRequestId` 字段 |
| `ApplyAction` | **扩展**：增加 `actionId` 字段 + `computeActionId()` 静态方法 |
| `ApplyPreviewResponse` | **扩展**：增加 `previewToken`、`ActionPreviewItem`、`actions`；修改 `from()` 签名 |
| `ErrorCode` | **扩展**：在 Apply（2xxx）段追加 4 个错误码 |
| `TenantDiffStandaloneApplyServiceImpl` | **扩展**：`preview()` 增加 token 计算 + limit 校验；`execute()` 增加审计日志 |

### 3. 影响面

| 影响范围 | 文件 | 变更类型 |
|---------|------|---------|
| core 模块 | `ApplyAction.java` | 修改（增加字段 + 方法） |
| core 模块 | `SelectionMode.java` | **新增** |
| core 模块 | `ApplyOptions.java` | 修改（增加 4 字段） |
| core 模块 | `ErrorCode.java` | 修改（增加 4 错误码） |
| core 模块 | `PlanBuilder.java` | 修改（核心逻辑变更） |
| standalone 模块 | `ApplyPreviewResponse.java` | 修改（增加内部类 + 字段） |
| standalone 模块 | `TenantDiffStandaloneApplyServiceImpl.java` | 修改（编排逻辑） |
| core 测试 | `PlanBuilderTest.java` | 修改（新增 selection 测试） |
| standalone 测试 | `ApplyPreviewResponseTest.java` | **新增** |

**不改动**：Controller 类（options 变更自动透传）、`ApplyExecuteRequest`（`options` 类型为 `ApplyOptions`）、DB schema、ExceptionHandler。

### 4. 项目约定探测

| 维度 | 探测结果 |
|------|---------|
| 技术栈 | Spring Boot + MyBatis-Plus |
| ORM | MyBatis-Plus（Mapper + XML） |
| DTO 风格 | Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` |
| 错误处理 | 统一 `TenantDiffException(ErrorCode)` + ExceptionHandler → HTTP 200 + `ApiResponse.fail()` |
| 主键策略 | 自增 Long（session 等），不涉及本次变更 |
| 审计字段 | 不涉及（无 DB 变更） |
| JSON 序列化 | Jackson，`@JsonSetter(nulls = Nulls.SKIP)` 用于默认值保护 |

### 5. 相似功能检测

| 检测项 | 结果 |
|--------|------|
| token/digest 机制 | ❌ 项目中无类似的 token/digest 防陈旧机制，需新建 `computePreviewToken()` |
| 选择/过滤机制 | ✅ `ApplyOptions` 已有 `businessKeys`/`businessTypes`/`diffTypes` 过滤，新增 `selectedActionIds` 作为更细粒度层 |
| 防陈旧/版本校验 | ❌ 无类似机制，需新建 |
| 批量操作/限流 | ✅ `maxAffectedRows` 已有阈值控制，新增 `previewActionLimit` 类似模式 |

---

## M3 技术方案

### 方案选型（ADR-1）

| 方案 | 思路 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| **A. 无状态 ActionId Selection** | preview 返回带 `actionId` 的 action 列表；execute 传 `selectedActionIds` 过滤 | 简单，不改 DB，不改接口签名 | 勾选不持久化，刷新丢失 | **采用** |
| B. 持久化 Decision | 新增 PATCH 接口修改 `RecordDiff.decision` | 勾选持久化，多人协作友好 | 需新增写接口 + 更新大字段 | V3 |
| C. 多级白名单 | `Map<String, List<String>> recordKeysByBusinessKey` | 利用现有过滤 | 前端构造复杂，跨 businessType 传参臃肿 | 不采用 |

**推荐 A**：最小改动、无状态、向后兼容。代价是刷新丢失勾选状态，V3 可通过持久化 decision 解决。

### API 合约

#### POST /apply/preview — 响应扩展

新增字段：
- `previewToken: String` — 防陈旧令牌
- `actions: List<ActionPreviewItem>` — action 级明细

```json
{
  "sessionId": 1,
  "direction": "A_TO_B",
  "statistics": { "totalActions": 10, "insertCount": 3, "updateCount": 5, "deleteCount": 2 },
  "businessTypePreviews": [...],
  "previewToken": "pt_v1_4f9f7f9e8d3c2a1b0e9f8d7c6b5a4938",
  "actions": [
    {
      "actionId": "v1:EXAMPLE_PRODUCT:PROD-001:xai_product:PROD-001",
      "businessType": "EXAMPLE_PRODUCT",
      "businessKey": "PROD-001",
      "tableName": "xai_product",
      "recordBusinessKey": "PROD-001",
      "diffType": "INSERT",
      "dependencyLevel": 0
    }
  ]
}
```

#### POST /apply/execute — 请求 options 扩展

新增字段：
- `selectionMode: SelectionMode` — `ALL`（默认）/ `PARTIAL`
- `selectedActionIds: Set<String>` — 用户勾选的 actionId 集合
- `previewToken: String` — preview 返回的一致性令牌
- `clientRequestId: String`（可选）— 仅审计日志追踪

```json
{
  "sessionId": 1,
  "direction": "A_TO_B",
  "options": {
    "selectionMode": "PARTIAL",
    "previewToken": "pt_v1_4f9f7f9e8d3c2a1b0e9f8d7c6b5a4938",
    "selectedActionIds": [
      "v1:EXAMPLE_PRODUCT:PROD-001:xai_product:PROD-001",
      "v1:EXAMPLE_PRODUCT:PROD-002:xai_product:PROD-002"
    ]
  }
}
```

#### DTO 字段清单

**ActionPreviewItem**（新增内部类，`ApplyPreviewResponse` 内）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| actionId | String | ✅ | 动作唯一标识 |
| businessType | String | ✅ | 业务类型 |
| businessKey | String | ✅ | 业务主键 |
| tableName | String | ✅ | 表名 |
| recordBusinessKey | String | ✅ | 记录业务主键 |
| diffType | DiffType | ✅ | INSERT/UPDATE/DELETE |
| dependencyLevel | Integer | ✅ | 0=主表，>0=子表 |

**ApplyOptions 新增字段**：

| 字段 | 类型 | 必填 | 默认值 | 校验约束 |
|------|------|------|--------|---------|
| selectionMode | SelectionMode | ❌ | ALL | `@JsonSetter(nulls=SKIP)` |
| selectedActionIds | Set\<String\> | PARTIAL 时必填 | emptySet | 非空 + 每项 v1: 前缀 + 长度≤512 + 总数≤5000 |
| previewToken | String | PARTIAL 时必填 | null | 非空白 |
| clientRequestId | String | ❌ | null | 仅审计 |

### 向后兼容性

| 场景 | 行为 |
|------|------|
| 旧前端不传 `selectionMode` | 默认 `ALL`（`@JsonSetter(nulls=SKIP)` + PlanBuilder null→ALL 双重保护） |
| 旧前端不传 `selectedActionIds` | `ALL` 模式下忽略 |
| `PARTIAL` 且 `selectedActionIds` 为空 | `SELECTION_EMPTY` 拒绝 |
| `PARTIAL` 且不传 `previewToken` | `PARAM_INVALID` 拒绝 |

### 核心逻辑：PlanBuilder 过滤管线

```
diff 遍历
  → businessType 白名单
  → businessKey 白名单
  → diffType 白名单
  → allowDelete 检查
  → actionId 计算
  → selectionMode 语义校验（ALL/PARTIAL）
  → previewToken 防陈旧校验（PARTIAL）
  → selectedActionIds 归一化 + 格式 + 数量 + 存在性校验（PARTIAL，strict）
  → V1 主表限制校验（PARTIAL，dependencyLevel=0 only）
  → selectedActionIds 过滤（PARTIAL）
  → 排序
  → maxAffectedRows 阈值校验（仅 EXECUTE 模式）
  → 生成统计
```

### 失败场景枚举

#### 1. 输入校验失败

| 场景 | 触发条件 | 错误码 | HTTP 状态 | 保障措施 |
|------|---------|--------|-----------|---------|
| selectedActionIds 为空 | `PARTIAL` 且 `selectedActionIds` 为空集合 | `DIFF_E_2010` (SELECTION_EMPTY) | 400 | 前端禁用"执行"按钮当无勾选 |
| actionId 格式非法 | actionId 不以 `v1:` 开头或长度 > 512 | `DIFF_E_2011` (SELECTION_INVALID_ID) | 400 | 前端使用 preview 返回的原始 actionId |
| selectedActionIds 超限 | 数量 > 5000 | `PARAM_INVALID` | 400 | 前端限制勾选数量 |
| previewToken 缺失 | `PARTIAL` 且 `previewToken` 为空/null | `PARAM_INVALID` | 400 | 前端必传 preview 返回的 token |
| actionId 分量为空 | `computeActionId()` 的 4 个分量任一为 null 或空白 | `IllegalArgumentException` → `PARAM_INVALID` | 400 | 数据源保证完整性 |

#### 2. 业务规则违反

| 场景 | 触发条件 | 错误码 | HTTP 状态 | 保障措施 |
|------|---------|--------|-----------|---------|
| 未知 actionId | `selectedActionIds` 包含当前 diff 中不存在的 actionId | `DIFF_E_2011` (SELECTION_INVALID_ID) | 400 | 严格存在性校验，拒绝而非静默忽略 |
| V1 主表限制 | `PARTIAL` 选择了 `dependencyLevel>0` 的动作 | `PARAM_INVALID` | 400 | 前端根据 `dependencyLevel` 禁止勾选子表 |
| maxAffectedRows 超限 | EXECUTE 模式下过滤后 action 数超过阈值 | `APPLY_THRESHOLD_EXCEEDED` | 400 | 前端提示分批执行 |

#### 3. 数据一致性问题

| 场景 | 触发条件 | 错误码 | HTTP 状态 | 保障措施 |
|------|---------|--------|-----------|---------|
| 数据陈旧 | preview 与 execute 之间 diff 数据发生变化（重新 compare） | `DIFF_E_2012` (SELECTION_STALE) | 409 | 前端提示用户重新 preview |

#### 4. 资源耗尽

| 场景 | 触发条件 | 错误码 | HTTP 状态 | 保障措施 |
|------|---------|--------|-----------|---------|
| preview 体积超限 | preview 返回的 action 数超过 `previewActionLimit`（默认 5000） | `DIFF_E_2014` (PREVIEW_TOO_LARGE) | 413 | 前端提示缩小筛选范围；可配置 limit |

### 错误码设计

**原则**：为每个失败场景定义专用错误码，避免使用通用错误码。

**命名规范**：`DIFF_E_{序号}`，在 Apply（2xxx）段追加。

**错误处理矩阵**：

| 场景 | 错误码 | 枚举名 | HTTP 语义 | 用户消息 | 处理策略 |
|------|--------|--------|-----------|---------|---------|
| selectedActionIds 为空 | `DIFF_E_2010` | SELECTION_EMPTY | 400 | "请至少选择一条记录" | 前端禁用按钮 + 后端拒绝 |
| actionId 未知/格式非法 | `DIFF_E_2011` | SELECTION_INVALID_ID | 400 | "选择的记录标识无效，请重新预览" | 前端使用原始 actionId + 后端严格校验 |
| previewToken 不匹配 | `DIFF_E_2012` | SELECTION_STALE | 409 | "数据已变化，请重新预览" | 前端自动触发重新 preview |
| preview 超限 | `DIFF_E_2014` | PREVIEW_TOO_LARGE | 413 | "预览数据量过大，请缩小筛选范围" | 配置 `previewActionLimit` + 前端提示 |

**实现位置**：`ErrorCode.java`，在 `// ── Apply（2xxx）──` 段追加，与现有 ErrorCode 统一处理方式（HTTP 200 + `ApiResponse.fail(errorCode)`），ExceptionHandler 不改动。

### 标识符设计检查清单

#### actionId 设计

```
actionId = "v1:{escape(businessType)}:{escape(businessKey)}:{escape(tableName)}:{escape(recordBusinessKey)}"
escape(x): x.replace("%", "%25").replace(":", "%3A")
```

| 维度 | 检查结果 |
|------|---------|
| **版本前缀** | ✅ `v1:` 前缀，便于未来演进（v2 可能改用不同的生成规则） |
| **转义规则** | ✅ 使用 `:` 作为分隔符，定义了 `escape()` 函数处理 `%` → `%25`、`:` → `%3A` |
| **确定性保证** | ✅ 基于 4 个业务维度拼接，同一份 diff 数据必须产生相同 actionId，无随机值 |
| **唯一性保证** | ✅ 4 个业务维度组合在单 session 内必须唯一 |
| **可读性与调试** | ✅ 包含业务语义（businessType/businessKey/tableName/recordBusinessKey），便于日志排查 |
| **大小写与归一化** | ✅ 不做大小写归一化，不做 trim，保持原始值 |

#### previewToken 设计

```
previewToken = "pt_v1_" + sha256(sessionId|direction|sortedActionId1,sortedActionId2,...).substring(0, 32)
```

| 维度 | 检查结果 |
|------|---------|
| **版本前缀** | ✅ `pt_v1_` 前缀 |
| **确定性** | ✅ 同一份 diff 数据必须产生相同 token |
| **敏感性** | ✅ 增减任意 action 都会改变 token |
| **固定长度** | ✅ 总长 38 字符（`pt_v1_` 6 字符 + 32 hex） |

### 可观测性

| 日志点 | 级别 | MDC 字段 | 日志内容 |
|--------|------|---------|---------|
| execute 入口 | INFO | sessionId, direction | `selectionMode={}, actionCount={}, clientRequestId={}` |
| SELECTION_STALE | WARN | sessionId, direction | `previewToken mismatch: expected={}, actual={}` |
| SELECTION_INVALID_ID | WARN | sessionId | `unknown actionIds (count): first_id [and N more]` |
| PREVIEW_TOO_LARGE | WARN | sessionId | `preview actions({}) exceeds limit({})` |

**排障起点**：当用户报告"数据已变化，请重新预览"时：
1. 搜索 `SELECTION_STALE` 关键字，找到对应 sessionId
2. 检查该 session 在 preview 和 execute 之间是否有 compare 操作
3. 确认 diff 数据是否真的发生变化

### 架构自检

- **分层边界**：selection 校验逻辑放在 `PlanBuilder`（core domain 层），不侵入 Controller/Service，符合 DDD 原则
- **依赖方向**：core → 无外部依赖；standalone → core，方向正确
- **KISS**：不引入新接口/新表/新中间件，仅扩展现有模型和逻辑
- **无红旗**

---

## M4 实施路线图

### 改动文件清单

| # | 文件 | 模块 | 变更类型 | 说明 |
|---|------|------|---------|------|
| 1 | `SelectionMode.java` | core | **新增** | ALL/PARTIAL 枚举 |
| 2 | `ApplyAction.java` | core | 修改 | 增加 `actionId` + `computeActionId()` + `escapeRequired()` |
| 3 | `ApplyOptions.java` | core | 修改 | 增加 4 字段 + `@JsonSetter` |
| 4 | `ErrorCode.java` | core | 修改 | 增加 DIFF_E_2010/2011/2012/2014 |
| 5 | `PlanBuilder.java` | core | 修改 | actionId 计算 + `computePreviewToken()` + selection 校验/过滤 + V1 主表限制 |
| 6 | `ApplyPreviewResponse.java` | standalone | 修改 | 增加 `ActionPreviewItem` + `previewToken` + `actions` + `from()` 签名变更 |
| 7 | `TenantDiffStandaloneApplyServiceImpl.java` | standalone | 修改 | preview 编排 + execute 审计日志 |
| 8 | `PlanBuilderTest.java` | core (test) | 修改 | 新增 selection 相关 16 个测试用例 |
| 9 | `ApplyPreviewResponseTest.java` | standalone (test) | **新增** | previewToken/actions 映射测试 |

### 任务卡拆分

#### T1: 模型层（core）— 枚举 + 字段

**范围**：`SelectionMode.java`（新增）、`ApplyAction.java`（修改）、`ApplyOptions.java`（修改）、`ErrorCode.java`（修改）

**DoD**：
- `SelectionMode` 枚举 ALL/PARTIAL 定义完成
- `ApplyAction` 增加 `actionId` 字段 + `computeActionId()` + `escapeRequired()`
- `ApplyOptions` 增加 4 字段 + `@JsonSetter(nulls=SKIP)`
- `ErrorCode` 增加 4 个错误码
- `mvn compile -pl tenant-diff-core` 通过

**预估**：1h

#### T2: 核心逻辑（core）— PlanBuilder

**依赖**：T1

**范围**：`PlanBuilder.java`

**DoD**：
- `build()` 方法中 actionId 计算逻辑完成
- `computePreviewToken()` 实现
- `validatePreviewToken()` 防陈旧校验
- `normalizeSelectedIds()` 归一化 + 格式 + 数量校验
- `validateSelectedIdsExist()` 严格存在性校验
- `validateMainTableOnly()` V1 主表限制
- `maxAffectedRows` 改为仅 EXECUTE 模式校验
- `mvn compile -pl tenant-diff-core` 通过

**预估**：2h

#### T3: 响应层（standalone）— DTO + Service

**依赖**：T1

**范围**：`ApplyPreviewResponse.java`、`TenantDiffStandaloneApplyServiceImpl.java`

**DoD**：
- `ActionPreviewItem` 内部类定义完成
- `ApplyPreviewResponse` 增加 `previewToken` + `actions` 字段
- `from()` 签名变更为 `from(ApplyPlan, String)` 并适配已有调用方
- Service `preview()` 增加 previewActionLimit 校验 + token 计算
- Service `execute()` 增加审计日志
- `mvn compile -pl tenant-diff-standalone` 通过

**预估**：1.5h

#### T4: 单元测试（core）

**依赖**：T2

**范围**：`PlanBuilderTest.java`

**DoD**：
- 覆盖 16 个测试用例（§9.1 全部用例）
- 包括：ALL 默认、null 兼容、空集合、缺 token、token 不匹配、未知 id、格式非法、超 5000、子表限制、AND 语义、allowDelete 交互、确定性、maxAffectedRows EXECUTE-only
- `mvn test -pl tenant-diff-core` 通过

**预估**：2h

#### T5: 集成测试（standalone）

**依赖**：T3, T4

**范围**：`ApplyPreviewResponseTest.java`、集成测试

**DoD**：
- 覆盖 §9.2 全部 8 个集成测试场景
- `mvn test -pl tenant-diff-standalone` 通过

**预估**：1.5h

### 依赖关系

```
T1（模型层）
├── T2（PlanBuilder）
│   └── T4（单元测试）
└── T3（DTO + Service）
    └── T5（集成测试）
```

T2 和 T3 可并行开发。

### DoD（可复制验收命令）

```bash
# 1. 编译
mvn compile -pl tenant-diff-core,tenant-diff-standalone

# 2. 单元测试
mvn test -pl tenant-diff-core -Dtest="PlanBuilderTest"

# 3. 全量测试
mvn test -pl tenant-diff-core,tenant-diff-standalone

# 4. 启动验证
mvn spring-boot:run -pl tenant-diff-standalone

# 5. 集成验证（手动）
# POST /apply/preview → 验证 actions 非空 + previewToken 存在
# POST /apply/execute (PARTIAL) → 验证仅选中记录被同步
```

### 回滚策略

本次改动为纯追加型：
- `selectionMode` 默认 `ALL`，不传等同旧行为
- 无 DB schema 变更
- 无需灰度开关
- 回滚策略：Git revert 即可

### 配置属性

| 属性名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `tenant-diff.apply.preview-action-limit` | int | 5000 | 单次 preview 最大 action 数 |

---

## 质量门槛自检

### 核心维度

| 维度 | 证据 |
|------|------|
| **合约明确** | API 请求/响应 JSON 示例完整；DTO 字段清单含类型/必填/约束；错误语义有 4 个专用错误码 + 错误处理矩阵 |
| **失败覆盖** | 枚举了 4 大类 10 个失败场景，每个场景有触发条件/错误码/HTTP 状态/保障措施 |
| **可验收** | 5 步可复制 DoD 命令（compile → unit test → full test → boot → integration） |
| **范围清晰** | In-scope 6 条 + Out-of-scope 5 条（V2/V3 明确标注）；影响面 9 个文件 |
| **架构一致** | 校验逻辑在 PlanBuilder（core domain 层）；依赖方向 core ← standalone；无新接口/新表/中间件 |

### 触发式维度

| 维度 | 证据 |
|------|------|
| **决策可追溯** | ADR-1：3 候选方案对比（无状态 Selection vs 持久化 Decision vs 多级白名单），推荐 A，代价/回滚明确 |
| **可观测性** | 4 个关键日志点（级别 + MDC + 内容）+ 排障起点说明 |
| **并行友好** | T2/T3 可并行；依赖图清晰 |

### 已知不足

| What | So what | Next |
|------|---------|------|
| 未设计子表 PARTIAL 选择 | 用户可能需要单独选择子表记录 | V2 需解决 IdMapping miss 安全策略后支持 |
| ExceptionHandler 仍返回 HTTP 200 | 前端无法通过 HTTP 状态码区分错误类型 | V2 全局统一改造时一起做 |
| 无 Micrometer metrics | 缺少 selection 使用率/错误率监控 | V2 项目引入 Micrometer 后补充 |

---

## M5 用户确认

以上为 Apply 勾选机制的完整技术设计方案。请确认：
1. 方案整体是否可以开始实现？
2. 任务卡拆分（T1-T5）和依赖关系是否合理？
3. 已知不足是否可接受？

请回复"可以开始实现"或提出修改意见。
