# 差异视图优化方案 V1（修订版）

> **文档定位**：基于当前代码库现状的可执行技术方案，覆盖 2 个功能模块的完整设计。
>
> **日期**：2026-03-15
> **修订说明**：经对抗性审核后精简——删除 SimpleDiff 独立模块（合并到 F1）、精简 Decision 接口（砍掉 seed 仪式 / updateStatus / applyDecisionFilter 开关）、API 参数合并为单一 `view` 枚举。
> **前置依赖**：无新增外部依赖，全部基于现有 core/standalone 模块内完成。

---

## 目录

1. [方案总览](#1-方案总览)
2. [Feature 1: NOOP 过滤 + showFields 投影 + 大字段裁剪](#2-feature-1-noop-过滤--showfields-投影--大字段裁剪)
3. [Feature 2: Decision 管理服务层](#3-feature-2-decision-管理服务层)
4. [依赖关系与实施顺序](#4-依赖关系与实施顺序)
5. [风险与约束](#5-风险与约束)
6. [变更文件清单](#6-变更文件清单)

---

## 1. 方案总览

| # | 功能 | 核心问题 | 产出 |
|---|------|---------|------|
| F1 | NOOP 过滤 + showFields 投影 + 大字段裁剪 | `getBusinessDetail` 返回全量引擎原始输出（含 NOOP 记录 + 所有列 + 全量 sourceFields/targetFields），前端消费成本高 | `DiffViewFilter`（static 工具类） + `BusinessSchema.showFieldsByTable` + API `view` 枚举参数 |
| F2 | Decision 管理服务层 | 表/PO/Mapper/枚举已就绪但 Service 空白，无法 "逐条审查 → 持久化 → Apply 时自动排除" | `DecisionRecordService`（save + list 两个核心方法） + REST API（2 端点） |

**依赖关系**：F1 和 F2 相互独立，可并行实施。

---

## 2. Feature 1: NOOP 过滤 + showFields 投影 + 大字段裁剪

### 2.1 为什么做

**现状问题（基于代码实证）**：

1. **NOOP 记录不过滤**：`TenantDiffEngine.compareRecord()` 对两侧都存在且内容一致的记录产出 `DiffType.NOOP` 的 `RecordDiff`，写入 `diff_json` 并原样返回给 `getBusinessDetail` API。`PlanBuilder.build()` 在 Apply 链路中已过滤 NOOP（第 125 行 `if (type == null || type == DiffType.NOOP) { continue; }`），但查询 API 侧没有任何过滤。

2. **showFields 从未填充**：`RecordDiff.showFields` 字段存在（Javadoc 标注"可选展示投影字段，由插件/视图层按需填充"），但整个代码库没有任何地方填充它。前端看到的是 `sourceFields` / `targetFields` 中的全部数据库列（包含 `id`、`tenantsid`、`version`、`data_modify_time` 等系统字段）。

3. **大字段不可裁剪**：`RecordDiff` 的 `sourceFields` 和 `targetFields` 包含完整字段快照。当前 demo 表约 8 列影响有限，但框架面向的生产场景表列数更多，API 无法按需裁剪。

4. **统计数据包含 NOOP**：`TableDiffCounts.noopCount` 和 `DiffStatistics.noopCount` 包含 NOOP 计数，前端用 `totalRecords` 展示会产生误导。

**必要性判定**：`RecordDiff.showFields` 字段的存在本身就是设计意图的证据，缺少的只是"谁来填充它"。NOOP 过滤在 Apply 侧已有先例（PlanBuilder），查询侧补齐是合理的对称行为。

### 2.2 做什么

| 变更项 | 层级 | 说明 |
|--------|------|------|
| `BusinessSchema` 增加 `showFieldsByTable` | core | 声明各表前端展示字段，与 `ignoreFieldsByTable`、`fieldTypesByTable` 同层元数据 |
| 新增 `DiffViewFilter` | standalone/service/support | static 工具类：过滤 NOOP + 投影 showFields + 可选裁剪大字段 + 重算统计 |
| `getBusinessDetail` API 增加 `view` 参数 | standalone/web/controller | 单一枚举参数控制返回粒度（替代多个 boolean） |
| Demo 插件配置 `showFieldsByTable` | demo/plugin | 示例配置 |

### 2.3 怎么做

#### Step 1: BusinessSchema 增加 showFieldsByTable

```java
// BusinessSchema.java 新增字段
@Builder.Default
private Map<String, List<String>> showFieldsByTable = Collections.emptyMap();
```

设计决策：
- `List`（非 `Set`）：保留字段展示顺序，前端按此顺序渲染列
- `@Builder.Default` 为 `emptyMap()`：所有现有 `BusinessSchema.builder()...build()` 调用无需修改
- 未配置的表不做投影，`showFields` 保持 `null`

#### Step 2: API view 枚举参数

用单一 `view` 参数替代多个 boolean（`excludeNoop`、`stripRawFields`、`view=simple`）：

```java
/**
 * getBusinessDetail 的视图模式。
 */
public enum DiffDetailView {
    /** 原始引擎输出，含 NOOP + 全量字段。现有行为，向后兼容。 */
    FULL,
    /** 过滤 NOOP + 投影 showFields，保留 sourceFields/targetFields。 */
    FILTERED,
    /** 过滤 NOOP + 投影 showFields + 裁剪 sourceFields/targetFields 为 null。 */
    COMPACT
}
```

API 签名：

```java
@GetMapping("/getBusinessDetail")
public ApiResponse<BusinessDiff> getBusinessDetail(
    @RequestParam("sessionId") Long sessionId,
    @RequestParam("businessType") String businessType,
    @RequestParam("businessKey") String businessKey,
    @RequestParam(value = "view", required = false,
                  defaultValue = "FULL") DiffDetailView view
) {
    Optional<BusinessDiff> diff = service.getBusinessDetail(
        sessionId, businessType, businessKey);
    if (diff.isEmpty()) {
        return ApiResponse.fail(ErrorCode.BUSINESS_DETAIL_NOT_FOUND);
    }
    BusinessDiff result = diff.get();
    if (view != DiffDetailView.FULL) {
        BusinessSchema schema = resolveSchema(businessType);
        boolean stripRawFields = (view == DiffDetailView.COMPACT);
        result = DiffViewFilter.filterAndProject(
            result, schema, stripRawFields);
    }
    return ApiResponse.ok(result);
}
```

优势：
- 返回类型始终是 `ApiResponse<BusinessDiff>`，不需要 `ApiResponse<?>`，类型安全 + Swagger 友好
- 不传 `view` 时默认 `FULL`，完全向后兼容
- 一个参数三种模式，语义清晰

#### Step 3: 实现 DiffViewFilter

位置：`tenant-diff-standalone/src/main/java/com/diff/standalone/service/support/DiffViewFilter.java`

```java
/**
 * Diff 视图过滤工具——将引擎原始输出转换为前端友好的视图。
 *
 * <p>全部 static 方法，无状态，线程安全。</p>
 */
public final class DiffViewFilter {

    private DiffViewFilter() {}

    /**
     * 过滤 NOOP 记录、投影 showFields、可选裁剪 sourceFields/targetFields。
     *
     * <p>构建新对象树返回，不修改入参。对不可变的子对象（FieldDiff 等）
     * 共享引用而非深拷贝，减少内存开销。</p>
     *
     * @param original      原始 BusinessDiff
     * @param schema        业务 Schema（用于 showFieldsByTable，可为 null）
     * @param stripRawFields 是否将 sourceFields/targetFields 置为 null
     * @return 过滤后的新 BusinessDiff
     */
    public static BusinessDiff filterAndProject(
            BusinessDiff original,
            BusinessSchema schema,
            boolean stripRawFields) {
        if (original == null) {
            return null;
        }

        Map<String, List<String>> showConfig =
            (schema != null && schema.getShowFieldsByTable() != null)
                ? schema.getShowFieldsByTable()
                : Collections.emptyMap();

        List<TableDiff> newTableDiffs = new ArrayList<>();
        int totalInsert = 0, totalUpdate = 0, totalDelete = 0;

        if (original.getTableDiffs() != null) {
            for (TableDiff td : original.getTableDiffs()) {
                if (td == null || td.getRecordDiffs() == null) {
                    continue;
                }

                List<RecordDiff> filtered = new ArrayList<>();
                for (RecordDiff rd : td.getRecordDiffs()) {
                    if (rd == null || rd.getDiffType() == DiffType.NOOP) {
                        continue;
                    }
                    filtered.add(projectRecord(
                        rd, td.getTableName(), showConfig, stripRawFields));
                }

                TableDiff.TableDiffCounts counts = recount(filtered);
                totalInsert += safeInt(counts.getInsertCount());
                totalUpdate += safeInt(counts.getUpdateCount());
                totalDelete += safeInt(counts.getDeleteCount());

                newTableDiffs.add(TableDiff.builder()
                    .tableName(td.getTableName())
                    .dependencyLevel(td.getDependencyLevel())
                    .diffType(td.getDiffType())
                    .counts(counts)
                    .recordDiffs(filtered)
                    .build());
            }
        }

        int totalRecords = totalInsert + totalUpdate + totalDelete;
        DiffStatistics newStats = DiffStatistics.builder()
            .totalBusinesses(1)
            .totalTables(newTableDiffs.size())
            .totalRecords(totalRecords)
            .insertCount(totalInsert)
            .updateCount(totalUpdate)
            .deleteCount(totalDelete)
            .noopCount(0)
            .build();

        return BusinessDiff.builder()
            .businessType(original.getBusinessType())
            .businessTable(original.getBusinessTable())
            .businessKey(original.getBusinessKey())
            .businessName(original.getBusinessName())
            .diffType(original.getDiffType())
            .statistics(newStats)
            .tableDiffs(newTableDiffs)
            .build();
    }

    private static RecordDiff projectRecord(
            RecordDiff rd, String tableName,
            Map<String, List<String>> showConfig,
            boolean stripRawFields) {
        RecordDiff.RecordDiffBuilder builder = RecordDiff.builder()
            .recordBusinessKey(rd.getRecordBusinessKey())
            .diffType(rd.getDiffType())
            .decision(rd.getDecision())
            .decisionReason(rd.getDecisionReason())
            .decisionTime(rd.getDecisionTime())
            .fieldDiffs(rd.getFieldDiffs())   // 共享引用，FieldDiff 不可变
            .warnings(rd.getWarnings())       // 共享引用
            .showFields(buildShowFields(
                rd, tableName, showConfig));

        if (stripRawFields) {
            builder.sourceFields(null).targetFields(null);
        } else {
            builder.sourceFields(rd.getSourceFields())
                   .targetFields(rd.getTargetFields());
        }
        return builder.build();
    }

    private static Map<String, Object> buildShowFields(
            RecordDiff rd, String tableName,
            Map<String, List<String>> showConfig) {
        List<String> fieldNames = showConfig.get(tableName);
        if (fieldNames == null || fieldNames.isEmpty()) {
            return null;
        }

        // INSERT → sourceFields，DELETE → targetFields，UPDATE → sourceFields
        Map<String, Object> source = rd.getSourceFields();
        Map<String, Object> target = rd.getTargetFields();
        Map<String, Object> primary =
            (source != null) ? source : target;
        if (primary == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : fieldNames) {
            if (primary.containsKey(name)) {
                result.put(name, primary.get(name));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static TableDiff.TableDiffCounts recount(List<RecordDiff> records) {
        int insert = 0, update = 0, delete = 0;
        for (RecordDiff rd : records) {
            if (rd == null || rd.getDiffType() == null) continue;
            switch (rd.getDiffType()) {
                case INSERT -> insert++;
                case UPDATE -> update++;
                case DELETE -> delete++;
                default -> {}
            }
        }
        return TableDiff.TableDiffCounts.builder()
            .insertCount(insert)
            .updateCount(update)
            .deleteCount(delete)
            .noopCount(0)
            .build();
    }

    private static int safeInt(Integer v) {
        return v == null ? 0 : v;
    }
}
```

关键设计点：
- **全 static，无实例化**：与 `PlanBuilder.computePreviewToken()` 同风格
- **不修改入参**：构建新的 BusinessDiff/TableDiff/RecordDiff 对象树返回
- **共享不可变引用**：`FieldDiff`、`warnings` 等不可变子对象不深拷贝，直接共享引用
- **命名为 Filter 而非 Mapper**：避免与 MyBatis Mapper（DAO）混淆

#### Step 4: Schema 解析路由

`StandaloneTenantModelBuilder` 已持有插件 Map，新增一个方法暴露 schema：

```java
// StandaloneTenantModelBuilder.java 新增
public BusinessSchema getSchema(String businessType) {
    StandaloneBusinessPlugin plugin = pluginMap.get(businessType);
    return plugin != null ? plugin.schema() : null;
}
```

Controller 通过注入 `StandaloneTenantModelBuilder` 获取 schema。

#### Step 5: Demo 插件配置 showFieldsByTable

```java
// ExampleOrderPlugin.schema() 增加
.showFieldsByTable(Map.of(
    TABLE_ORDER, List.of("order_code", "order_name", "status", "total_amount"),
    TABLE_ORDER_ITEM, List.of("item_code", "product_name", "quantity", "unit_price")
))
```

`ExampleProductPlugin` 同理，按需配置。未配置的表 `showFields` 为 `null`，不影响现有行为。

### 2.4 如何验证

| 测试类 | 验证点 | 类型 |
|--------|--------|------|
| `DiffViewFilterTest` | 1) NOOP 记录被过滤 | 单元测试 |
| | 2) 非 NOOP 记录保留且 showFields 正确投影 | |
| | 3) `stripRawFields=true` → sourceFields/targetFields 为 null | |
| | 4) `stripRawFields=false` → sourceFields/targetFields 保留 | |
| | 5) TableDiffCounts 重算正确（noopCount=0） | |
| | 6) DiffStatistics 重算正确（totalRecords 不含 NOOP） | |
| | 7) 未配置 showFieldsByTable 的表 → showFields 为 null | |
| | 8) INSERT/DELETE/UPDATE 各类型的投影取值来源正确 | |
| | 9) null BusinessDiff → 返回 null | |
| | 10) 原始 BusinessDiff 未被修改（入参不可变验证） | |
| API 测试 | 11) 不传 `view` → 默认 FULL，返回含 NOOP 的原始结果 | 集成测试 |
| | 12) `view=FILTERED` → 无 NOOP，有 showFields，有 sourceFields | |
| | 13) `view=COMPACT` → 无 NOOP，有 showFields，sourceFields 为 null | |

