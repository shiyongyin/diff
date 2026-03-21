package com.diff.standalone.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diff.core.apply.PlanBuilder;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyRecordStatus;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.apply.SelectionMode;
import com.diff.core.domain.diff.*;
import com.diff.standalone.web.dto.response.ApplyPreviewResponse;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.apply.StandaloneApplyExecutor;
import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.snapshot.StandaloneSnapshotBuilder;
import com.diff.standalone.persistence.entity.TenantDiffApplyRecordPo;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.entity.TenantDiffSnapshotPo;
import com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSnapshotMapper;
import com.diff.standalone.service.DecisionRecordService;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

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
    private final PlanBuilder planBuilder;
    private final ObjectMapper objectMapper;
    private final int previewActionLimit;

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
        PlanBuilder planBuilder,
        ObjectMapper objectMapper,
        int previewActionLimit,
        @Nullable DecisionRecordService decisionRecordService
    ) {
        this.applyRecordMapper = applyRecordMapper;
        this.snapshotMapper = snapshotMapper;
        this.sessionMapper = sessionMapper;
        this.resultMapper = resultMapper;
        this.snapshotBuilder = snapshotBuilder;
        this.applyExecutor = applyExecutor;
        this.planBuilder = planBuilder;
        this.objectMapper = objectMapper;
        this.previewActionLimit = previewActionLimit;
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

        String previewToken = PlanBuilder.computePreviewToken(sessionId, direction, plan.getActions());
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
        try {
            return doExecute(plan);
        } finally {
            MDC.remove("sessionId");
            MDC.remove("applyId");
        }
    }

    private TenantDiffApplyExecuteResponse doExecute(ApplyPlan plan) {
        TenantDiffSessionPo session = sessionMapper.selectById(plan.getSessionId());
        if (session == null) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
        }

        if (!SessionStatus.SUCCESS.name().equals(session.getStatus())) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_READY);
        }

        // CAS: SUCCESS -> APPLYING
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

        String planJson = toJsonRequired(plan);
        LocalDateTime startedAt = LocalDateTime.now();

        TenantDiffApplyRecordPo record = TenantDiffApplyRecordPo.builder()
            .applyKey(UUID.randomUUID().toString().replace("-", ""))
            .sessionId(plan.getSessionId())
            .direction(plan.getDirection().name())
            .planJson(planJson)
            .status(ApplyRecordStatus.RUNNING.name())
            .errorMsg(null)
            .startedAt(startedAt)
            .finishedAt(null)
            .build();
        applyRecordMapper.insert(record);
        if (record.getId() == null) {
            throw new IllegalStateException("insert apply_record failed: id is null");
        }
        MDC.put("applyId", String.valueOf(record.getId()));
        log.info("开始执行 Apply: applyId={}, sessionId={}, actions={}",
            record.getId(), plan.getSessionId(),
            plan.getActions() == null ? 0 : plan.getActions().size());

        List<TenantDiffSnapshotPo> snapshots = snapshotBuilder.buildTargetSnapshots(record.getId(), session, plan);
        for (TenantDiffSnapshotPo snapshot : snapshots) {
            if (snapshot != null) {
                snapshotMapper.insert(snapshot);
            }
        }

        ApplyResult result = applyExecutor.execute(plan, ApplyMode.EXECUTE);

        ApplyRecordStatus finalStatus = result != null && result.isSuccess() ? ApplyRecordStatus.SUCCESS : ApplyRecordStatus.FAILED;
        String errorMsg = finalStatus == ApplyRecordStatus.FAILED ? (result == null ? "apply failed" : result.getMessage()) : null;
        LocalDateTime finishedAt = LocalDateTime.now();

        updateApplyRecord(record.getId(), finalStatus, errorMsg, finishedAt);
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

    private void updateApplyRecord(Long applyId, ApplyRecordStatus status, String errorMsg, LocalDateTime finishedAt) {
        TenantDiffApplyRecordPo update = new TenantDiffApplyRecordPo();
        update.setId(applyId);
        update.setStatus(status == null ? null : status.name());
        update.setErrorMsg(errorMsg);
        update.setFinishedAt(finishedAt);
        applyRecordMapper.updateById(update);
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

}
