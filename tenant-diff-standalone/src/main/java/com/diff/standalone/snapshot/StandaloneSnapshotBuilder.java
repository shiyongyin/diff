package com.diff.standalone.snapshot;


import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.scope.TenantModelScope;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.diff.DiffSessionOptions;
import com.diff.standalone.model.StandaloneTenantModelBuilder;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.entity.TenantDiffSnapshotPo;
import com.diff.standalone.util.StandaloneLoadOptionsResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standalone 模式的快照构建器。
 *
 * <p>
 * <b>设计动机：</b>在 Apply 执行前对目标租户（TARGET）构建业务模型快照并落库，是实现回滚的前提——
 * 回滚时通过“快照 vs 当前”再次 diff 生成恢复计划，若无快照则无法回滚。因此快照必须在 Apply 前完成。
 * </p>
 *
 * <p>
 * 快照用于回滚：在执行 Apply 之前，先对目标租户在本次计划涉及的业务范围内构建“业务模型快照”，
 * 并以 JSON 形式落库保存（{@code xai_tenant_diff_snapshot}）。
 * </p>
 *
 * <p>
 * 说明：快照粒度为 BusinessData（业务级），而非逐条 SQL/记录级审计。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public class StandaloneSnapshotBuilder {
    public static final String SIDE_TARGET = "TARGET";

    private final StandaloneTenantModelBuilder modelBuilder;
    private final ObjectMapper objectMapper;

    /**
     * 构造快照构建器。
     *
     * @param modelBuilder 租户模型构建器
     * @param objectMapper JSON 序列化器
     */
    public StandaloneSnapshotBuilder(StandaloneTenantModelBuilder modelBuilder, ObjectMapper objectMapper) {
        this.modelBuilder = modelBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建本次 apply 的 TARGET 快照集合。
     *
     * <p>
     * 快照范围来自 {@link ApplyPlan} 中的 actions：仅对“实际涉及的 businessType/businessKey”构建模型，
     * 避免对目标租户做全量快照导致成本过高。
     * </p>
     *
     * @param applyId apply 记录 ID
     * @param session diff 会话（用于解析 target tenant 与 loadOptions）
     * @param plan    apply 计划（用于推导 scope）
     * @return 快照 PO 列表（调用方负责落库）
     * @throws IllegalArgumentException 若参数无效或 plan.sessionId 与 session.id 不一致
     */
    public List<TenantDiffSnapshotPo> buildTargetSnapshots(Long applyId, TenantDiffSessionPo session, ApplyPlan plan) {
        if (applyId == null) {
            throw new IllegalArgumentException("applyId is null");
        }
        if (session == null) {
            throw new IllegalArgumentException("session is null");
        }
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        if (plan.getSessionId() == null) {
            throw new IllegalArgumentException("plan.sessionId is null");
        }
        if (!Objects.equals(plan.getSessionId(), session.getId())) {
            throw new IllegalArgumentException("plan.sessionId != session.id");
        }

        // 如果 plan 中没有任何 action，无需构建快照
        if (plan.getActions() == null || plan.getActions().isEmpty()) {
            return List.of();
        }

        // 根据 Apply 方向确定目标租户 ID
        Long targetTenantId = resolveTargetTenantId(session, plan);
        // 从 Plan 的 actions 中提取涉及的业务范围，避免全量快照
        TenantModelScope scope = scopeFromPlan(plan);
        LoadOptions loadOptions = loadOptionsFromSession(session, plan.getDirection());

        // 构建目标租户的业务模型（这是 Apply 前的状态，用于后续回滚）
        StandaloneTenantModelBuilder.BuildResult target = modelBuilder.buildWithWarnings(targetTenantId, scope, loadOptions);

        LocalDateTime now = LocalDateTime.now();
        List<TenantDiffSnapshotPo> snapshots = new ArrayList<>();
        if (target.models() != null) {
            for (BusinessData model : target.models()) {
                if (model == null) {
                    continue;
                }
                snapshots.add(TenantDiffSnapshotPo.builder()
                    .applyId(applyId)
                    .sessionId(session.getId())
                    .side(SIDE_TARGET)
                    .businessType(model.getBusinessType())
                    .businessTable(model.getBusinessTable())
                    .businessKey(model.getBusinessKey())
                    .snapshotJson(toJsonRequired(model))
                    .createdAt(now)
                    .build());
            }
        }
        return snapshots;
    }

    private LoadOptions loadOptionsFromSession(TenantDiffSessionPo session, ApplyDirection direction) {
        if (session == null || session.getOptionsJson() == null || session.getOptionsJson().isBlank()) {
            return LoadOptions.builder().build();
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(session.getOptionsJson(), DiffSessionOptions.class);
            return StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
        } catch (Exception e) {
            throw new IllegalStateException(
                "optionsJson parse failed for snapshot loadOptions: sessionId=" + session.getId()
                    + ", direction=" + direction, e);
        }
    }

    private Long resolveTargetTenantId(TenantDiffSessionPo session, ApplyPlan plan) {
        if (session.getSourceTenantId() == null || session.getTargetTenantId() == null) {
            throw new IllegalArgumentException("session tenant ids are null");
        }
        if (plan.getDirection() == null) {
            throw new IllegalArgumentException("plan.direction is null");
        }
        return switch (plan.getDirection()) {
            case A_TO_B -> session.getTargetTenantId();
            case B_TO_A -> session.getSourceTenantId();
        };
    }

    private static TenantModelScope scopeFromPlan(ApplyPlan plan) {
        Map<String, LinkedHashSet<String>> keysByType = new LinkedHashMap<>();
        if (plan.getActions() != null) {
            plan.getActions().forEach(action -> {
                if (action == null) {
                    return;
                }
                String type = action.getBusinessType();
                String key = action.getBusinessKey();
                if (type == null || type.isBlank() || key == null || key.isBlank()) {
                    return;
                }
                keysByType.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(key);
            });
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