**验证脚本**：

```bash
SESSION_ID=$(curl -s -X POST .../session/create -d '...' | jq '.data.sessionId')

# FULL（默认，向后兼容）
curl -s ".../getBusinessDetail?sessionId=$SESSION_ID&businessType=EXAMPLE_ORDER&businessKey=ORD-001" \
  | jq '[.data.tableDiffs[].recordDiffs[].diffType] | group_by(.) | map({(.[0]): length}) | add'
# 预期：含 NOOP 记录

# FILTERED
curl -s ".../getBusinessDetail?sessionId=$SESSION_ID&businessType=EXAMPLE_ORDER&businessKey=ORD-001&view=FILTERED" \
  | jq '[.data.tableDiffs[].recordDiffs[].diffType]'
# 预期：不含 NOOP，showFields 有值，sourceFields 有值

# COMPACT
curl -s ".../getBusinessDetail?sessionId=$SESSION_ID&businessType=EXAMPLE_ORDER&businessKey=ORD-001&view=COMPACT" \
  | jq '.data.tableDiffs[0].recordDiffs[0] | {showFields, sourceFields, targetFields}'
# 预期：showFields 有值，sourceFields=null，targetFields=null
```

---

## 3. Feature 2: Decision 管理服务层

### 3.1 为什么做

