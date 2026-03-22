package com.diff.core.apply;


import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.diff.RecordDiff;
import com.diff.core.domain.diff.TableDiff;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于 diff 结果构建可执行的 {@link ApplyPlan}（从"差异描述"到"执行指令"的转换器）。
 *
 * <p>
 * 对比引擎输出的 {@link BusinessDiff} 只描述"两侧有何不同"，尚不能直接执行。
 * PlanBuilder 负责将这些差异描述转换为有序的 {@link ApplyAction} 列表，
 * 并在转换过程中施加安全约束与过滤规则。
 * </p>
 *
 * <h3>为什么需要白名单过滤</h3>
 * <p>
 * 实际使用中，用户可能只想同步部分业务对象或部分操作类型（如只做 INSERT 不做 DELETE），
 * 通过 {@link ApplyOptions} 中的白名单实现精确控制，避免全量 apply 带来的风险。
 * </p>
 *
 * <h3>为什么 B_TO_A 需要反转 INSERT/DELETE</h3>
 * <p>
 * diff 引擎始终以"A 有 B 无 → INSERT，A 无 B 有 → DELETE"的视角输出。
 * 当用户选择 B_TO_A 方向同步时，语义需要反转：
 * 原来的 INSERT（A 有 B 无）意味着应从 A 删除，原来的 DELETE（A 无 B 有）意味着应往 A 插入。
 * </p>
 *
 * <h3>安全阈值</h3>
 * <p>
 * 使用 {@code actions.size()} 作为预估影响行数。在 {@link ApplyMode#EXECUTE} 模式下，
 * 若超过 {@link ApplyOptions#getMaxAffectedRows()} 则拒绝生成计划，防止误操作导致大规模数据变更。
 * </p>
 *
 * <h3>线程安全</h3>
 * <p>本类无状态，可安全并发调用。</p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyPlan
 * @see ApplyOptions
 */
public class PlanBuilder {

    /** 最大选择动作 ID 数量。 */
    private static final int MAX_SELECTED_ACTION_IDS = 5000;

    /** 最大动作 ID 长度。*/
    private static final int MAX_ACTION_ID_LENGTH = 512;

    /**
     * 从 diff 明细构建一份 apply 计划。
     *
     * <p>
     * 构建流程：遍历 diff → 白名单过滤 → 方向反转 → 依赖排序 → 阈值校验 → 生成统计。
     * </p>
     *
     * @param sessionId diff 会话 ID，不允许 {@code null}
     * @param direction 应用方向（A_TO_B / B_TO_A），不允许 {@code null}
     * @param options   apply 选项（允许 {@code null}，内部使用默认值）
     * @param diffs     diff 明细（允许 {@code null}，表示无动作）
     * @return apply 计划，包含排序后的动作列表与统计信息
     * @throws IllegalArgumentException 当 sessionId 或 direction 为 {@code null} 时
     * @throws TenantDiffException      当预估影响行数超过阈值或 selection 校验失败时
     */
    public ApplyPlan build(Long sessionId, ApplyDirection direction, ApplyOptions options, List<BusinessDiff> diffs) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is null");
        }

        // 获取有效的 options
        ApplyOptions effectiveOptions = options == null ? ApplyOptions.builder().build() : options;

        List<ApplyAction> actions = new ArrayList<>();

        // 获取不为空的所有 businessKey 集合
        Set<String> businessKeyAllow = toStringSet(effectiveOptions.getBusinessKeys());
        // 获取不为空的所有 businessType 集合
        Set<String> businessTypeAllow = toStringSet(effectiveOptions.getBusinessTypes());

        // 获取有效的 diffType
        Set<DiffType> diffTypeAllow = effectiveOptions.getDiffTypes() == null ? Set.of() : new HashSet<>(effectiveOptions.getDiffTypes());

        // 是否允许删除
        boolean allowDelete = effectiveOptions.isAllowDelete();

        if (diffs != null) {
            for (BusinessDiff businessDiff : diffs) {
                if (businessDiff == null) {
                    continue;
                }
                if (!businessTypeAllow.isEmpty() && !businessTypeAllow.contains(businessDiff.getBusinessType())) {
                    continue;
                }
                if (!businessKeyAllow.isEmpty() && !businessKeyAllow.contains(businessDiff.getBusinessKey())) {
                    continue;
                }

                if (businessDiff.getTableDiffs() == null) {
                    continue;
                }
                for (TableDiff tableDiff : businessDiff.getTableDiffs()) {
                    if (tableDiff == null) {
                        continue;
                    }
                    if (tableDiff.getRecordDiffs() == null) {
                        continue;
                    }
                    for (RecordDiff recordDiff : tableDiff.getRecordDiffs()) {
                        if (recordDiff == null) {
                            continue;
                        }
                        DiffType type = resolveOpType(direction, recordDiff.getDiffType());
                        if (type == null || type == DiffType.NOOP) {
                            continue;
                        }
                        if (!diffTypeAllow.isEmpty() && !diffTypeAllow.contains(type)) {
                            continue;
                        }
                        if (type == DiffType.DELETE && !allowDelete) {
                            continue;
                        }

                        actions.add(ApplyAction.builder()
                            .actionId(
                                ApplyAction.computeActionId(
                                businessDiff.getBusinessType(),
                                businessDiff.getBusinessKey(),
                                tableDiff.getTableName(),
                                recordDiff.getRecordBusinessKey())
                            )
                            .businessType(businessDiff.getBusinessType())
                            .businessKey(businessDiff.getBusinessKey())
                            .tableName(tableDiff.getTableName())
                            .dependencyLevel(tableDiff.getDependencyLevel())
                            .recordBusinessKey(recordDiff.getRecordBusinessKey())
                            .diffType(type)
                            .build());
                    }
                }
            }
        }

        // --- Selection 逻辑（新增） ---
        SelectionMode selMode = effectiveOptions.getSelectionMode() == null
            ? SelectionMode.ALL
            : effectiveOptions.getSelectionMode();

        if (selMode == SelectionMode.PARTIAL) {
            requireNonBlank(effectiveOptions.getPreviewToken(),
                "previewToken is required when selectionMode=PARTIAL");

            Set<String> selectedIds = normalizeSelectedIds(effectiveOptions.getSelectedActionIds());
            if (selectedIds.isEmpty()) {
                throw new TenantDiffException(ErrorCode.SELECTION_EMPTY);
            }

            validatePreviewToken(sessionId, direction, actions, effectiveOptions.getPreviewToken());
            validateSelectedIdsExist(selectedIds, actions);
            validateMainTableOnly(selectedIds, actions);

            actions = actions.stream()
                .filter(a -> a != null && selectedIds.contains(a.getActionId()))
                .collect(Collectors.toList());
        }

        actions.sort(actionComparator());

        // 阈值校验仅在 EXECUTE 模式生效；preview/DRY_RUN 不阻断。
        if (effectiveOptions.getMode() == ApplyMode.EXECUTE) {
            int estimated = actions.size();
            int max = effectiveOptions.getMaxAffectedRows();
            if (max > 0 && estimated > max) {
                throw new TenantDiffException(ErrorCode.APPLY_THRESHOLD_EXCEEDED,
                    "estimatedAffectedRows(" + estimated + ") exceeds maxAffectedRows(" + max + ")");
            }
        }

        ApplyStatistics statistics = buildStatistics(actions);

        return ApplyPlan.builder()
            .planId(UUID.randomUUID().toString().replace("-", ""))
            .sessionId(sessionId)
            .direction(direction)
            .options(effectiveOptions)
            .actions(actions)
            .statistics(statistics)
            .build();
    }

    /**
     * 计算 previewToken（用于 execute 时防陈旧校验）。
     *
     * <p>token 形态：{@code pt_v1_} + 32 字符 hex（固定长度 38）。</p>
     */
    public static String computePreviewToken(Long sessionId, ApplyDirection direction, List<ApplyAction> allActions) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is null");
        }

        List<String> sortedIds = allActions == null
            ? List.of()
            : allActions.stream()
            .filter(Objects::nonNull)
            .map(ApplyAction::getActionId)
            .filter(Objects::nonNull)
            .sorted()
            .collect(Collectors.toList());

        String canonical = sessionId + "|" + direction.name() + "|" + String.join(",", sortedIds);
        return "pt_v1_" + sha256Hex(canonical).substring(0, 32);
    }

    private static void validatePreviewToken(Long sessionId,
                                             ApplyDirection direction,
                                             List<ApplyAction> allActions,
                                             String actualToken) {
        String expectedToken = computePreviewToken(sessionId, direction, allActions);
        if (!extractPreviewHash(expectedToken).equals(extractPreviewHash(actualToken))) {
            throw new TenantDiffException(ErrorCode.SELECTION_STALE,
                "previewToken mismatch: diff data may have changed since last preview");
        }
    }

    private static String extractPreviewHash(String previewToken) {
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

    /**
     * 归一化选择的动作 ID。
     *
     * @param raw 原始动作 ID 集合
     * @return 归一化后的动作 ID 集合
     */
    private static Set<String> normalizeSelectedIds(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        // 校验动作 ID 数量
        if (raw.size() > MAX_SELECTED_ACTION_IDS) {
            throw new TenantDiffException(ErrorCode.PARAM_INVALID,
                "selectedActionIds count(" + raw.size()
                    + ") exceeds limit(" + MAX_SELECTED_ACTION_IDS + ")");
        }
        // 归一化动作 ID
        Set<String> result = new LinkedHashSet<>();
        for (String id : raw) {
            if (id == null || id.isBlank()) {
                continue;
            }
            // 校验动作 ID 格式
            if (!id.startsWith("v1:")) {
                throw new TenantDiffException(ErrorCode.SELECTION_INVALID_ID,
                    "invalid actionId format (missing v1: prefix): " + id);
            }
            // 校验动作 ID 长度
            if (id.length() > MAX_ACTION_ID_LENGTH) {
                throw new TenantDiffException(ErrorCode.SELECTION_INVALID_ID,
                    "actionId length exceeds " + MAX_ACTION_ID_LENGTH + ": " + id.length());
            }
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 验证选择的动作 ID 是否存在。
     *
     * @param selectedIds 选择的动作 ID 集合
     * @param allActions 所有动作列表
     */
    private static void validateSelectedIdsExist(Set<String> selectedIds, List<ApplyAction> allActions) {
        // 获取不为空的所有动作 ID
        Set<String> validIds = allActions == null
            ? Set.of()
            : allActions.stream()
            .filter(Objects::nonNull)
            .map(ApplyAction::getActionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        List<String> unknownIds = new ArrayList<>();
        for (String id : selectedIds) {
            if (!validIds.contains(id)) {
                unknownIds.add(id);
            }
        }
        if (!unknownIds.isEmpty()) {
            // 获取第一个未知动作 ID
            String first = unknownIds.get(0);
            // 构建错误消息
            String msg = "unknown actionIds (" + unknownIds.size() + "): " + first
                + (unknownIds.size() > 1 ? " and " + (unknownIds.size() - 1) + " more" : "");
            throw new TenantDiffException(ErrorCode.SELECTION_INVALID_ID, msg);
        }
    }

    /**
     * V1 安全限制：PARTIAL 仅允许选择主表（dependencyLevel=0）动作。
     */
    private static void validateMainTableOnly(Set<String> selectedIds, List<ApplyAction> allActions) {
        if (allActions == null || allActions.isEmpty()) {
            return;
        }
        for (ApplyAction action : allActions) {
            if (action == null || action.getActionId() == null) {
                continue;
            }
            if (!selectedIds.contains(action.getActionId())) {
                continue;
            }
            // 获取依赖层级
            int level = action.getDependencyLevel() == null ? 0 : action.getDependencyLevel();
            if (level > 0) {
                // 抛出异常 参数无效
                throw new TenantDiffException(ErrorCode.PARAM_INVALID,
                    "selectionMode=PARTIAL does not support sub-table actions (dependencyLevel="
                        + level + ", table=" + action.getTableName() + ")");
            }
        }
    }

    /**
     * 要求字符串不为空。
     *
     * @param value 字符串
     * @param message 错误消息
     */
    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new TenantDiffException(ErrorCode.PARAM_INVALID, message);
        }
    }

    /**
     * 计算 SHA-256 哈希值。
     *
     * @param input 输入字符串
     * @return SHA-256 哈希值
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 构建统计信息。
     *
     * @param actions 动作列表
     * @return 统计信息
     */
    private static ApplyStatistics buildStatistics(List<ApplyAction> actions) {
        int insertCount = 0;
        int updateCount = 0;
        int deleteCount = 0;
        // 统计动作数量
        if (actions != null) {
            for (ApplyAction action : actions) {
                if (action == null || action.getDiffType() == null) {
                    continue;
                }
                if (action.getDiffType() == DiffType.INSERT) {
                    insertCount++;
                } else if (action.getDiffType() == DiffType.UPDATE) {
                    updateCount++;
                } else if (action.getDiffType() == DiffType.DELETE) {
                    deleteCount++;
                }
            }
        }
        int total = actions == null ? 0 : actions.size();
        // 构建统计信息
        return ApplyStatistics.builder()
            .totalActions(total)
            .estimatedAffectedRows(total)
            .insertCount(insertCount)
            .updateCount(updateCount)
            .deleteCount(deleteCount)
            .build();
    }

    /**
     * 构建 ApplyAction 排序比较器。
     *
     * <p>排序策略说明：
     * <ol>
     *     <li>INSERT/UPDATE 优先于 DELETE：降低外键约束冲突概率</li>
     *     <li>INSERT/UPDATE 按依赖层级升序：先写主表再写子表（保证外键引用有效）</li>
     *     <li>DELETE 按依赖层级降序：先删子表再删主表（避免外键约束阻止删除）</li>
     *     <li>相同条件下按 businessType/businessKey/tableName/recordBusinessKey 字典序，保证执行顺序稳定可预测</li>
     * </ol>
     * </p>
     */
    private static Comparator<ApplyAction> actionComparator() {
        return (a, b) -> {
            boolean aDelete = a != null && a.getDiffType() == DiffType.DELETE;
            boolean bDelete = b != null && b.getDiffType() == DiffType.DELETE;
            if (aDelete != bDelete) {
                return aDelete ? 1 : -1;
            }

            int depA = dependencyValue(a, aDelete);
            int depB = dependencyValue(b, bDelete);
            if (depA != depB) {
                return aDelete ? Integer.compare(depB, depA) : Integer.compare(depA, depB);
            }

            int c1 = safeCompare(key(a == null ? null : a.getBusinessType()), key(b == null ? null : b.getBusinessType()));
            if (c1 != 0) {
                return c1;
            }
            int c2 = safeCompare(key(a == null ? null : a.getBusinessKey()), key(b == null ? null : b.getBusinessKey()));
            if (c2 != 0) {
                return c2;
            }
            int c3 = safeCompare(key(a == null ? null : a.getTableName()), key(b == null ? null : b.getTableName()));
            if (c3 != 0) {
                return c3;
            }
            int c4 = safeCompare(key(a == null ? null : a.getRecordBusinessKey()), key(b == null ? null : b.getRecordBusinessKey()));
            if (c4 != 0) {
                return c4;
            }
            return safeCompare(key(a == null || a.getDiffType() == null ? null : a.getDiffType().name()),
                key(b == null || b.getDiffType() == null ? null : b.getDiffType().name()));
        };
    }

    /**
     * 获取动作的依赖层级。
     *
     * @param action 动作
     * @param isDelete 是否是删除动作
     * @return 依赖层级
     */
    private static int dependencyValue(ApplyAction action, boolean isDelete) {
        if (action == null || action.getDependencyLevel() == null) {
            return isDelete ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        return action.getDependencyLevel();
    }

    private static String key(String value) {
        return value == null ? "" : value;
    }

    /**
     * 安全比较两个字符串。
     *
     * @param a 字符串 a
     * @param b 字符串 b
     * @return 比较结果
     */
    private static int safeCompare(String a, String b) {
        return Objects.toString(a, "").compareTo(Objects.toString(b, ""));
    }

    private static Set<String> toStringSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            set.add(value);
        }
        return set;
    }

    /**
     * 根据应用方向解析实际操作类型。
     *
     * <p>方向转换规则：
     * <ul>
     *     <li>A_TO_B（默认）：保持原始 diffType（INSERT 还是 INSERT，DELETE 还是 DELETE）</li>
     *     <li>B_TO_A（反向）：INSERT ↔ DELETE 互换（因为"A 有 B 无"在反向时变成"删除 A"）</li>
     * </ul>
     * UPDATE 不受方向影响，始终保持为 UPDATE。
     * </p>
     *
     * @param direction 应用方向
     * @param diffType  原始差异类型
     * @return 反转后的操作类型
     */
    private static DiffType resolveOpType(ApplyDirection direction, DiffType diffType) {
        if (diffType == null || direction == null) {
            return diffType;
        }
        // B_TO_A 场景：diff 结果的语义需要反转
        // 原本 INSERT（A 有 B 无）变成 DELETE（从 A 删除）
        // 原本 DELETE（A 无 B 有）变成 INSERT（往 A 插入）
        if (direction == ApplyDirection.B_TO_A) {
            if (diffType == DiffType.INSERT) {
                return DiffType.DELETE;
            }
            if (diffType == DiffType.DELETE) {
                return DiffType.INSERT;
            }
        }
        return diffType;
    }
}
