package com.diff.standalone.apply;

import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.apply.ApplyActionError;
import com.diff.core.apply.IdMapping;
import com.diff.core.domain.exception.ApplyExecutionException;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.diff.RecordDiff;
import com.diff.core.domain.diff.TableDiff;
import com.diff.core.spi.apply.BusinessApplySupport;
import com.diff.core.util.TypeConversionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standalone Apply 执行核心：统一执行流程与 SQL 写入逻辑。
 *
 * <p>
 * <b>设计动机（WHY 独立于 SessionBasedApplyExecutor）</b>：本类抽取 Apply 的核心执行逻辑，
 * 供 {@link SessionBasedApplyExecutor}（依赖结果表持久化）与 {@link InMemoryApplyExecutor}（依赖内存 diff）
 * 共同复用。两者仅在 diff 加载来源与事务边界上不同，执行顺序、字段转换、IdMapping 维护等逻辑完全一致，
 * 因此抽成独立核心类避免重复实现。
 * </p>
 *
 * <p>
 * 核心职责：
 * <ul>
 *     <li>按依赖层级排序执行动作，保证父子表写入顺序正确（INSERT 先父后子，DELETE 先子后父）</li>
 *     <li>调用 {@link BusinessApplySupport} 扩展点进行字段转换（外键替换、清洗等）</li>
 *     <li>维护 {@link IdMapping} 用于记录新插入记录的 ID，供后续子表外键替换使用</li>
 *     <li>收集执行警告与错误，支持部分失败时的错误追踪</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class ApplyExecutorCore {

    private final BusinessApplySupportRegistry supportRegistry;

    public ApplyExecutorCore(BusinessApplySupportRegistry supportRegistry) {
        this.supportRegistry = supportRegistry;
    }

    /**
     * 执行 Apply 计划。
     *
     * @param plan Apply 计划
     * @param mode 执行模式
     * @param targetTenantId 目标租户 ID
     * @param loader 业务 diff 加载器
     * @param targetJdbc 目标数据源的 JdbcTemplate
     * @return 执行结果
     * @throws ApplyExecutionException 执行过程中出错时抛出，携带部分结果
     */
    public ApplyResult execute(ApplyPlan plan, ApplyMode mode, Long targetTenantId, BusinessDiffLoader loader, JdbcTemplate targetJdbc) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }

        ApplyMode effectiveMode = resolveEffectiveMode(plan, mode);
        ApplyOptions options = resolveOptions(plan);
        int estimated = estimateActions(plan);
        validateAffectedRowsThreshold(options, estimated);

        if (effectiveMode == ApplyMode.DRY_RUN) {
            validateDeleteSafety(plan, options);
            return ApplyResult.builder()
                .success(true)
                .message("DRY_RUN")
                .affectedRows(0)
                .estimatedAffectedRows(estimated)
                .idMapping(new IdMapping())
                .build();
        }

        if (plan.getDirection() == null) {
            throw new IllegalArgumentException("plan.direction is null");
        }
        if (targetTenantId == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("diff loader is null");
        }

        try {
            return doExecute(plan, options, targetTenantId, loader, targetJdbc);
        } catch (ApplyExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplyExecutionException(buildErrorMessage(e), e, failResult(e));
        }
    }

    static ApplyMode resolveEffectiveMode(ApplyPlan plan, ApplyMode mode) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        ApplyMode effectiveMode = mode != null ? mode : (plan.getOptions() == null ? null : plan.getOptions().getMode());
        return effectiveMode == null ? ApplyMode.DRY_RUN : effectiveMode;
    }

    private static ApplyOptions resolveOptions(ApplyPlan plan) {
        return plan.getOptions() == null ? ApplyOptions.builder().build() : plan.getOptions();
    }

    private static int estimateActions(ApplyPlan plan) {
        return plan.getActions() == null ? 0 : plan.getActions().size();
    }

    private static void validateAffectedRowsThreshold(ApplyOptions options, int estimatedAffectedRows) {
        int maxAffectedRows = options == null ? 0 : options.getMaxAffectedRows();
        if (maxAffectedRows > 0 && estimatedAffectedRows > maxAffectedRows) {
            throw new IllegalStateException(
                "estimatedAffectedRows(" + estimatedAffectedRows + ") exceeds maxAffectedRows(" + maxAffectedRows + ")");
        }
    }

    /**
     * 执行 Apply 计划的核心逻辑。
     *
     * <p>执行顺序策略：
     * <ol>
     *     <li>INSERT/UPDATE 按依赖层级升序执行（先主表后子表），保证外键引用有效</li>
     *     <li>DELETE 按依赖层级降序执行（先子表后主表），避免外键约束冲突</li>
     * </ol>
     * </p>
     */
    private ApplyResult doExecute(ApplyPlan plan, ApplyOptions options, Long targetTenantId, BusinessDiffLoader loader, JdbcTemplate targetJdbc) {
        // 安全检查：确保 DELETE 操作在 allowDelete=false 时不会被执行
        validateDeleteSafety(plan, options);

        ApplyDirection direction = plan.getDirection();
        // 按执行顺序重排动作列表：INSERT/UPDATE 优先于 DELETE，且按依赖层级排序
        List<ApplyAction> ordered = enforceExecutionOrder(plan.getActions());

        IdMapping idMapping = new IdMapping();
        int affectedRows = 0;
        List<String> errors = new ArrayList<>();
        List<ApplyActionError> actionErrors = new ArrayList<>();

        for (ApplyAction action : ordered) {
            if (action == null || action.getDiffType() == null) {
                continue;
            }
            DiffType op = action.getDiffType();
            if (op != DiffType.INSERT && op != DiffType.UPDATE && op != DiffType.DELETE) {
                continue;
            }
            String actionHint = actionHint(action);
            try {
                BusinessDiff detail = loader.load(action);
                RecordDiff recordDiff = findRecordDiff(detail, action.getTableName(), action.getRecordBusinessKey());

                Map<String, Object> sourceFields = (direction == ApplyDirection.A_TO_B)
                    ? recordDiff.getSourceFields()
                    : recordDiff.getTargetFields();
                Map<String, Object> targetFields = (direction == ApplyDirection.A_TO_B)
                    ? recordDiff.getTargetFields()
                    : recordDiff.getSourceFields();

                if (op == DiffType.INSERT) {
                    Map<String, Object> effectiveFields = applyTransformForInsert(action, sourceFields, targetTenantId, idMapping);
                    StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildInsert(action.getTableName(), targetTenantId, effectiveFields);
                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    int rows = targetJdbc.update(connection -> {
                        PreparedStatement ps = connection.prepareStatement(sql.sql(), Statement.RETURN_GENERATED_KEYS);
                        Object[] sqlArgs = sql.args();
                        for (int i = 0; i < sqlArgs.length; i++) {
                            ps.setObject(i + 1, sqlArgs[i]);
                        }
                        return ps;
                    }, keyHolder);
                    if (rows <= 0) {
                        String hint = "INSERT affectedRows=0: table=" + Objects.toString(action.getTableName(), "")
                            + ", recordBusinessKey=" + Objects.toString(action.getRecordBusinessKey(), "");
                        throw new IllegalStateException(hint);
                    }
                    affectedRows += rows;
                    Long newId = extractGeneratedId(keyHolder);
                    if (newId == null) {
                        log.warn("INSERT KeyHolder 未返回自增 ID: table={}, recordBusinessKey={}",
                            action.getTableName(), action.getRecordBusinessKey());
                    }
                    idMapping.put(action.getTableName(), action.getRecordBusinessKey(), newId);
                } else if (op == DiffType.UPDATE) {
                    Long targetId = TypeConversionUtil.toLong(targetFields == null ? null : targetFields.get("id"));
                    Map<String, Object> effectiveFields = applyTransformForUpdate(action, sourceFields, targetTenantId, idMapping);
                    StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildUpdateById(action.getTableName(), targetTenantId, targetId, effectiveFields);
                    if (sql != null) {
                        int rows = targetJdbc.update(sql.sql(), sql.args());
                        if (rows <= 0) {
                            recordActionError(action, "UPDATE affectedRows=0", false, errors, actionErrors);
                            log.warn("执行 Apply 警告: {} {}", actionHint, "UPDATE 影响行数=0");
                        } else {
                            affectedRows += rows;
                        }
                    }
                } else if (op == DiffType.DELETE) {
                    if (!options.isAllowDelete()) {
                        throw new IllegalStateException("DELETE is not allowed (allowDelete=false)");
                    }
                    Long targetId = TypeConversionUtil.toLong(targetFields == null ? null : targetFields.get("id"));
                    StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildDeleteById(action.getTableName(), targetTenantId, targetId);
                    int rows = targetJdbc.update(sql.sql(), sql.args());
                    if (rows <= 0) {
                        recordActionError(action, "DELETE affectedRows=0", false, errors, actionErrors);
                        log.warn("执行 Apply 警告: {} {}", actionHint, "DELETE 影响行数=0");
                    } else {
                        affectedRows += rows;
                    }
                }
            } catch (Exception e) {
                String errorMsg = buildErrorMessage(e);
                recordActionError(action, errorMsg, true, errors, actionErrors);
                log.error("执行 Apply 动作失败: {}", actionHint, e);
                ApplyResult partialResult = buildResult(false, errorMsg, affectedRows, errors, actionErrors, idMapping);
                throw new ApplyExecutionException(errorMsg, e, partialResult);
            }
        }

        boolean hasWarnings = !actionErrors.isEmpty();
        String message = hasWarnings ? "EXECUTE_WITH_WARNINGS" : "EXECUTE";
        return buildResult(true, message, affectedRows, errors, actionErrors, idMapping);
    }

    private Map<String, Object> applyTransformForInsert(
        ApplyAction action,
        Map<String, Object> sourceFields,
        Long targetTenantId,
        IdMapping idMapping
    ) {
        BusinessApplySupport support = supportRegistry == null ? null : supportRegistry.get(action.getBusinessType());
        if (support == null) {
            return sourceFields;
        }
        Map<String, Object> transformed = support.transformForInsert(
            action.getTableName(),
            action.getRecordBusinessKey(),
            sourceFields,
            targetTenantId,
            idMapping
        );
        return transformed == null ? sourceFields : transformed;
    }

    private Map<String, Object> applyTransformForUpdate(
        ApplyAction action,
        Map<String, Object> sourceFields,
        Long targetTenantId,
        IdMapping idMapping
    ) {
        BusinessApplySupport support = supportRegistry == null ? null : supportRegistry.get(action.getBusinessType());
        if (support == null) {
            return sourceFields;
        }
        Map<String, Object> transformed = support.transformForUpdate(
            action.getTableName(),
            action.getRecordBusinessKey(),
            sourceFields,
            targetTenantId,
            idMapping
        );
        return transformed == null ? sourceFields : transformed;
    }

    private static void validateDeleteSafety(ApplyPlan plan, ApplyOptions options) {
        boolean allowDelete = options != null && options.isAllowDelete();
        if (allowDelete) {
            return;
        }
        if (plan.getActions() == null) {
            return;
        }
        for (ApplyAction action : plan.getActions()) {
            if (action == null) {
                continue;
            }
            if (action.getDiffType() == DiffType.DELETE) {
                throw new IllegalStateException("plan contains DELETE but allowDelete=false");
            }
        }
    }

    /**
     * 强制执行顺序排序。
     *
     * <p>排序规则：
     * <ul>
     *     <li>INSERT/UPDATE（upserts）优先执行，按依赖层级升序排列</li>
     *     <li>DELETE 后执行，按依赖层级降序排列（先删子表再删主表）</li>
     *     <li>相同层级内按 tableName + recordBusinessKey 字典序排列，保证结果稳定</li>
     * </ul>
     * </p>
     */
    private static List<ApplyAction> enforceExecutionOrder(List<ApplyAction> actions) {
        List<ApplyAction> input = actions == null ? List.of() : actions;

        // 分离 INSERT/UPDATE 与 DELETE，分别采用不同排序策略
        List<ApplyAction> upserts = new ArrayList<>();
        List<ApplyAction> deletes = new ArrayList<>();
        for (ApplyAction action : input) {
            if (action == null || action.getDiffType() == null) {
                continue;
            }
            if (action.getDiffType() == DiffType.DELETE) {
                deletes.add(action);
            } else {
                upserts.add(action);
            }
        }

        upserts.sort(Comparator
            .comparing((ApplyAction a) -> a.getDependencyLevel() == null ? Integer.MAX_VALUE : a.getDependencyLevel())
            .thenComparing(a -> Objects.toString(a.getTableName(), ""))
            .thenComparing(a -> Objects.toString(a.getRecordBusinessKey(), ""))
        );

        deletes.sort(Comparator
            .comparing((ApplyAction a) -> a.getDependencyLevel() == null ? Integer.MIN_VALUE : a.getDependencyLevel(), Comparator.reverseOrder())
            .thenComparing(a -> Objects.toString(a.getTableName(), ""))
            .thenComparing(a -> Objects.toString(a.getRecordBusinessKey(), ""))
        );

        List<ApplyAction> ordered = new ArrayList<>(upserts.size() + deletes.size());
        ordered.addAll(upserts);
        ordered.addAll(deletes);
        return ordered;
    }

    private static String actionHint(ApplyAction action) {
        if (action == null) {
            return "action=null";
        }
        return "action{businessType=" + Objects.toString(action.getBusinessType(), "")
            + ", businessKey=" + Objects.toString(action.getBusinessKey(), "")
            + ", table=" + Objects.toString(action.getTableName(), "")
            + ", recordBusinessKey=" + Objects.toString(action.getRecordBusinessKey(), "")
            + ", diffType=" + Objects.toString(action.getDiffType(), "")
            + ", dependencyLevel=" + Objects.toString(action.getDependencyLevel(), "") + "}";
    }

    private static void recordActionError(
        ApplyAction action,
        String message,
        boolean fatal,
        List<String> errors,
        List<ApplyActionError> actionErrors
    ) {
        String hint = actionHint(action) + " -> " + message;
        if (errors != null) {
            errors.add(hint);
        }
        if (actionErrors != null) {
            actionErrors.add(ApplyActionError.builder()
                .businessType(action == null ? null : action.getBusinessType())
                .businessKey(action == null ? null : action.getBusinessKey())
                .tableName(action == null ? null : action.getTableName())
                .recordBusinessKey(action == null ? null : action.getRecordBusinessKey())
                .diffType(action == null ? null : action.getDiffType())
                .dependencyLevel(action == null ? null : action.getDependencyLevel())
                .message(message)
                .fatal(fatal)
                .build());
        }
    }

    private static String buildErrorMessage(Exception e) {
        if (e == null) {
            return "execute failed";
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank()
            ? e.getClass().getSimpleName()
            : (e.getClass().getSimpleName() + ": " + msg);
    }

    private static ApplyResult buildResult(
        boolean success,
        String message,
        int affectedRows,
        List<String> errors,
        List<ApplyActionError> actionErrors,
        IdMapping idMapping
    ) {
        return ApplyResult.builder()
            .success(success)
            .message(message)
            .affectedRows(affectedRows)
            .estimatedAffectedRows(0)
            .errors(errors == null ? List.of() : errors)
            .actionErrors(actionErrors == null ? List.of() : actionErrors)
            .idMapping(idMapping == null ? new IdMapping() : idMapping)
            .build();
    }

    private static Long extractGeneratedId(KeyHolder keyHolder) {
        if (keyHolder == null) {
            return null;
        }
        try {
            Number generatedKey = keyHolder.getKey();
            if (generatedKey != null) {
                return generatedKey.longValue();
            }
        } catch (InvalidDataAccessApiUsageException ignored) {
            // 某些数据库会返回多列 generated keys，此时退化为从 key map 中提取 id。
        }

        Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : keys.entrySet()) {
            if (entry != null && entry.getKey() != null && "id".equalsIgnoreCase(entry.getKey())) {
                Long value = TypeConversionUtil.toLong(entry.getValue());
                if (value != null) {
                    return value;
                }
            }
        }
        for (Object value : keys.values()) {
            Long parsed = TypeConversionUtil.toLong(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static RecordDiff findRecordDiff(BusinessDiff businessDiff, String tableName, String recordBusinessKey) {
        if (businessDiff == null || businessDiff.getTableDiffs() == null) {
            throw new IllegalArgumentException("businessDiff.tableDiffs is empty");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is blank");
        }
        if (recordBusinessKey == null || recordBusinessKey.isBlank()) {
            throw new IllegalArgumentException("recordBusinessKey is blank");
        }

        for (TableDiff tableDiff : businessDiff.getTableDiffs()) {
            if (tableDiff == null || tableDiff.getTableName() == null) {
                continue;
            }
            if (!tableName.equals(tableDiff.getTableName())) {
                continue;
            }
            if (tableDiff.getRecordDiffs() == null) {
                continue;
            }
            for (RecordDiff recordDiff : tableDiff.getRecordDiffs()) {
                if (recordDiff == null) {
                    continue;
                }
                if (recordBusinessKey.equals(recordDiff.getRecordBusinessKey())) {
                    return recordDiff;
                }
            }
        }
        throw new IllegalArgumentException("record diff not found: table=" + tableName + ", recordBusinessKey=" + recordBusinessKey);
    }

    private static ApplyResult failResult(Exception e) {
        String msg = buildErrorMessage(e);
        return ApplyResult.builder()
            .success(false)
            .message(msg)
            .affectedRows(0)
            .estimatedAffectedRows(0)
            .errors(List.of(msg))
            .actionErrors(List.of())
            .idMapping(new IdMapping())
            .build();
    }
}