**现状问题（基于代码实证）**：

1. **基础设施已就绪，Service 层空白**：
   - DDL：`xai_tenant_diff_decision_record` 表已存在于 `schema-h2.sql` 和 `schema.sql`
   - PO：`TenantDiffDecisionRecordPo` 已定义（含 sessionId、businessType、businessKey、tableName、recordBusinessKey、diffType、decision、decisionReason、executionStatus、applyId 等完整字段）
   - Mapper：`TenantDiffDecisionRecordMapper extends BaseMapper<TenantDiffDecisionRecordPo>` 已定义
   - 枚举：`DecisionType`（ACCEPT/SKIP）和 `DecisionExecutionStatus`（PENDING/SKIPPED/SUCCESS/FAILED）已定义
   - **但没有任何 Service 类使用上述基础设施**

2. **RecordDiff.decision 字段是死代码**：`TenantDiffEngine.compareRecord()` 对每条记录硬编码设置 `decision = DecisionType.ACCEPT`，此值从未被下游代码读取或修改。

3. **与 Selection 的关系——互补而非替代**：
   - **Selection**（已实现）：Apply 阶段的一次性选择——"这次 Apply 包含哪些 action"
   - **Decision**（未实现）：Review 阶段的持久化结论——"这条记录审查后标记为跳过"，可跨多次操作复用
   - Selection 每次 Apply 都要重新勾选；Decision 标记一次后持久化，后续 Apply 自动排除 SKIP 记录

