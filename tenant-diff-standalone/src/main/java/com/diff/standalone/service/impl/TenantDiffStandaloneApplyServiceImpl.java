package com.diff.standalone.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diff.core.apply.PlanBuilder;
import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.apply.ApplyActionError;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyRecordStatus;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.apply.SelectionMode;
import com.diff.core.domain.diff.*;
import com.diff.core.domain.exception.ApplyExecutionException;
import com.diff.core.engine.DiffRules;
import com.diff.core.engine.TenantDiffEngine;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.model.DerivedFieldNames;
import com.diff.core.domain.schema.BusinessSchema;
import com.diff.core.util.TypeConversionUtil;
import com.diff.standalone.web.dto.response.ApplyPreviewResponse;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.apply.StandaloneApplyExecutor;
import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.snapshot.StandaloneSnapshotBuilder;
import com.diff.standalone.persistence.entity.TenantDiffApplyRecordPo;
import com.diff.standalone.persistence.entity.TenantDiffApplyLeasePo;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.entity.TenantDiffSnapshotPo;
import com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSnapshotMapper;
import com.diff.standalone.service.ApplyAuditService;
import com.diff.standalone.service.ApplyLeaseService;
import com.diff.standalone.service.DecisionRecordService;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.plugin.StandaloneBusinessTypePlugin;
import com.diff.standalone.plugin.StandalonePluginRegistry;
import com.diff.standalone.util.StandaloneLoadOptionsResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Standalone Apply 编排实现：审计（apply_record）+ 快照（snapshot）+ 执行器（executor）（无 DAP）。
 *
 * <p>
 * 该类不直接关心"如何拼 SQL"，而是负责 Apply 的治理与可回滚性：
 * <ul>
 *     <li>保存 planJson 与执行状态（便于审计/追溯）</li>
 *     <li>在执行前保存 TARGET 快照（用于回滚）</li>
 *     <li>委托 {@link StandaloneApplyExecutor} 执行计划</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>设计动机（WHY）</b>：
 * <ul>
 *     <li><b>审计轨迹</b>：planJson 与 apply_record 持久化，支持事后追溯与问题复现。</li>
 *     <li><b>快照先行</b>：Apply 前先落库 TARGET 快照，回滚时以快照为 source 做逆向 Diff，保证可恢复。</li>
 *     <li><b>事务边界</b>：整次 Apply 包在 {@code @Transactional} 内，保证 record/snapshot/状态更新原子性；执行器内部 SQL 与主事务需数据源一致。</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class TenantDiffStandaloneApplyServiceImpl implements TenantDiffStandaloneApplyService {
    private final TenantDiffApplyRecordMapper applyRecordMapper;
    private final TenantDiffSnapshotMapper snapshotMapper;
    private final TenantDiffSessionMapper sessionMapper;
    private final TenantDiffResultMapper resultMapper;
    private final StandaloneSnapshotBuilder snapshotBuilder;
    private final StandaloneApplyExecutor applyExecutor;
    private final TenantDiffEngine diffEngine;
    private final PlanBuilder planBuilder;
    private final ObjectMapper objectMapper;
    private final int previewActionLimit;
    private final Duration previewTokenTtl;
    private final Duration maxCompareAge;
    private final Duration targetLeaseTtl;
    private final ApplyAuditService applyAuditService;
    private final ApplyLeaseService applyLeaseService;
    private final StandalonePluginRegistry pluginRegistry;

    /** 可选依赖——仅当 decision 模块启用时非 null。 */
    @Nullable
    private final DecisionRecordService decisionRecordService;

    public TenantDiffStandaloneApplyServiceImpl(
        TenantDiffApplyRecordMapper applyRecordMapper,
        TenantDiffSnapshotMapper snapshotMapper,
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        StandaloneSnapshotBuilder snapshotBuilder,
        StandaloneApplyExecutor applyExecutor,
        TenantDiffEngine diffEngine,
        PlanBuilder planBuilder,
        ObjectMapper objectMapper,
        int previewActionLimit,
        Duration previewTokenTtl,
        Duration maxCompareAge,
        Duration targetLeaseTtl,
        ApplyAuditService applyAuditService,
        ApplyLeaseService applyLeaseService,
        StandalonePluginRegistry pluginRegistry,
        @Nullable DecisionRecordService decisionRecordService
    ) {
        this.applyRecordMapper = applyRecordMapper;
        this.snapshotMapper = snapshotMapper;
        this.sessionMapper = sessionMapper;
        this.resultMapper = resultMapper;
        this.snapshotBuilder = snapshotBuilder;
        this.applyExecutor = applyExecutor;
        this.diffEngine = diffEngine;
        this.planBuilder = planBuilder;
        this.objectMapper = objectMapper;
        this.previewActionLimit = previewActionLimit;
        this.previewTokenTtl = previewTokenTtl == null ? Duration.ofMinutes(30) : previewTokenTtl;
        this.maxCompareAge = maxCompareAge == null ? Duration.ZERO : maxCompareAge;
        this.targetLeaseTtl = targetLeaseTtl == null ? Duration.ofMinutes(10) : targetLeaseTtl;
        this.applyAuditService = applyAuditService;
        this.applyLeaseService = applyLeaseService;
        this.pluginRegistry = pluginRegistry;
        this.decisionRecordService = decisionRecordService;
    }

    @Override
    public ApplyPreviewResponse preview(Long sessionId, ApplyDirection direction, ApplyOptions options) {
        // 创建独立的 preview 选项，不修改调用方传入的 options。
        ApplyOptions.ApplyOptionsBuilder previewBuilder = ApplyOptions.builder()
            .mode(ApplyMode.DRY_RUN)
            .selectionMode(SelectionMode.ALL)
            .selectedActionIds(Collections.emptySet())
            .previewToken(null);

        // 保留调用方的过滤条件
        if (options != null) {
            previewBuilder
                .allowDelete(options.isAllowDelete())
                .maxAffectedRows(options.getMaxAffectedRows())
                .businessKeys(options.getBusinessKeys())
                .businessTypes(options.getBusinessTypes())
                .diffTypes(options.getDiffTypes());
        }
        ApplyOptions previewOptions = previewBuilder.build();

        ApplyPlan plan = buildPlan(sessionId, direction, previewOptions);

        int actionCount = plan.getActions() == null ? 0 : plan.getActions().size();
        if (actionCount > previewActionLimit) {
            throw new TenantDiffException(ErrorCode.PREVIEW_TOO_LARGE,
                "preview actions(" + actionCount + ") exceeds limit(" + previewActionLimit + ")");
        }

        String previewToken = issuePreviewToken(sessionId, direction, plan.getActions());
        log.info("Apply preview: sessionId={}, direction={}, actionCount={}, previewToken={}",
            sessionId, direction, actionCount, previewToken);
        return ApplyPreviewResponse.from(plan, previewToken);
    }

    @Override
    public ApplyPlan buildPlan(Long sessionId, ApplyDirection direction, ApplyOptions options) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is null");
        }

        TenantDiffSessionPo session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
        }

        List<TenantDiffResultPo> resultPos = resultMapper.selectList(
            new LambdaQueryWrapper<TenantDiffResultPo>()
                .eq(TenantDiffResultPo::getSessionId, sessionId)
        );
        if (resultPos == null || resultPos.isEmpty()) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND,
                "session " + sessionId + " has no diff results");
        }

        List<BusinessDiff> diffs = new ArrayList<>(resultPos.size());
        for (TenantDiffResultPo po : resultPos) {
            if (po == null || po.getDiffJson() == null || po.getDiffJson().isBlank()) {
                continue;
            }
            try {
                BusinessDiff diff = objectMapper.readValue(po.getDiffJson(), BusinessDiff.class);
                diffs.add(diff);
            } catch (Exception e) {
                log.warn("反序列化 diff_json 失败: resultId={}, businessType={}", po.getId(), po.getBusinessType(), e);
            }
        }

        if (decisionRecordService != null) {
            diffs = applyDecisionFilter(sessionId, diffs);
        }

        validatePreviewTokenTtl(options);
        return planBuilder.build(sessionId, direction, options, diffs);
    }

    /**
     * 按 Decision 记录过滤 diffs：SKIP 的记录从 tableDiffs 中移除，不修改入参。
     */
    private List<BusinessDiff> applyDecisionFilter(Long sessionId, List<BusinessDiff> diffs) {
        DecisionRecordService svc = this.decisionRecordService;
        if (svc == null) {
            return diffs;
        }
        List<BusinessDiff> result = new ArrayList<>(diffs.size());
        for (BusinessDiff bd : diffs) {
            List<TenantDiffDecisionRecordPo> decisions = svc.listDecisions(
                sessionId, bd.getBusinessType(), bd.getBusinessKey());

            if (decisions == null || decisions.isEmpty()) {
                result.add(bd);
                continue;
            }

            Set<String> skipKeys = decisions.stream()
                .filter(d -> DecisionType.SKIP.name().equals(d.getDecision()))
                .map(d -> d.getTableName() + "|" + d.getRecordBusinessKey())
                .collect(Collectors.toSet());

            if (skipKeys.isEmpty()) {
                result.add(bd);
                continue;
            }

            List<TableDiff> filteredTables = new ArrayList<>();
            if (bd.getTableDiffs() != null) {
                for (TableDiff td : bd.getTableDiffs()) {
                    List<RecordDiff> filteredRecords = new ArrayList<>();
                    if (td.getRecordDiffs() != null) {
                        for (RecordDiff rd : td.getRecordDiffs()) {
                            String compositeKey = td.getTableName() + "|" + rd.getRecordBusinessKey();
                            if (!skipKeys.contains(compositeKey)) {
                                filteredRecords.add(rd);
                            }
                        }
                    }
                    filteredTables.add(TableDiff.builder()
                        .tableName(td.getTableName())
                        .dependencyLevel(td.getDependencyLevel())
                        .diffType(td.getDiffType())
                        .counts(td.getCounts())
                        .recordDiffs(filteredRecords)
                        .build());
                }
            }

            result.add(BusinessDiff.builder()
                .businessType(bd.getBusinessType())
                .businessTable(bd.getBusinessTable())
                .businessKey(bd.getBusinessKey())
                .businessName(bd.getBusinessName())
                .diffType(bd.getDiffType())
                .statistics(bd.getStatistics())
                .tableDiffs(filteredTables)
                .build());
        }
        return result;
    }

    /**
     * 执行 Apply。
     *
     * <p>
     * <b>事务策略：</b>整个执行流程包在声明式事务内，保证 CAS/record/snapshot/executor SQL 的原子性。
     * 失败时全部回滚，系统状态一致（session 恢复为 SUCCESS，无孤立 record）。
     * </p>
     * <p>
     * <b>已知限制：</b>失败时 apply_record 随事务回滚，审计轨迹仅保留在日志中。
     * 后续可通过 REQUIRES_NEW 审计服务或 TransactionalEventListener 解决。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantDiffApplyExecuteResponse execute(ApplyPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        if (plan.getSessionId() == null) {
            throw new IllegalArgumentException("plan.sessionId is null");
        }
        if (plan.getDirection() == null) {
            throw new IllegalArgumentException("plan.direction is null");
        }

        String selectionMode = plan.getOptions() != null && plan.getOptions().getSelectionMode() != null
            ? plan.getOptions().getSelectionMode().name()
            : "N/A";
        String clientRequestId = plan.getOptions() == null ? null : plan.getOptions().getClientRequestId();
        int actionCount = plan.getActions() == null ? 0 : plan.getActions().size();
        log.info("Apply execute: sessionId={}, direction={}, selectionMode={}, actionCount={}, clientRequestId={}",
            plan.getSessionId(), plan.getDirection(), selectionMode, actionCount, clientRequestId);

        MDC.put("sessionId", String.valueOf(plan.getSessionId()));
        ApplyExecutionContext context = new ApplyExecutionContext();
        try {
            return doExecute(plan, context);
        } catch (Exception e) {
            markApplyFailed(context, e);
            throw e;
        } finally {
            releaseLeaseQuietly(context);
            MDC.remove("sessionId");
            MDC.remove("applyId");
        }
    }

    private TenantDiffApplyExecuteResponse doExecute(ApplyPlan plan, ApplyExecutionContext context) {
        TenantDiffSessionPo session = sessionMapper.selectById(plan.getSessionId());
        if (session == null) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
        }

        context.stage = "SESSION_READY_CHECK";
        if (!SessionStatus.SUCCESS.name().equals(session.getStatus())) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_READY);
        }

        context.targetTenantId = resolveTargetTenantId(session, plan.getDirection());
        context.targetDataSourceKey = normalizeDataSourceKey(resolveTargetDataSourceKey(session, plan.getDirection()));
        validateCompareFreshness(session);

        // CAS: SUCCESS -> APPLYING
        context.stage = "SESSION_CAS";
        TenantDiffSessionPo statusUpdate = new TenantDiffSessionPo();
        statusUpdate.setId(session.getId());
        statusUpdate.setVersion(session.getVersion());
        statusUpdate.setStatus(SessionStatus.APPLYING.name());
        int updated = sessionMapper.updateById(statusUpdate);
        if (updated == 0) {
            throw new TenantDiffException(ErrorCode.APPLY_CONCURRENT_CONFLICT);
        }

        Long existingApply = applyRecordMapper.selectCount(
            new LambdaQueryWrapper<TenantDiffApplyRecordPo>()
                .eq(TenantDiffApplyRecordPo::getSessionId, plan.getSessionId())
                .in(TenantDiffApplyRecordPo::getStatus,
                    ApplyRecordStatus.SUCCESS.name(),
                    ApplyRecordStatus.ROLLED_BACK.name())
        );
        if (existingApply > 0) {
            restoreSessionStatus(session.getId(), SessionStatus.SUCCESS);
            throw new TenantDiffException(ErrorCode.SESSION_ALREADY_APPLIED);
        }

        context.stage = "LEASE_ACQUIRE";
        TenantDiffApplyLeasePo lease = applyLeaseService.acquire(
            context.targetTenantId,
            context.targetDataSourceKey,
            session.getId(),
            targetLeaseTtl
        );
        context.leaseToken = lease.getLeaseToken();

        context.stage = "AUDIT_CREATE";
        String planJson = toJsonRequired(plan);
        LocalDateTime startedAt = LocalDateTime.now();
        context.startedAt = startedAt;

        TenantDiffApplyRecordPo record = applyAuditService.createRunningRecord(TenantDiffApplyRecordPo.builder()
            .applyKey(UUID.randomUUID().toString().replace("-", ""))
            .sessionId(plan.getSessionId())
            .targetTenantId(context.targetTenantId)
            .targetDataSourceKey(context.targetDataSourceKey)
            .direction(plan.getDirection().name())
            .planJson(planJson)
            .status(ApplyRecordStatus.RUNNING.name())
            .errorMsg(null)
            .startedAt(startedAt)
            .finishedAt(null)
            .build());
        context.applyId = record.getId();
        MDC.put("applyId", String.valueOf(record.getId()));
        log.info("开始执行 Apply: applyId={}, sessionId={}, actions={}",
            record.getId(), plan.getSessionId(),
            plan.getActions() == null ? 0 : plan.getActions().size());

        context.stage = "SNAPSHOT_BUILD";
        List<TenantDiffSnapshotPo> snapshots = snapshotBuilder.buildTargetSnapshots(record.getId(), session, plan);
        for (TenantDiffSnapshotPo snapshot : snapshots) {
            if (snapshot != null) {
                snapshotMapper.insert(snapshot);
            }
        }

        context.stage = "EXECUTE";
        ApplyResult result = applyExecutor.execute(plan, ApplyMode.EXECUTE);

        ApplyRecordStatus finalStatus = result != null && result.isSuccess() ? ApplyRecordStatus.SUCCESS : ApplyRecordStatus.FAILED;
        String errorMsg = finalStatus == ApplyRecordStatus.FAILED ? (result == null ? "apply failed" : result.getMessage()) : null;
        LocalDateTime finishedAt = LocalDateTime.now();
        String diagnosticsJson = buildApplyValidationSummary(session, plan, snapshots, context.targetTenantId);

        updateApplyRecord(record.getId(), finalStatus, errorMsg, diagnosticsJson, finishedAt);
        restoreSessionStatus(session.getId(), SessionStatus.SUCCESS);
        log.info("Apply 执行完成: applyId={}, status={}, affectedRows={}",
            record.getId(), finalStatus,
            result != null && result.getAffectedRows() != null ? result.getAffectedRows() : 0);

        return TenantDiffApplyExecuteResponse.builder()
            .applyId(record.getId())
            .sessionId(plan.getSessionId())
            .direction(plan.getDirection())
            .status(finalStatus)
            .errorMsg(errorMsg)
            .startedAt(startedAt)
            .finishedAt(finishedAt)
            .applyResult(result)
            .build();
    }

    private void restoreSessionStatus(Long sessionId, SessionStatus status) {
        TenantDiffSessionPo restore = new TenantDiffSessionPo();
        restore.setId(sessionId);
        restore.setStatus(status.name());
        sessionMapper.updateById(restore);
    }

    private void updateApplyRecord(Long applyId, ApplyRecordStatus status, String errorMsg,
                                   String diagnosticsJson, LocalDateTime finishedAt) {
        TenantDiffApplyRecordPo update = new TenantDiffApplyRecordPo();
        update.setId(applyId);
        update.setStatus(status == null ? null : status.name());
        update.setErrorMsg(errorMsg);
        update.setDiagnosticsJson(diagnosticsJson);
        update.setFinishedAt(finishedAt);
        applyRecordMapper.updateById(update);
    }

    private void markApplyFailed(ApplyExecutionContext context, Exception error) {
        if (context == null || context.applyId == null) {
            return;
        }
        try {
            applyAuditService.markFailed(
                context.applyId,
                buildErrorMsg(error),
                context.stage,
                resolveFailureActionId(error),
                buildDiagnosticsJson(context, error),
                LocalDateTime.now()
            );
        } catch (Exception auditError) {
            log.error("更新 Apply 失败审计记录失败: applyId={}", context.applyId, auditError);
        }
    }

    private String buildDiagnosticsJson(ApplyExecutionContext context, Exception error) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("failureStage", context == null ? null : context.stage);
        diagnostics.put("targetTenantId", context == null ? null : context.targetTenantId);
        diagnostics.put("targetDataSourceKey", context == null ? null : context.targetDataSourceKey);
        diagnostics.put("errorType", error == null ? null : error.getClass().getSimpleName());
        diagnostics.put("errorMessage", error == null ? null : error.getMessage());
        if (error instanceof ApplyExecutionException applyExecutionException
            && applyExecutionException.getPartialResult() != null) {
            ApplyResult partialResult = applyExecutionException.getPartialResult();
            diagnostics.put("partialAffectedRows", partialResult.getAffectedRows());
            diagnostics.put("partialErrors", partialResult.getErrors());
        }
        return toJsonOrNull(diagnostics);
    }

    private String resolveFailureActionId(Exception error) {
        if (!(error instanceof ApplyExecutionException applyExecutionException)) {
            return null;
        }
        ApplyResult partialResult = applyExecutionException.getPartialResult();
        if (partialResult == null || partialResult.getActionErrors() == null || partialResult.getActionErrors().isEmpty()) {
            return null;
        }
        for (int i = partialResult.getActionErrors().size() - 1; i >= 0; i--) {
            ApplyActionError actionError = partialResult.getActionErrors().get(i);
            if (actionError == null || !actionError.isFatal()) {
                continue;
            }
            if (actionError.getBusinessType() == null || actionError.getBusinessType().isBlank()
                || actionError.getBusinessKey() == null || actionError.getBusinessKey().isBlank()
                || actionError.getTableName() == null || actionError.getTableName().isBlank()
                || actionError.getRecordBusinessKey() == null || actionError.getRecordBusinessKey().isBlank()) {
                return null;
            }
            return ApplyAction.computeActionId(
                actionError.getBusinessType(),
                actionError.getBusinessKey(),
                actionError.getTableName(),
                actionError.getRecordBusinessKey()
            );
        }
        return null;
    }

    private String issuePreviewToken(Long sessionId, ApplyDirection direction, List<ApplyAction> actions) {
        String baseToken = PlanBuilder.computePreviewToken(sessionId, direction, actions);
        String hash = extractPreviewHash(baseToken);
        return "pt_v2_" + Instant.now().getEpochSecond() + "_" + hash;
    }

    private void validatePreviewTokenTtl(ApplyOptions options) {
        if (options == null) {
            return;
        }
        SelectionMode selectionMode = options.getSelectionMode() == null ? SelectionMode.ALL : options.getSelectionMode();
        if (selectionMode != SelectionMode.PARTIAL) {
            return;
        }
        if (previewTokenTtl == null || previewTokenTtl.isZero() || previewTokenTtl.isNegative()) {
            return;
        }
        String previewToken = options.getPreviewToken();
        if (previewToken == null || previewToken.isBlank() || !previewToken.startsWith("pt_v2_")) {
            return;
        }
        String[] parts = previewToken.split("_", 4);
        if (parts.length != 4) {
            throw new TenantDiffException(ErrorCode.PARAM_INVALID, "invalid previewToken format");
        }
        long issuedAtEpochSeconds;
        try {
            issuedAtEpochSeconds = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new TenantDiffException(ErrorCode.PARAM_INVALID, "invalid previewToken timestamp");
        }
        long ageSeconds = Instant.now().getEpochSecond() - issuedAtEpochSeconds;
        if (ageSeconds > previewTokenTtl.getSeconds()) {
            throw new TenantDiffException(ErrorCode.PREVIEW_TOKEN_EXPIRED);
        }
    }

    private Long resolveTargetTenantId(TenantDiffSessionPo session, ApplyDirection direction) {
        if (session == null) {
            throw new IllegalArgumentException("session is null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is null");
        }
        if (session.getSourceTenantId() == null || session.getTargetTenantId() == null) {
            throw new IllegalArgumentException("session tenant ids are null");
        }
        return direction == ApplyDirection.A_TO_B ? session.getTargetTenantId() : session.getSourceTenantId();
    }

    private String resolveTargetDataSourceKey(TenantDiffSessionPo session, ApplyDirection direction) {
        if (session == null || session.getOptionsJson() == null || session.getOptionsJson().isBlank()) {
            return null;
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(session.getOptionsJson(), DiffSessionOptions.class);
            return StandaloneLoadOptionsResolver.resolveForDirection(options, direction).getDataSourceKey();
        } catch (Exception e) {
            throw new IllegalStateException(
                "无法解析 session optionsJson 中的 dataSourceKey, sessionId=" + session.getId()
                    + ", direction=" + direction, e);
        }
    }

    private String normalizeDataSourceKey(String dataSourceKey) {
        return dataSourceKey == null || dataSourceKey.isBlank()
            ? DiffDataSourceRegistry.PRIMARY_KEY
            : dataSourceKey.trim();
    }

    private String extractPreviewHash(String previewToken) {
        if (previewToken == null || previewToken.isBlank()) {
            return previewToken;
        }
        if (previewToken.startsWith("pt_v1_")) {
            return previewToken.substring("pt_v1_".length());
        }
        if (previewToken.startsWith("pt_v2_")) {
            String[] parts = previewToken.split("_", 4);
            return parts.length == 4 ? parts[3] : previewToken;
        }
        return previewToken;
    }

    private String buildErrorMsg(Exception error) {
        if (error == null) {
            return "apply failed";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void validateCompareFreshness(TenantDiffSessionPo session) {
        if (session == null || maxCompareAge == null || maxCompareAge.isZero() || maxCompareAge.isNegative()) {
            return;
        }
        if (session.getFinishedAt() == null) {
            throw new TenantDiffException(ErrorCode.APPLY_COMPARE_TOO_OLD);
        }
        Duration age = Duration.between(session.getFinishedAt(), LocalDateTime.now());
        if (age.compareTo(maxCompareAge) > 0) {
            throw new TenantDiffException(
                ErrorCode.APPLY_COMPARE_TOO_OLD,
                "compare result age(" + age + ") exceeds maxCompareAge(" + maxCompareAge + ")");
        }
    }

    private void releaseLeaseQuietly(ApplyExecutionContext context) {
        if (context == null || context.leaseToken == null || context.leaseToken.isBlank()) {
            return;
        }
        try {
            applyLeaseService.release(context.leaseToken);
        } catch (Exception e) {
            log.error("释放 Apply 目标租约失败: leaseToken={}", context.leaseToken, e);
        }
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON serialize failed: " + e.getMessage(), e);
        }
    }

    private String buildApplyValidationSummary(TenantDiffSessionPo session,
                                               ApplyPlan plan,
                                               List<TenantDiffSnapshotPo> beforeSnapshots,
                                               Long targetTenantId) {
        if (session == null || plan == null || beforeSnapshots == null || beforeSnapshots.isEmpty()) {
            return null;
        }
        List<BusinessData> beforeModels = parseSnapshotModels(beforeSnapshots);
        List<TenantDiffSnapshotPo> afterSnapshots = snapshotBuilder.buildTargetSnapshots(-1L, session, plan);
        List<BusinessData> afterModels = parseSnapshotModels(afterSnapshots);
        Map<String, BusinessDiff> originalDiffs = loadBusinessDiffs(plan.getSessionId());
        List<BusinessData> expectedAfterModels = buildExpectedPostApplyModels(
            beforeModels,
            plan,
            originalDiffs,
            targetTenantId,
            afterModels
        );
        DiffRules diffRules = resolveValidationDiffRules(session);
        TenantDiffEngine.CompareResult compareResult = diffEngine.compare(
            normalizeBusinessModelsForComparison(expectedAfterModels),
            normalizeBusinessModelsForComparison(afterModels),
            diffRules
        );
        ApplyPlan remainingPlan = planBuilder.build(
            plan.getSessionId(),
            ApplyDirection.A_TO_B,
            ApplyOptions.builder().mode(ApplyMode.DRY_RUN).allowDelete(true).build(),
            compareResult.businessDiffs()
        );
        int remainingDiffCount = remainingPlan.getActions() == null ? 0 : remainingPlan.getActions().size();
        ApplyValidationSummary summary = ApplyValidationSummary.builder()
            .success(remainingDiffCount == 0)
            .beforeBusinessCount(beforeModels.size())
            .afterBusinessCount(afterModels.size())
            .plannedActionCount(plan.getActions() == null ? 0 : plan.getActions().size())
            .remainingDiffCount(remainingDiffCount)
            .firstRemainingActionId(remainingDiffCount == 0 ? null : remainingPlan.getActions().get(0).getActionId())
            .summary(remainingDiffCount == 0 ? "APPLY_VALIDATED" : "APPLY_REMAINING_DIFFS")
            .build();
        if (!summary.isSuccess()) {
            log.warn("Apply validation detected remaining diffs: sessionId={}, firstActionId={}",
                plan.getSessionId(), summary.getFirstRemainingActionId());
        }
        return toJsonOrNull(summary);
    }

    private DiffRules resolveValidationDiffRules(TenantDiffSessionPo session) {
        if (session == null || session.getOptionsJson() == null || session.getOptionsJson().isBlank()) {
            return DiffRules.defaults();
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(session.getOptionsJson(), DiffSessionOptions.class);
            return options.getDiffRules() == null ? DiffRules.defaults() : options.getDiffRules();
        } catch (Exception e) {
            throw new IllegalArgumentException("optionsJson parse failed for validation diffRules: " + e.getMessage(), e);
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

    private Map<String, BusinessDiff> loadBusinessDiffs(Long sessionId) {
        List<TenantDiffResultPo> resultPos = resultMapper.selectList(
            new LambdaQueryWrapper<TenantDiffResultPo>()
                .eq(TenantDiffResultPo::getSessionId, sessionId)
        );
        List<BusinessDiff> diffs = new ArrayList<>();
        if (resultPos != null) {
            for (TenantDiffResultPo po : resultPos) {
                if (po == null || po.getDiffJson() == null || po.getDiffJson().isBlank()) {
                    continue;
                }
                try {
                    diffs.add(objectMapper.readValue(po.getDiffJson(), BusinessDiff.class));
                } catch (Exception e) {
                    throw new IllegalStateException("diff_json deserialize failed: resultId=" + po.getId(), e);
                }
            }
        }
        if (decisionRecordService != null) {
            diffs = applyDecisionFilter(sessionId, diffs);
        }
        Map<String, BusinessDiff> diffMap = new LinkedHashMap<>();
        for (BusinessDiff diff : diffs) {
            if (diff == null) {
                continue;
            }
            diffMap.put(diff.getBusinessType() + "|" + diff.getBusinessKey(), diff);
        }
        return diffMap;
    }

    private List<BusinessData> buildExpectedPostApplyModels(List<BusinessData> beforeModels,
                                                            ApplyPlan plan,
                                                            Map<String, BusinessDiff> originalDiffs,
                                                            Long targetTenantId,
                                                            List<BusinessData> actualAfterModels) {
        Map<String, BusinessData> businessMap = cloneBusinessModels(beforeModels);
        Map<String, Long> actualRecordIds = indexActualRecordIds(actualAfterModels);
        List<ApplyAction> orderedActions = new ArrayList<>(plan.getActions() == null ? List.of() : plan.getActions());
        orderedActions.sort(Comparator
            .comparing((ApplyAction action) -> action.getDiffType() == DiffType.DELETE ? 1 : 0)
            .thenComparing(action -> action.getDependencyLevel() == null ? Integer.MAX_VALUE : action.getDependencyLevel())
            .thenComparing(ApplyAction::getTableName, Comparator.nullsLast(String::compareTo))
            .thenComparing(ApplyAction::getRecordBusinessKey, Comparator.nullsLast(String::compareTo)));
        for (ApplyAction action : orderedActions) {
            if (action == null) {
                continue;
            }
            BusinessDiff businessDiff = originalDiffs.get(action.getBusinessType() + "|" + action.getBusinessKey());
            if (businessDiff == null) {
                continue;
            }
            RecordDiff recordDiff = findRecordDiff(businessDiff, action.getTableName(), action.getRecordBusinessKey());
            Map<String, Object> desiredFields = expectedFieldsForAction(
                action.getBusinessType(),
                action,
                desiredFieldsForAction(plan.getDirection(), recordDiff),
                targetTenantId,
                actualRecordIds
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

    private Map<String, BusinessData> cloneBusinessModels(List<BusinessData> models) {
        Map<String, BusinessData> businessMap = new LinkedHashMap<>();
        if (models == null) {
            return businessMap;
        }
        for (BusinessData model : models) {
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
                for (com.diff.core.domain.model.RecordData record : table.getRecords()) {
                    if (record == null || record.getFields() == null) {
                        continue;
                    }
                    Map<String, Object> normalizedFields = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : record.getFields().entrySet()) {
                        normalizedFields.put(entry.getKey(), normalizeComparableValue(entry.getValue()));
                    }
                    record.setFields(normalizedFields);
                    record.setFingerprint(null);
                }
            }
        }
        return new ArrayList<>(normalized.values());
    }

    private Object normalizeComparableValue(Object value) {
        if (value instanceof java.math.BigDecimal bigDecimal) {
            return bigDecimal.stripTrailingZeros();
        }
        if (value instanceof Double || value instanceof Float) {
            return java.math.BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros();
        }
        return value;
    }

    private Map<String, Object> desiredFieldsForAction(ApplyDirection direction, RecordDiff recordDiff) {
        Map<String, Object> sourceFields = direction == ApplyDirection.A_TO_B
            ? recordDiff.getSourceFields()
            : recordDiff.getTargetFields();
        return sourceFields == null ? Map.of() : new LinkedHashMap<>(sourceFields);
    }

    private Map<String, Object> expectedFieldsForAction(String businessType,
                                                        ApplyAction action,
                                                        Map<String, Object> desiredFields,
                                                        Long targetTenantId,
                                                        Map<String, Long> actualRecordIds) {
        Map<String, Object> expectedFields = new LinkedHashMap<>(desiredFields == null ? Map.of() : desiredFields);
        if (targetTenantId != null) {
            expectedFields.put("tenantsid", targetTenantId);
        }

        Long actualId = actualRecordIds.get(recordIdKey(action.getTableName(), action.getRecordBusinessKey()));
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
        Long parentActualId = actualRecordIds.get(recordIdKey(relation.getParentTable(), parentBusinessKey));
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
                              BusinessDiff businessDiff,
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

    private RecordDiff findRecordDiff(BusinessDiff businessDiff, String tableName, String recordBusinessKey) {
        if (businessDiff == null || businessDiff.getTableDiffs() == null) {
            throw new IllegalArgumentException("businessDiff.tableDiffs is empty");
        }
        for (TableDiff tableDiff : businessDiff.getTableDiffs()) {
            if (tableDiff == null || tableDiff.getRecordDiffs() == null || !tableName.equals(tableDiff.getTableName())) {
                continue;
            }
            for (RecordDiff recordDiff : tableDiff.getRecordDiffs()) {
                if (recordDiff != null && recordBusinessKey.equals(recordDiff.getRecordBusinessKey())) {
                    return recordDiff;
                }
            }
        }
        throw new IllegalArgumentException(
            "record diff not found: tableName=" + tableName + ", recordBusinessKey=" + recordBusinessKey);
    }

    private String toJsonRequired(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("value is null");
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON serialize failed: " + e.getMessage(), e);
        }
    }

    private static final class ApplyExecutionContext {
        private Long applyId;
        private Long targetTenantId;
        private String targetDataSourceKey;
        private String leaseToken;
        private String stage;
        private LocalDateTime startedAt;
    }

    @lombok.Data
    @lombok.Builder
    private static final class ApplyValidationSummary {
        private boolean success;
        private int beforeBusinessCount;
        private int afterBusinessCount;
        private int plannedActionCount;
        private int remainingDiffCount;
        private String firstRemainingActionId;
        private String summary;
    }

}
