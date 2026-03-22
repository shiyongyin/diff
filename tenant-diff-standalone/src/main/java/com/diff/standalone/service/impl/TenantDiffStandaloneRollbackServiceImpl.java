package com.diff.standalone.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.diff.core.domain.apply.*;
import com.diff.core.apply.PlanBuilder;
import com.diff.core.engine.DiffDefaults;
import com.diff.core.engine.DiffRules;
import com.diff.core.engine.TenantDiffEngine;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.model.DerivedFieldNames;
import com.diff.core.domain.scope.TenantModelScope;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.diff.DiffSessionOptions;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.core.util.TypeConversionUtil;
import com.diff.standalone.web.dto.response.RollbackVerification;
import com.diff.standalone.web.dto.response.TenantDiffRollbackResponse;
import com.diff.standalone.apply.StandaloneBusinessDiffApplyExecutor;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.model.StandaloneTenantModelBuilder;
import com.diff.standalone.persistence.entity.TenantDiffApplyRecordPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.entity.TenantDiffSnapshotPo;
import com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSnapshotMapper;
import com.diff.standalone.plugin.StandaloneBusinessTypePlugin;
import com.diff.standalone.plugin.StandalonePluginRegistry;
import com.diff.standalone.service.TenantDiffStandaloneRollbackService;
import com.diff.standalone.util.StandaloneLoadOptionsResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import com.diff.core.domain.schema.BusinessSchema;
import com.diff.standalone.model.BuildWarning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * v1 回滚实现：将 TARGET 恢复到 Apply 前快照（业务级覆盖恢复，无 DAP）。
 *
 * <p>
 * 设计思路：
 * <ul>
 *     <li>Apply 前保存 TARGET 的业务快照（BusinessData JSON）。</li>
 *     <li>回滚时，将“快照（source）”与“当前 TARGET（target）”再次 Diff，得到恢复所需的变更集合。</li>
 *     <li>基于 Diff 生成 ApplyPlan，并执行到 TARGET，从而恢复到 Apply 前状态。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 注意：该回滚策略属于 v1 实现，强调工具化落地与可用性；对复杂场景（并发变更、跨业务依赖等）需要更严格的审计与冲突处理。
 * </p>
 *
 * <p>
 * <b>设计动机（WHY）</b>：
 * <ul>
 *     <li><b>快照回滚</b>：以 Apply 前快照为 source、当前 TARGET 为 target 做 Diff，生成"恢复计划"，避免逆向 SQL 推导的复杂性。</li>
 *     <li><b>CAS 乐观锁</b>：SUCCESS -> ROLLING_BACK 防止并发回滚；version 更新失败即冲突。</li>
 *     <li><b>v1 限制</b>：仅支持主库 target；外部数据源回滚需 apply_record 持久化 targetDataSourceKey 后二期支持。</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class TenantDiffStandaloneRollbackServiceImpl implements TenantDiffStandaloneRollbackService {
    private final TenantDiffApplyRecordMapper applyRecordMapper;
    private final TenantDiffSnapshotMapper snapshotMapper;
    private final TenantDiffSessionMapper sessionMapper;
    private final TenantDiffResultMapper resultMapper;
    private final StandaloneTenantModelBuilder modelBuilder;
    private final TenantDiffEngine diffEngine;
    private final PlanBuilder planBuilder;
    private final StandaloneBusinessDiffApplyExecutor diffApplyExecutor;
    private final ObjectMapper objectMapper;
    private final StandalonePluginRegistry pluginRegistry;

    public TenantDiffStandaloneRollbackServiceImpl(
        TenantDiffApplyRecordMapper applyRecordMapper,
        TenantDiffSnapshotMapper snapshotMapper,
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        StandaloneTenantModelBuilder modelBuilder,
        TenantDiffEngine diffEngine,
        PlanBuilder planBuilder,
        StandaloneBusinessDiffApplyExecutor diffApplyExecutor,
        ObjectMapper objectMapper,
        StandalonePluginRegistry pluginRegistry
    ) {
        this.applyRecordMapper = applyRecordMapper;
        this.snapshotMapper = snapshotMapper;
        this.sessionMapper = sessionMapper;
        this.resultMapper = resultMapper;
        this.modelBuilder = modelBuilder;
        this.diffEngine = diffEngine;
        this.planBuilder = planBuilder;
        this.diffApplyExecutor = diffApplyExecutor;
        this.objectMapper = objectMapper;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 回滚指定 applyId 对应的 Apply 变更。
     *
     * <p>
     * 该方法会：
     * <ul>
     *     <li>加载 apply_record 与 apply 前 TARGET 快照</li>
     *     <li>按快照范围构建当前 TARGET 模型</li>
     *     <li>执行 diff（snapshot vs current）得到“恢复差异”</li>
     *     <li>生成并执行恢复计划（允许 DELETE）</li>
     * </ul>
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantDiffRollbackResponse rollback(Long applyId, boolean acknowledgeDrift) {
        if (applyId == null) {
            throw new IllegalArgumentException("applyId is null");
        }

        TenantDiffApplyRecordPo record = applyRecordMapper.selectById(applyId);
        if (record == null) {
            throw new TenantDiffException(ErrorCode.APPLY_RECORD_NOT_FOUND);
        }

        // 状态检查：已回滚的不能重复回滚，避免重复执行恢复计划。
        if (ApplyRecordStatus.ROLLED_BACK.name().equals(record.getStatus())) {
            throw new TenantDiffException(ErrorCode.APPLY_ALREADY_ROLLED_BACK);
        }
        // 状态检查：apply_record 必须是 SUCCESS 才能回滚（RUNNING/FAILED 均不允许）
        if (!ApplyRecordStatus.SUCCESS.name().equals(record.getStatus())) {
            throw new TenantDiffException(ErrorCode.APPLY_NOT_SUCCESS);
        }

        Long sessionId = record.getSessionId();
        if (sessionId == null) {
            throw new IllegalArgumentException("apply_record.sessionId is null");
        }
        TenantDiffSessionPo session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
        }

        ApplyDirection direction = parseDirection(record.getDirection())
            .orElseThrow(() -> new IllegalArgumentException("apply_record.direction is invalid"));

        Long targetTenantId = resolveTargetTenantId(session, direction);

        // v1 限制：仅支持主库 target；外部数据源无法正确路由回滚写入，显式拒绝。
        validateRollbackDataSourceSupport(session, direction);

        List<TenantDiffSnapshotPo> snapshots = snapshotMapper.selectList(new QueryWrapper<TenantDiffSnapshotPo>()
            .eq("apply_id", applyId)
            .orderByAsc("business_type")
            .orderByAsc("business_key")
        );
        if (snapshots == null || snapshots.isEmpty()) {
            throw new TenantDiffException(ErrorCode.ROLLBACK_SNAPSHOT_INCOMPLETE,
                "no snapshots found for applyId: " + applyId);
        }

        List<BusinessData> snapshotModels = parseSnapshotModels(snapshots);
        TenantModelScope snapshotScope = scopeFromSnapshots(snapshots);
        TenantModelScope planScope = scopeFromPlan(record.getPlanJson());
        validateSnapshotCoverage(snapshots, planScope);
        TenantModelScope scope = mergeScopes(snapshotScope, planScope);
        LoadOptions loadOptions = loadOptionsFromSession(session, direction);

        StandaloneTenantModelBuilder.BuildResult current = modelBuilder.buildWithWarnings(targetTenantId, scope, loadOptions);
        logWarnings(sessionId, "rollback.current", current.warnings());

        DiffRules rollbackRules = rollbackDiffRules(session, scope);
        DriftDetectionResult driftDetectionResult = detectDrift(record, sessionId, targetTenantId, loadOptions, snapshotModels, current.models(), rollbackRules);
        if (driftDetectionResult.driftDetected() && !acknowledgeDrift) {
            throw new TenantDiffException(
                ErrorCode.ROLLBACK_DRIFT_DETECTED,
                driftDetectionResult.diagnostics());
        }

        // CAS 乐观锁：SUCCESS -> ROLLING_BACK，防止同一 apply 被并发回滚。
        TenantDiffApplyRecordPo casUpdate = new TenantDiffApplyRecordPo();
        casUpdate.setId(applyId);
        casUpdate.setVersion(record.getVersion());
        casUpdate.setStatus(ApplyRecordStatus.ROLLING_BACK.name());
        int updated = applyRecordMapper.updateById(casUpdate);
        if (updated == 0) {
            throw new TenantDiffException(ErrorCode.ROLLBACK_CONCURRENT_CONFLICT);
        }

        TenantDiffEngine.CompareResult compareResult = diffEngine.compare(snapshotModels, current.models(), rollbackRules);

        ApplyOptions options = ApplyOptions.builder()
            .mode(ApplyMode.EXECUTE)
            .allowDelete(true)
            .maxAffectedRows(0)
            .build();

        // 回滚 Diff 为快照->当前，A_TO_B 方向正确；allowDelete=true 允许恢复删除。
        ApplyPlan plan = planBuilder.build(sessionId, ApplyDirection.A_TO_B, options, compareResult.businessDiffs());
        ApplyResult result = diffApplyExecutor.execute(targetTenantId, plan, compareResult.businessDiffs(), ApplyMode.EXECUTE);

        StandaloneTenantModelBuilder.BuildResult afterRollback = modelBuilder.buildWithWarnings(targetTenantId, scope, loadOptions);
        logWarnings(sessionId, "rollback.after", afterRollback.warnings());
        TenantDiffEngine.CompareResult verifyCompareResult = diffEngine.compare(
            normalizeBusinessModelsForComparison(snapshotModels),
            normalizeBusinessModelsForComparison(afterRollback.models()),
            rollbackRules
        );
        ApplyPlan verifyPlan = buildVerifyPlan(sessionId, verifyCompareResult.businessDiffs());
        int remainingDiffCount = verifyPlan.getActions() == null ? 0 : verifyPlan.getActions().size();
        RollbackVerification verification = RollbackVerification.builder()
            .success(remainingDiffCount == 0)
            .remainingDiffCount(remainingDiffCount)
            .summary(remainingDiffCount == 0 ? "ROLLBACK_VERIFIED" : "ROLLBACK_REMAINING_DIFFS")
            .build();
        log.info("Rollback verify: applyId={}, success={}, remainingDiffCount={}",
            applyId, verification.isSuccess(), verification.getRemainingDiffCount());

        // 回滚成功后将 apply_record 状态更新为 ROLLED_BACK
        TenantDiffApplyRecordPo statusUpdate = new TenantDiffApplyRecordPo();
        statusUpdate.setId(applyId);
        statusUpdate.setStatus(ApplyRecordStatus.ROLLED_BACK.name());
        statusUpdate.setVerifyStatus(verification.isSuccess() ? "SUCCESS" : "FAILED");
        statusUpdate.setVerifyJson(toJson(verification));
        applyRecordMapper.updateById(statusUpdate);

        return TenantDiffRollbackResponse.builder()
            .applyId(applyId)
            .applyResult(result)
            .verification(verification)
            .driftDetected(driftDetectionResult.driftDetected())
            .diagnostics(driftDetectionResult.diagnostics())
            .build();
    }

    private DiffRules rollbackDiffRules(TenantDiffSessionPo session, TenantModelScope scope) {
        DiffRules base = session == null ? null : parseSessionDiffRules(session.getOptionsJson()).orElse(null);
        DiffRules effective = base == null ? DiffRules.defaults() : base;

        Set<String> ignore = new HashSet<>(effective.getDefaultIgnoreFields() == null
            ? DiffDefaults.DEFAULT_IGNORE_FIELDS
            : effective.getDefaultIgnoreFields());

        // 回滚在同一 tenant 内执行：外键字段必须参与 Diff，否则关系无法恢复；从 Plugin schema 动态获取。
        ignore.removeAll(collectFkFieldsFromPlugins(scope));

        return DiffRules.builder()
            .defaultIgnoreFields(ignore)
            .ignoreFieldsByTable(effective.getIgnoreFieldsByTable() == null ? Map.of() : effective.getIgnoreFieldsByTable())
            .build();
    }

    private Set<String> collectFkFieldsFromPlugins(TenantModelScope scope) {
        Set<String> fkFields = new HashSet<>();
        if (scope == null || scope.getBusinessTypes() == null) {
            return fkFields;
        }
        for (String businessType : scope.getBusinessTypes()) {
            StandaloneBusinessTypePlugin plugin = pluginRegistry.all().get(businessType);
            if (plugin == null || plugin.schema() == null) {
                continue;
            }
            List<BusinessSchema.TableRelation> relations = plugin.schema().getRelations();
            if (relations == null) {
                continue;
            }
            for (BusinessSchema.TableRelation rel : relations) {
                if (rel != null && rel.getFkColumn() != null && !rel.getFkColumn().isBlank()) {
                    fkFields.add(rel.getFkColumn());
                }
            }
        }
        return fkFields;
    }

    private Optional<DiffRules> parseSessionDiffRules(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return Optional.empty();
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(optionsJson, DiffSessionOptions.class);
            return Optional.ofNullable(options == null ? null : options.getDiffRules());
        } catch (Exception e) {
            throw new IllegalArgumentException("optionsJson parse failed for diffRules: " + e.getMessage(), e);
        }
    }

    private LoadOptions loadOptionsFromSession(TenantDiffSessionPo session, ApplyDirection direction) {
        if (session == null || session.getOptionsJson() == null || session.getOptionsJson().isBlank()) {
            return LoadOptions.builder().build();
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(session.getOptionsJson(), DiffSessionOptions.class);
            return StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
        } catch (Exception e) {
            Long sessionId = session == null ? null : session.getId();
            throw new IllegalArgumentException("optionsJson parse failed for loadOptions: sessionId=" + sessionId, e);
        }
    }

    private List<BusinessData> parseSnapshotModels(List<TenantDiffSnapshotPo> snapshots) {
        List<BusinessData> models = new ArrayList<>();
        for (TenantDiffSnapshotPo snapshot : snapshots) {
            if (snapshot == null || snapshot.getSnapshotJson() == null || snapshot.getSnapshotJson().isBlank()) {
                continue;
            }
            try {
                models.add(objectMapper.readValue(snapshot.getSnapshotJson(), BusinessData.class));
            } catch (Exception e) {
                throw new IllegalArgumentException("snapshotJson parse failed: " + e.getMessage(), e);
            }
        }
        return models;
    }

    private static TenantModelScope scopeFromSnapshots(List<TenantDiffSnapshotPo> snapshots) {
        Map<String, LinkedHashSet<String>> keysByType = new LinkedHashMap<>();
        for (TenantDiffSnapshotPo snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            String type = snapshot.getBusinessType();
            String key = snapshot.getBusinessKey();
            if (type == null || type.isBlank() || key == null || key.isBlank()) {
                continue;
            }
            keysByType.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(key);
        }

        List<String> types = new ArrayList<>(keysByType.keySet());
        Map<String, List<String>> businessKeysByType = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : keysByType.entrySet()) {
            businessKeysByType.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return TenantModelScope.builder()
            .businessTypes(types)
            .businessKeysByType(businessKeysByType)
            .build();
    }

    private DriftDetectionResult detectDrift(TenantDiffApplyRecordPo record,
                                             Long sessionId,
                                             Long targetTenantId,
                                             LoadOptions loadOptions,
                                             List<BusinessData> snapshotModels,
                                             List<BusinessData> currentModels,
                                             DiffRules rollbackRules) {
        ApplyPlan originalPlan = parsePlan(record.getPlanJson());
        if (originalPlan == null || originalPlan.getActions() == null || originalPlan.getActions().isEmpty()) {
            return DriftDetectionResult.noDrift();
        }
        Map<String, com.diff.core.domain.diff.BusinessDiff> originalDiffs = loadOriginalDiffs(sessionId);
        List<BusinessData> expectedPostApplyModels = buildExpectedPostApplyModels(
            snapshotModels,
            originalPlan,
            originalDiffs,
            targetTenantId,
            currentModels
        );
        TenantDiffEngine.CompareResult driftCompare = diffEngine.compare(
            normalizeBusinessModelsForComparison(expectedPostApplyModels),
            normalizeBusinessModelsForComparison(currentModels),
            rollbackRules
        );
        ApplyPlan driftPlan = buildVerifyPlan(sessionId, driftCompare.businessDiffs());
        int driftActionCount = driftPlan.getActions() == null ? 0 : driftPlan.getActions().size();
        if (driftActionCount == 0) {
            return DriftDetectionResult.noDrift();
        }
        String firstActionHint = driftPlan.getActions().get(0).getActionId();
        return new DriftDetectionResult(
            true,
            "drift detected before rollback: remainingActions=" + driftActionCount + ", firstAction=" + firstActionHint
        );
    }

    private ApplyPlan parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(planJson, ApplyPlan.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("planJson parse failed: " + e.getMessage(), e);
        }
    }

    private Map<String, com.diff.core.domain.diff.BusinessDiff> loadOriginalDiffs(Long sessionId) {
        Map<String, com.diff.core.domain.diff.BusinessDiff> diffs = new LinkedHashMap<>();
        List<com.diff.standalone.persistence.entity.TenantDiffResultPo> resultPos = resultMapper.selectList(
            new QueryWrapper<com.diff.standalone.persistence.entity.TenantDiffResultPo>()
                .eq("session_id", sessionId)
        );
        if (resultPos == null) {
            return diffs;
        }
        for (com.diff.standalone.persistence.entity.TenantDiffResultPo po : resultPos) {
            if (po == null || po.getDiffJson() == null || po.getDiffJson().isBlank()) {
                continue;
            }
            try {
                com.diff.core.domain.diff.BusinessDiff diff =
                    objectMapper.readValue(po.getDiffJson(), com.diff.core.domain.diff.BusinessDiff.class);
                diffs.put(diff.getBusinessType() + "|" + diff.getBusinessKey(), diff);
            } catch (Exception e) {
                throw new IllegalStateException("diff_json deserialize failed: resultId=" + po.getId(), e);
            }
        }
        return diffs;
    }

    private List<BusinessData> buildExpectedPostApplyModels(List<BusinessData> snapshotModels,
                                                            ApplyPlan originalPlan,
                                                            Map<String, com.diff.core.domain.diff.BusinessDiff> originalDiffs,
                                                            Long targetTenantId,
                                                            List<BusinessData> currentModels) {
        Map<String, BusinessData> businessMap = cloneBusinessModels(snapshotModels);
        Map<String, Long> currentRecordIds = indexActualRecordIds(currentModels);
        List<ApplyAction> orderedActions = new ArrayList<>(originalPlan.getActions());
        orderedActions.sort(Comparator
            .comparing((ApplyAction action) -> action.getDiffType() == DiffType.DELETE ? 1 : 0)
            .thenComparing(action -> action.getDependencyLevel() == null ? Integer.MAX_VALUE : action.getDependencyLevel())
            .thenComparing(ApplyAction::getTableName, Comparator.nullsLast(String::compareTo))
            .thenComparing(ApplyAction::getRecordBusinessKey, Comparator.nullsLast(String::compareTo)));
        for (ApplyAction action : orderedActions) {
            if (action == null) {
                continue;
            }
            String businessKey = action.getBusinessType() + "|" + action.getBusinessKey();
            com.diff.core.domain.diff.BusinessDiff businessDiff = originalDiffs.get(businessKey);
            if (businessDiff == null) {
                continue;
            }
            com.diff.core.domain.diff.RecordDiff recordDiff =
                findRecordDiff(businessDiff, action.getTableName(), action.getRecordBusinessKey());
            Map<String, Object> desiredFields = expectedFieldsForAction(
                action.getBusinessType(),
                action,
                desiredFieldsForAction(originalPlan.getDirection(), recordDiff),
                targetTenantId,
                currentRecordIds
            );
            if (action.getDiffType() == DiffType.DELETE) {
                removeRecord(businessMap, action);
            } else {
                upsertRecord(businessMap, businessDiff, action, desiredFields, targetTenantId);
            }
        }
        return new ArrayList<>(businessMap.values());
    }

    private Map<String, Long> indexActualRecordIds(List<BusinessData> models) {
        Map<String, Long> ids = new LinkedHashMap<>();
        if (models == null) {
            return ids;
        }
        for (BusinessData businessData : models) {
            if (businessData == null || businessData.getTables() == null) {
                continue;
            }
            for (com.diff.core.domain.model.TableData table : businessData.getTables()) {
                if (table == null || table.getTableName() == null || table.getRecords() == null) {
                    continue;
                }
                for (com.diff.core.domain.model.RecordData record : table.getRecords()) {
                    if (record == null || record.getBusinessKey() == null || record.getBusinessKey().isBlank()) {
                        continue;
                    }
                    Long actualId = record.getId();
                    if (actualId == null && record.getFields() != null) {
                        actualId = TypeConversionUtil.toLong(record.getFields().get("id"));
                    }
                    if (actualId != null) {
                        ids.put(recordIdKey(table.getTableName(), record.getBusinessKey()), actualId);
                    }
                }
            }
        }
        return ids;
    }

    private Map<String, BusinessData> cloneBusinessModels(List<BusinessData> snapshotModels) {
        Map<String, BusinessData> businessMap = new LinkedHashMap<>();
        if (snapshotModels == null) {
            return businessMap;
        }
        for (BusinessData model : snapshotModels) {
            if (model == null) {
                continue;
            }
            List<com.diff.core.domain.model.TableData> tables = new ArrayList<>();
            if (model.getTables() != null) {
                for (com.diff.core.domain.model.TableData table : model.getTables()) {
                    List<com.diff.core.domain.model.RecordData> records = new ArrayList<>();
                    if (table != null && table.getRecords() != null) {
                        for (com.diff.core.domain.model.RecordData record : table.getRecords()) {
                            if (record == null) {
                                continue;
                            }
                            records.add(com.diff.core.domain.model.RecordData.builder()
                                .id(record.getId())
                                .businessKey(record.getBusinessKey())
                                .businessNote(record.getBusinessNote())
                                .publicFlag(record.isPublicFlag())
                                .fields(record.getFields() == null ? null : new LinkedHashMap<>(record.getFields()))
                                .fingerprint(record.getFingerprint())
                                .modifyTime(record.getModifyTime())
                                .build());
                        }
                    }
                    if (table != null) {
                        tables.add(com.diff.core.domain.model.TableData.builder()
                            .tableName(table.getTableName())
                            .dependencyLevel(table.getDependencyLevel())
                            .records(records)
                            .build());
                    }
                }
            }
            BusinessData copy = BusinessData.builder()
                .businessType(model.getBusinessType())
                .businessTable(model.getBusinessTable())
                .businessId(model.getBusinessId())
                .businessKey(model.getBusinessKey())
                .businessName(model.getBusinessName())
                .tenantId(model.getTenantId())
                .tables(tables)
                .build();
            businessMap.put(copy.getBusinessType() + "|" + copy.getBusinessKey(), copy);
        }
        return businessMap;
    }

    private List<BusinessData> normalizeBusinessModelsForComparison(List<BusinessData> models) {
        Map<String, BusinessData> normalized = cloneBusinessModels(models);
        for (BusinessData businessData : normalized.values()) {
            if (businessData == null || businessData.getTables() == null) {
                continue;
            }
            for (com.diff.core.domain.model.TableData table : businessData.getTables()) {
                if (table == null || table.getRecords() == null) {
                    continue;
                }
                String relationFkColumn = relationFkColumnOf(businessData.getBusinessType(), table.getTableName());
                for (com.diff.core.domain.model.RecordData record : table.getRecords()) {
                    if (record == null || record.getFields() == null) {
                        continue;
                    }
                    Map<String, Object> normalizedFields = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : record.getFields().entrySet()) {
                        if (relationFkColumn != null && relationFkColumn.equals(entry.getKey())) {
                            continue;
                        }
                        normalizedFields.put(entry.getKey(), normalizeComparableValue(entry.getValue()));
                    }
                    record.setFields(normalizedFields);
                    record.setFingerprint(null);
                }
            }
        }
        return new ArrayList<>(normalized.values());
    }

    private String relationFkColumnOf(String businessType, String tableName) {
        BusinessSchema.TableRelation relation = relationOf(businessType, tableName);
        if (relation == null || relation.getFkColumn() == null || relation.getFkColumn().isBlank()) {
            return null;
        }
        return relation.getFkColumn();
    }

    private Object normalizeComparableValue(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.stripTrailingZeros();
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros();
        }
        return value;
    }

    private Map<String, Object> desiredFieldsForAction(ApplyDirection direction,
                                                       com.diff.core.domain.diff.RecordDiff recordDiff) {
        Map<String, Object> sourceFields = direction == ApplyDirection.A_TO_B
            ? recordDiff.getSourceFields()
            : recordDiff.getTargetFields();
        return sourceFields == null ? Map.of() : new LinkedHashMap<>(sourceFields);
    }

    private Map<String, Object> expectedFieldsForAction(String businessType,
                                                        ApplyAction action,
                                                        Map<String, Object> desiredFields,
                                                        Long targetTenantId,
                                                        Map<String, Long> currentRecordIds) {
        Map<String, Object> expectedFields = new LinkedHashMap<>(desiredFields == null ? Map.of() : desiredFields);
        if (targetTenantId != null) {
            expectedFields.put("tenantsid", targetTenantId);
        }

        Long actualId = currentRecordIds.get(recordIdKey(action.getTableName(), action.getRecordBusinessKey()));
        if (actualId != null) {
            expectedFields.put("id", actualId);
        }

        BusinessSchema.TableRelation relation = relationOf(businessType, action.getTableName());
        if (relation == null || relation.getFkColumn() == null || relation.getFkColumn().isBlank()) {
            return expectedFields;
        }

        String parentBusinessKey = resolveParentBusinessKey(expectedFields);
        if (parentBusinessKey == null || parentBusinessKey.isBlank()) {
            return expectedFields;
        }
        Long parentActualId = currentRecordIds.get(recordIdKey(relation.getParentTable(), parentBusinessKey));
        if (parentActualId != null) {
            expectedFields.put(relation.getFkColumn(), parentActualId);
        }
        return expectedFields;
    }

    private BusinessSchema.TableRelation relationOf(String businessType, String tableName) {
        if (pluginRegistry == null || businessType == null || businessType.isBlank()
            || tableName == null || tableName.isBlank()) {
            return null;
        }
        StandaloneBusinessTypePlugin plugin = pluginRegistry.all().get(businessType);
        if (plugin == null || plugin.schema() == null || plugin.schema().getRelations() == null) {
            return null;
        }
        for (BusinessSchema.TableRelation relation : plugin.schema().getRelations()) {
            if (relation != null && tableName.equals(relation.getChildTable())) {
                return relation;
            }
        }
        return null;
    }

    private String resolveParentBusinessKey(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        Object parent = fields.get(DerivedFieldNames.PARENT_BUSINESS_KEY);
        if (parent instanceof String parentKey && !parentKey.isBlank()) {
            return parentKey;
        }
        Object main = fields.get(DerivedFieldNames.MAIN_BUSINESS_KEY);
        if (main instanceof String mainKey && !mainKey.isBlank()) {
            return mainKey;
        }
        return null;
    }

    private String recordIdKey(String tableName, String recordBusinessKey) {
        return tableName + "::" + recordBusinessKey;
    }

    private void upsertRecord(Map<String, BusinessData> businessMap,
                              com.diff.core.domain.diff.BusinessDiff businessDiff,
                              ApplyAction action,
                              Map<String, Object> desiredFields,
                              Long targetTenantId) {
        String businessKey = action.getBusinessType() + "|" + action.getBusinessKey();
        BusinessData business = businessMap.computeIfAbsent(businessKey, ignored -> BusinessData.builder()
            .businessType(businessDiff.getBusinessType())
            .businessTable(businessDiff.getBusinessTable())
            .businessKey(businessDiff.getBusinessKey())
            .businessName(businessDiff.getBusinessName())
            .tenantId(targetTenantId)
            .tables(new ArrayList<>())
            .build());
        List<com.diff.core.domain.model.TableData> tables = business.getTables();
        if (tables == null) {
            tables = new ArrayList<>();
            business.setTables(tables);
        }
        com.diff.core.domain.model.TableData table = null;
        for (com.diff.core.domain.model.TableData candidate : tables) {
            if (candidate != null && action.getTableName().equals(candidate.getTableName())) {
                table = candidate;
                break;
            }
        }
        if (table == null) {
            table = com.diff.core.domain.model.TableData.builder()
                .tableName(action.getTableName())
                .dependencyLevel(action.getDependencyLevel())
                .records(new ArrayList<>())
                .build();
            tables.add(table);
        }
        List<com.diff.core.domain.model.RecordData> records = table.getRecords();
        if (records == null) {
            records = new ArrayList<>();
            table.setRecords(records);
        }
        com.diff.core.domain.model.RecordData existing = records.stream()
            .filter(candidate -> candidate != null && action.getRecordBusinessKey().equals(candidate.getBusinessKey()))
            .findFirst()
            .orElse(null);
        Long expectedRecordId = TypeConversionUtil.toLong(desiredFields == null ? null : desiredFields.get("id"));
        com.diff.core.domain.model.RecordData replacement = com.diff.core.domain.model.RecordData.builder()
            .id(expectedRecordId != null ? expectedRecordId : (existing == null ? null : existing.getId()))
            .businessKey(action.getRecordBusinessKey())
            .publicFlag(existing != null && existing.isPublicFlag())
            .fields(desiredFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(desiredFields))
            .modifyTime(existing == null ? null : existing.getModifyTime())
            .fingerprint(null)
            .build();
        if (existing == null) {
            records.add(replacement);
            return;
        }
        records.remove(existing);
        records.add(replacement);
    }

    private void removeRecord(Map<String, BusinessData> businessMap, ApplyAction action) {
        BusinessData business = businessMap.get(action.getBusinessType() + "|" + action.getBusinessKey());
        if (business == null || business.getTables() == null) {
            return;
        }
        for (com.diff.core.domain.model.TableData table : business.getTables()) {
            if (table == null || table.getRecords() == null || !action.getTableName().equals(table.getTableName())) {
                continue;
            }
            table.getRecords().removeIf(record ->
                record != null && action.getRecordBusinessKey().equals(record.getBusinessKey()));
        }
    }

    private com.diff.core.domain.diff.RecordDiff findRecordDiff(
        com.diff.core.domain.diff.BusinessDiff businessDiff,
        String tableName,
        String recordBusinessKey
    ) {
        if (businessDiff == null || businessDiff.getTableDiffs() == null) {
            throw new IllegalArgumentException("businessDiff.tableDiffs is empty");
        }
        for (com.diff.core.domain.diff.TableDiff tableDiff : businessDiff.getTableDiffs()) {
            if (tableDiff == null || tableDiff.getRecordDiffs() == null || !tableName.equals(tableDiff.getTableName())) {
                continue;
            }
            for (com.diff.core.domain.diff.RecordDiff recordDiff : tableDiff.getRecordDiffs()) {
                if (recordDiff != null && recordBusinessKey.equals(recordDiff.getRecordBusinessKey())) {
                    return recordDiff;
                }
            }
        }
        throw new IllegalArgumentException(
            "record diff not found: tableName=" + tableName + ", recordBusinessKey=" + recordBusinessKey);
    }

    private void validateSnapshotCoverage(List<TenantDiffSnapshotPo> snapshots, TenantModelScope planScope) {
        if (planScope == null || planScope.getBusinessKeysByType() == null || planScope.getBusinessKeysByType().isEmpty()) {
            return;
        }
        Map<String, Set<String>> snapshotKeysByType = new LinkedHashMap<>();
        for (TenantDiffSnapshotPo snapshot : snapshots) {
            if (snapshot == null || snapshot.getBusinessType() == null || snapshot.getBusinessKey() == null) {
                continue;
            }
            snapshotKeysByType
                .computeIfAbsent(snapshot.getBusinessType(), ignored -> new LinkedHashSet<>())
                .add(snapshot.getBusinessKey());
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : planScope.getBusinessKeysByType().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            Set<String> snapshotKeys = snapshotKeysByType.getOrDefault(entry.getKey(), Set.of());
            for (String businessKey : entry.getValue()) {
                if (businessKey != null && !snapshotKeys.contains(businessKey)) {
                    missing.add(entry.getKey() + ":" + businessKey);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new TenantDiffException(
                ErrorCode.ROLLBACK_SNAPSHOT_INCOMPLETE,
                "missing snapshots: " + String.join(", ", missing));
        }
    }

    private ApplyPlan buildVerifyPlan(Long sessionId, List<com.diff.core.domain.diff.BusinessDiff> businessDiffs) {
        return planBuilder.build(
            sessionId,
            ApplyDirection.A_TO_B,
            ApplyOptions.builder().mode(ApplyMode.DRY_RUN).allowDelete(true).build(),
            businessDiffs
        );
    }

    private TenantModelScope scopeFromPlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return TenantModelScope.builder().build();
        }
        try {
            ApplyPlan plan = objectMapper.readValue(planJson, ApplyPlan.class);
            Map<String, LinkedHashSet<String>> keysByType = new LinkedHashMap<>();
            if (plan.getActions() != null) {
                for (ApplyAction action : plan.getActions()) {
                    if (action == null) continue;
                    String type = action.getBusinessType();
                    String key = action.getBusinessKey();
                    if (type != null && !type.isBlank() && key != null && !key.isBlank()) {
                        keysByType.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(key);
                    }
                }
            }
            List<String> types = new ArrayList<>(keysByType.keySet());
            Map<String, List<String>> businessKeysByType = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : keysByType.entrySet()) {
                businessKeysByType.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return TenantModelScope.builder()
                .businessTypes(types)
                .businessKeysByType(businessKeysByType)
                .build();
        } catch (Exception e) {
            log.warn("反序列化 planJson 失败, 回退为空 scope", e);
            return TenantModelScope.builder().build();
        }
    }

    private static TenantModelScope mergeScopes(TenantModelScope a, TenantModelScope b) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();

        addToMerged(merged, a);
        addToMerged(merged, b);

        List<String> types = new ArrayList<>(merged.keySet());
        Map<String, List<String>> businessKeysByType = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : merged.entrySet()) {
            businessKeysByType.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return TenantModelScope.builder()
            .businessTypes(types)
            .businessKeysByType(businessKeysByType)
            .build();
    }

    private static void addToMerged(Map<String, LinkedHashSet<String>> merged, TenantModelScope scope) {
        if (scope == null) return;
        if (scope.getBusinessTypes() != null) {
            for (String type : scope.getBusinessTypes()) {
                merged.computeIfAbsent(type, t -> new LinkedHashSet<>());
            }
        }
        if (scope.getBusinessKeysByType() != null) {
            for (Map.Entry<String, List<String>> entry : scope.getBusinessKeysByType().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    merged.computeIfAbsent(entry.getKey(), t -> new LinkedHashSet<>()).addAll(entry.getValue());
                }
            }
        }
    }

    private static Long resolveTargetTenantId(TenantDiffSessionPo session, ApplyDirection direction) {
        if (session == null) {
            throw new IllegalArgumentException("session is null");
        }
        if (session.getSourceTenantId() == null || session.getTargetTenantId() == null) {
            throw new IllegalArgumentException("session tenant ids are null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is null");
        }
        return direction == ApplyDirection.A_TO_B ? session.getTargetTenantId() : session.getSourceTenantId();
    }

    private static Optional<ApplyDirection> parseDirection(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ApplyDirection.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * v1 回滚仅支持 target=主库方向。
     *
     * <p>若原始 Apply 的写入端指向外部数据源，当前回滚逻辑无法正确路由，
     * 需显式拒绝以防止数据写入错误的库。二期将通过 apply_record 持久化
     * targetDataSourceKey 来支持外部数据源回滚。</p>
     */
    private void validateRollbackDataSourceSupport(TenantDiffSessionPo session, ApplyDirection direction) {
        String optionsJson = session.getOptionsJson();
        if (optionsJson == null || optionsJson.isBlank()) {
            return; // 无配置 → 主数据源，允许回滚
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(optionsJson, DiffSessionOptions.class);
            LoadOptions applyTargetOptions = StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
            String dsKey = applyTargetOptions == null ? null : applyTargetOptions.getDataSourceKey();
            if (dsKey != null && !dsKey.isBlank() && !DiffDataSourceRegistry.PRIMARY_KEY.equals(dsKey.trim())) {
                throw new TenantDiffException(ErrorCode.ROLLBACK_DATASOURCE_UNSUPPORTED);
            }
        } catch (TenantDiffException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                "无法解析 session optionsJson 以校验回滚数据源, sessionId=" + session.getId(), e);
        }
    }

    private void logWarnings(Long sessionId, String phase, List<BuildWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        for (BuildWarning warning : warnings) {
            if (warning == null || warning.message() == null || warning.message().isBlank()) {
                continue;
            }
            log.warn("租户差异回滚警告: sessionId={}, phase={}, businessType={}, businessKey={}, msg={}",
                sessionId, phase, warning.businessType(), warning.businessKey(), warning.message());
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON serialize failed: " + e.getMessage(), e);
        }
    }

    private record DriftDetectionResult(boolean driftDetected, String diagnostics) {
        private static DriftDetectionResult noDrift() {
            return new DriftDetectionResult(false, null);
        }
    }
}