### 3.2 与 Selection 的边界澄清

|  | Selection（已有） | Decision（本方案） |
|--|------------------|-------------------|
| 粒度 | action 级（actionId） | record 级（tableName + recordBusinessKey） |
| 生命周期 | 单次 Apply 请求有效 | 持久化到 DB，跨请求有效 |
| 触发方式 | 前端在 preview 结果上勾选 | 前端在 diff 详情页逐条审查标记 |
| 适用场景 | "本次只同步这几个" | "这条差异审查后决定不同步" |
| 存储 | 无存储，请求参数传递 | `xai_tenant_diff_decision_record` 表 |

两者独立运作，不互锁。当两者同时生效时的行为：Decision 过滤发生在 `buildPlan` 之前（Service 层过滤 diff 列表），Selection 过滤发生在 `PlanBuilder.build()` 内部。结果是取交集——一条记录必须同时满足"Decision 不是 SKIP"且"在 selectedActionIds 中"才会被执行。

### 3.3 做什么

| 变更项 | 层级 | 说明 |
|--------|------|------|
| 新增 `DecisionRecordService` 接口 | standalone/service | 核心方法：save（upsert 语义） + list |
| 新增 `DecisionRecordServiceImpl` | standalone/service/impl | 基于 `TenantDiffDecisionRecordMapper` 实现 |
| 新增 `DecisionController` | standalone/web/controller | REST API：2 个端点 |
| 新增请求/响应 DTO | standalone/web/dto | `SaveDecisionRequest` + `DecisionRecordResponse` |
| `buildPlan` 集成 | standalone/service/impl | Apply 前按 Decision 过滤 SKIP 记录 |
| DDL 补建索引 | schema.sql / schema-h2.sql | 查询性能保障 |

### 3.4 怎么做

#### Step 1: DecisionRecordService 接口

位置：`tenant-diff-standalone/src/main/java/com/diff/standalone/service/DecisionRecordService.java`

```java
public interface DecisionRecordService {

    /**
     * 批量保存用户决策（upsert 语义）。
     *
     * <p>不存在则创建，已存在则更新 decision/decisionReason/decisionTime。
     * 无需先 seed——首次 save 即创建。</p>
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型
     * @param businessKey  业务键
     * @param decisions    决策列表
     * @return 实际保存的条数
     */
    int saveDecisions(Long sessionId, String businessType,
                      String businessKey, List<DecisionItem> decisions);

    /**
     * 查询某业务对象下所有决策记录。
     */
    List<TenantDiffDecisionRecordPo> listDecisions(Long sessionId,
                                                    String businessType,
                                                    String businessKey);
}
```

辅助 DTO——只需一个：

```java
/**
 * 单条决策项（前端提交）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DecisionItem {
    private String tableName;
    private String recordBusinessKey;
    private String diffType;
    private DecisionType decision;
    private String decisionReason;
}
```

对比原方案精简点：
- **砍掉 `seed` 方法**：save 内部自动 upsert，不存在则创建，已存在则更新。调用方只需一个 API
- **砍掉 `getDecisionMap`**：这是内部实现细节，在 `buildPlan` 集成时内部调用 `listDecisions` 转换即可
- **砍掉 `updateExecutionStatus`**：V1 不做执行状态回写——这是审计增强，不是核心功能，后续按需加
- **砍掉 `ExecutionStatusUpdate` DTO**：随 `updateExecutionStatus` 一起移除

#### Step 2: DecisionRecordServiceImpl 核心逻辑

```java
@Slf4j
public class DecisionRecordServiceImpl implements DecisionRecordService {

    private final TenantDiffDecisionRecordMapper mapper;

    public DecisionRecordServiceImpl(TenantDiffDecisionRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int saveDecisions(Long sessionId, String businessType,
                             String businessKey, List<DecisionItem> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return 0;
        }
        int saved = 0;
        LocalDateTime now = LocalDateTime.now();

        for (DecisionItem item : decisions) {
            if (item == null || item.getDecision() == null) {
                continue;
            }

            // 按 sessionId + businessType + businessKey + tableName + recordBusinessKey 查找
            TenantDiffDecisionRecordPo existing = findRecord(
                sessionId, businessType, businessKey,
                item.getTableName(), item.getRecordBusinessKey());

            if (existing != null) {
                // UPDATE
                existing.setDecision(item.getDecision().name());
                existing.setDecisionReason(item.getDecisionReason());
                existing.setDecisionTime(now);
                existing.setUpdatedAt(now);
                mapper.updateById(existing);
            } else {
                // INSERT（首次 save 自动创建，无需先 seed）
                TenantDiffDecisionRecordPo po = TenantDiffDecisionRecordPo.builder()
                    .sessionId(sessionId)
                    .businessType(businessType)
                    .businessKey(businessKey)
                    .tableName(item.getTableName())
                    .recordBusinessKey(item.getRecordBusinessKey())
                    .diffType(item.getDiffType())
                    .decision(item.getDecision().name())
                    .decisionReason(item.getDecisionReason())
                    .decisionTime(now)
                    .executionStatus(DecisionExecutionStatus.PENDING.name())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
                mapper.insert(po);
            }
            saved++;
        }
        return saved;
    }

    @Override
    public List<TenantDiffDecisionRecordPo> listDecisions(
            Long sessionId, String businessType, String businessKey) {
        return mapper.selectList(
            new LambdaQueryWrapper<TenantDiffDecisionRecordPo>()
                .eq(TenantDiffDecisionRecordPo::getSessionId, sessionId)
                .eq(TenantDiffDecisionRecordPo::getBusinessType, businessType)
                .eq(TenantDiffDecisionRecordPo::getBusinessKey, businessKey)
        );
    }

    private TenantDiffDecisionRecordPo findRecord(
            Long sessionId, String businessType, String businessKey,
            String tableName, String recordBusinessKey) {
        return mapper.selectOne(
            new LambdaQueryWrapper<TenantDiffDecisionRecordPo>()
                .eq(TenantDiffDecisionRecordPo::getSessionId, sessionId)
                .eq(TenantDiffDecisionRecordPo::getBusinessType, businessType)
                .eq(TenantDiffDecisionRecordPo::getBusinessKey, businessKey)
                .eq(TenantDiffDecisionRecordPo::getTableName, tableName)
                .eq(TenantDiffDecisionRecordPo::getRecordBusinessKey, recordBusinessKey)
                .last("LIMIT 1")
        );
    }
}
```

#### Step 3: REST API

位置：`tenant-diff-standalone/src/main/java/com/diff/standalone/web/controller/TenantDiffStandaloneDecisionController.java`

只有 2 个端点：

```java
@RestController
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled",
                        havingValue = "true")
@RequestMapping("/api/tenantDiff/standalone/decision")
public class TenantDiffStandaloneDecisionController {

    private final DecisionRecordService decisionService;

    /**
     * 批量保存决策（upsert 语义，不存在则创建）。
     */
    @PostMapping("/save")
    public ApiResponse<Map<String, Integer>> save(
            @RequestBody @Valid SaveDecisionRequest request) {
        int count = decisionService.saveDecisions(
            request.getSessionId(), request.getBusinessType(),
            request.getBusinessKey(), request.getDecisions());
        return ApiResponse.ok(Map.of("savedCount", count));
    }

    /**
     * 查询某业务对象下所有决策记录。
     */
    @GetMapping("/list")
    public ApiResponse<List<DecisionRecordResponse>> list(
            @RequestParam("sessionId") Long sessionId,
            @RequestParam("businessType") String businessType,
            @RequestParam("businessKey") String businessKey) {
        List<TenantDiffDecisionRecordPo> records =
            decisionService.listDecisions(sessionId, businessType, businessKey);
        return ApiResponse.ok(records.stream()
            .map(DecisionRecordResponse::from)
            .collect(Collectors.toList()));
    }
}
```

对比原方案精简点：
- **2 个端点（非 3 个）**：砍掉 `/seed`
- **无 `applyDecisionFilter` 开关**：如果 decision_record 表中有 SKIP 记录，Apply 时默认尊重。没有决策记录则不过滤——行为由"数据是否存在"自然决定

#### Step 4: 与 Apply 链路集成

集成点：`TenantDiffStandaloneApplyServiceImpl.buildPlan()` 中，在调用 `planBuilder.build()` **之前**过滤 SKIP 记录。

```java
// TenantDiffStandaloneApplyServiceImpl.buildPlan() 中新增
// DecisionRecordService 通过构造函数注入，允许为 null（未配置时不过滤）
if (decisionRecordService != null) {
    diffs = applyDecisionFilter(sessionId, diffs);
}
return planBuilder.build(sessionId, direction, options, diffs);
```

```java
/**
 * 按 Decision 过滤 SKIP 记录。
 * 构建新 List 返回，不修改入参中的 BusinessDiff/TableDiff 对象。
 */
private List<BusinessDiff> applyDecisionFilter(
        Long sessionId, List<BusinessDiff> diffs) {
    List<BusinessDiff> result = new ArrayList<>(diffs.size());

    for (BusinessDiff diff : diffs) {
        if (diff == null) {
            continue;
        }
        List<TenantDiffDecisionRecordPo> records =
            decisionRecordService.listDecisions(
                sessionId, diff.getBusinessType(), diff.getBusinessKey());
        if (records.isEmpty()) {
            result.add(diff);
            continue;
        }

        // 构建 SKIP 集合
        Set<String> skipKeys = new HashSet<>();
        for (TenantDiffDecisionRecordPo r : records) {
            if (DecisionType.SKIP.name().equals(r.getDecision())) {
                skipKeys.add(r.getTableName() + "#" + r.getRecordBusinessKey());
            }
        }
        if (skipKeys.isEmpty()) {
            result.add(diff);
            continue;
        }

        // 构建新的 tableDiffs 列表，不修改原对象
        List<TableDiff> newTableDiffs = new ArrayList<>();
        if (diff.getTableDiffs() != null) {
            for (TableDiff td : diff.getTableDiffs()) {
                if (td == null || td.getRecordDiffs() == null) {
                    newTableDiffs.add(td);
                    continue;
                }
                List<RecordDiff> filtered = td.getRecordDiffs().stream()
                    .filter(rd -> {
                        String key = td.getTableName() + "#"
                                     + rd.getRecordBusinessKey();
                        return !skipKeys.contains(key);
                    })
                    .collect(Collectors.toList());

                newTableDiffs.add(TableDiff.builder()
                    .tableName(td.getTableName())
                    .dependencyLevel(td.getDependencyLevel())
                    .diffType(td.getDiffType())
                    .counts(td.getCounts())
                    .recordDiffs(filtered)
                    .build());
            }
        }

        result.add(BusinessDiff.builder()
            .businessType(diff.getBusinessType())
            .businessTable(diff.getBusinessTable())
            .businessKey(diff.getBusinessKey())
            .businessName(diff.getBusinessName())
            .diffType(diff.getDiffType())
            .statistics(diff.getStatistics())
            .tableDiffs(newTableDiffs)
            .build());
    }
    return result;
}
```

对比原方案修正点：
- **不修改入参**：原方案中 `td.setRecordDiffs(...)` 原地修改了从 DB 反序列化的对象，这里改为构建新对象返回
- **无 `applyDecisionFilter` 开关**：`DecisionRecordService` 注入为 null 时跳过，非 null 时默认生效。行为由"是否注入了 DecisionRecordService Bean"决定，而非请求参数
- **SKIP 为少数的假设**：构建 skipKeys 集合（SKIP 记录通常远少于 ACCEPT），遍历时用 contains 判断

#### Step 5: DDL 补建索引

```sql
-- decision_record 表当前无索引（除主键），查询性能无保障
CREATE INDEX idx_decision_session_biz ON xai_tenant_diff_decision_record
    (session_id, business_type, business_key);
```

需要同时更新 `schema-h2.sql`、`schema-mysql.sql`、`demo/schema.sql` 三处。

### 3.5 如何验证

| 测试类 | 验证点 | 类型 |
|--------|--------|------|
| `DecisionRecordServiceTest` | 1) save 首次 → INSERT（自动创建） | 单元测试 |
| | 2) save 已存在 → UPDATE decision/reason/time | |
| | 3) save 后 list → 返回最新决策 | |
| | 4) 空 decisions 列表 → 返回 0 | |
| | 5) null decision → 跳过不处理 | |
| Decision API 测试 | 6) save → list → 验证数据一致 | 集成测试 |
| | 7) 多次 save 同一条记录 → 最终状态正确 | |
| Apply + Decision 集成测试 | 8) 标记 SKIP → execute → SKIP 记录未被执行 | 集成测试 |
| | 9) 无任何 decision 记录 → 全量执行（与现有行为一致） | |
| | 10) SKIP + Selection(PARTIAL) 同时生效 → 取交集 | |

**验证脚本**：

```bash
# 1. 创建 session + compare
SESSION_ID=$(curl -s -X POST .../session/create -d '...' | jq '.data.sessionId')

# 2. 直接 save 决策（无需先 seed）
curl -s -X POST .../decision/save -d '{
  "sessionId": '$SESSION_ID',
  "businessType": "EXAMPLE_ORDER",
  "businessKey": "ORD-001",
  "decisions": [
    {"tableName":"example_order","recordBusinessKey":"ORD-001",
     "diffType":"UPDATE","decision":"SKIP","decisionReason":"暂不同步"}
  ]
}'
# 预期：{"data":{"savedCount":1}}

# 3. 查询验证
curl -s ".../decision/list?sessionId=$SESSION_ID&businessType=EXAMPLE_ORDER&businessKey=ORD-001" \
  | jq '.data[] | {recordBusinessKey, decision}'
# 预期：ORD-001 → SKIP

# 4. Apply → SKIP 记录不应被执行
curl -s -X POST .../apply/execute -d '{
  "sessionId": '$SESSION_ID',
  "direction": "A_TO_B",
  "options": {}
}'
```

---

## 4. 依赖关系与实施顺序

```
Phase 1 (可并行)
├── F1: BusinessSchema.showFieldsByTable + DiffViewFilter + API view 参数  （约 1.5 天）
└── F2: DecisionRecordService + API + buildPlan 集成                       （约 1.5 天）
```

### Commit 策略

| 序号 | Commit 内容 | 依赖 | 预估工时 |
|------|------------|------|---------|
| 1 | `BusinessSchema` 增加 `showFieldsByTable` + `DiffDetailView` 枚举 | 无 | 0.5h |
| 2 | 新增 `DiffViewFilter` + 单元测试 | Commit 1 | 3h |
| 3 | `getBusinessDetail` API 增加 `view` 参数 + schema 路由 + 集成测试 | Commit 2 | 1.5h |
| 4 | Demo 插件配置 `showFieldsByTable` | Commit 1 | 0.5h |
| 5 | `DecisionRecordService` 接口 + 实现 + 单元测试 | 无 | 2.5h |
| 6 | `DecisionController` REST API + 集成测试 | Commit 5 | 1.5h |
| 7 | `buildPlan` Decision 过滤集成 + DDL 索引 + 集成测试 | Commit 5 | 2h |

**总预估**：~12h（对比原方案 17h，减少 30%）

---

## 5. 风险与约束

### 5.1 向后兼容

| 功能 | 兼容策略 |
|------|---------|
| F1 showFieldsByTable | `@Builder.Default` 为空 Map，现有 schema 无需修改 |
| F1 view 参数 | 默认 `FULL`，不传时行为与现有完全一致 |
| F1 返回类型 | 始终为 `ApiResponse<BusinessDiff>`，类型安全 |
| F2 Decision 过滤 | `DecisionRecordService` 允许不注入（null），此时不过滤 |
| F2 Decision 过滤 | 无 decision_record 记录时不过滤，全量执行 |

### 5.2 性能考量

| 场景 | 考量 |
|------|------|
| DiffViewFilter | 构建新对象树但共享 FieldDiff/warnings 等不可变子对象引用，峰值内存开销约为原始 BusinessDiff 结构体积（不含大字段引用时远小于 2 倍） |
| Decision save | 每条 decision 一次 SELECT + 一次 INSERT/UPDATE。当前 demo 规模可接受。后续若批量场景（>500 条）可优化为批量 upsert |
| Decision buildPlan 查询 | 每个 businessType+businessKey 一次 SELECT。需要索引支撑（Step 5 已覆盖） |

### 5.3 已知限制

| 限制 | 说明 |
|------|------|
| showFieldsByTable 静态配置 | 当前通过 Plugin.schema() 硬编码，无法动态调整。后续可扩展为配置文件或 DB 配置 |
| V1 不回写执行状态 | `executionStatus` 字段 V1 始终为 `PENDING`，不在 Apply 后回写为 SUCCESS/SKIPPED/FAILED。后续版本按需补充 |
| Decision 与 Selection 不互锁 | 两者独立运作取交集。不存在"标记 SKIP 的记录仍被 Selection 强制执行"的情况——因为 Decision 过滤在 Selection 之前 |
| Decision save 不校验 diff 存在性 | V1 不检查 sessionId + businessType + businessKey 是否确实有对应的 diff 结果。传入错误的组合不会报错，只是创建了无效的 decision_record |

---

## 6. 变更文件清单

### 6.1 Feature 1 (NOOP 过滤 + showFields 投影 + 大字段裁剪)

| 文件 | 操作 | 说明 |
|------|------|------|
| `core/.../schema/BusinessSchema.java` | **修改** | 增加 `showFieldsByTable` 字段 |
| `standalone/.../service/support/DiffViewFilter.java` | **新增** | static 过滤 + 投影 + 裁剪工具类 |
| `standalone/.../service/support/DiffDetailView.java` | **新增** | view 枚举（FULL / FILTERED / COMPACT） |
| `standalone/.../web/controller/TenantDiffStandaloneSessionController.java` | **修改** | `getBusinessDetail` 增加 `view` 参数 |
| `standalone/.../model/StandaloneTenantModelBuilder.java` | **修改** | 增加 `getSchema(businessType)` 方法 |
| `demo/.../plugin/ExampleOrderPlugin.java` | **修改** | schema() 增加 showFieldsByTable 配置 |
| `standalone/src/test/.../support/DiffViewFilterTest.java` | **新增** | 单元测试 |

**小计**：3 新增 + 4 修改 = 7 文件

### 6.2 Feature 2 (Decision 管理服务层)

| 文件 | 操作 | 说明 |
|------|------|------|
| `standalone/.../service/DecisionRecordService.java` | **新增** | 接口（2 方法） |
| `standalone/.../service/impl/DecisionRecordServiceImpl.java` | **新增** | 实现 |
| `standalone/.../web/controller/TenantDiffStandaloneDecisionController.java` | **新增** | REST API（2 端点） |
| `standalone/.../web/dto/request/SaveDecisionRequest.java` | **新增** | 请求 DTO |
| `standalone/.../web/dto/request/DecisionItem.java` | **新增** | 决策条目 DTO |
| `standalone/.../web/dto/response/DecisionRecordResponse.java` | **新增** | 响应 DTO |
| `standalone/.../service/impl/TenantDiffStandaloneApplyServiceImpl.java` | **修改** | buildPlan 前按 Decision 过滤 |
| `standalone/.../config/StandaloneAutoConfiguration.java` | **修改** | Bean 注册 |
| `schema-h2.sql` / `schema-mysql.sql` / `demo/schema.sql` | **修改** | 补建索引 |
| `standalone/src/test/.../DecisionRecordServiceTest.java` | **新增** | 单元测试 |
| `standalone/src/test/.../DecisionApiTest.java` | **新增** | 集成测试 |

**小计**：8 新增 + 4 修改 = 12 文件

---

### 修订对照

| 维度 | 原方案 | 修订后 | 变化 |
|------|--------|--------|------|
| 功能模块数 | 3（F1 + F2 + F3） | 2（F1 + F2） | F3 合并到 F1 |
| 新增文件数 | ~18 | 11 | -39% |
| 总文件变更数 | 26 | 19 | -27% |
| 新增类数 | ~12（含 4 个 SimpleDiff record） | 7 | -42% |
| F2 Service 方法数 | 5 | 2 | -60% |
| F2 REST 端点数 | 3 | 2 | -33% |
| API 新参数 | `excludeNoop` + `view` + `applyDecisionFilter` | `view` 枚举 | 3 → 1 |
| 预估工时 | ~17h | ~12h | -30% |
