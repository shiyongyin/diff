package com.diff.core.domain.apply;


import com.diff.core.domain.diff.DiffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;

/**
 * 一条可执行的 Apply 动作——v1 以记录级为最小粒度。
 *
 * <p>
 * 每条 ApplyAction 精确描述"对哪张表的哪条记录执行什么操作"，
 * 由 {@link com.diff.core.apply.PlanBuilder} 从 diff 结果转换而来。
 * 执行器根据 {@link #diffType} 分别调用 INSERT/UPDATE/DELETE SQL。
 * </p>
 *
 * <h3>定位维度</h3>
 * <ul>
 *   <li>{@link #actionId}：动作唯一标识（用于 selection 精确勾选）</li>
 *   <li>{@link #businessType} + {@link #businessKey}：定位业务对象</li>
 *   <li>{@link #tableName} + {@link #recordBusinessKey}：定位具体记录</li>
 *   <li>{@link #diffType}：决定操作类型（NOOP 不会出现在 ApplyPlan 中）</li>
 * </ul>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyPlan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyAction {
    /**
     * 动作唯一标识（actionId）。
     *
     * <p>格式（v1）：{@code v1:{escape(businessType)}:{escape(businessKey)}:{escape(tableName)}:{escape(recordBusinessKey)}}</p>
     *
     * <p>其中 escape 规则为：先将 {@code %} 转义为 {@code %25}，再将 {@code :} 转义为 {@code %3A}。</p>
     */
    private String actionId;
    /** 业务类型。 */
    private String businessType;
    /** 业务键。 */
    private String businessKey;
    /** 表名。 */
    private String tableName;
    /** 表依赖层级（冗余自 diff 结果，用于执行排序）。 */
    private Integer dependencyLevel;
    /** 记录业务键。 */
    private String recordBusinessKey;
    /** 操作类型。 */
    private DiffType diffType;

    /** v1 预留扩展载荷（保持最小且可序列化）。 */
    @Builder.Default
    private Map<String, Object> payload = Collections.emptyMap();

    /**
     * 基于 4 个业务维度计算确定性 actionId。
     *
     * <p>同一输入必须产生同一输出（无随机值、无大小写归一化、无 trim）。</p>
     *
     * @param businessType       业务类型（必填，不允许 null/blank）
     * @param businessKey        业务键（必填，不允许 null/blank）
     * @param tableName          表名（必填，不允许 null/blank）
     * @param recordBusinessKey  记录业务键（必填，不允许 null/blank）
     * @return actionId（v1）
     * @throws IllegalArgumentException 任一维度为 null/blank 时抛出
     */
    public static String computeActionId(String businessType,
                                        String businessKey,
                                        String tableName,
                                        String recordBusinessKey) {
        return "v1:" + String.join(":",
            escapeRequired("businessType", businessType),
            escapeRequired("businessKey", businessKey),
            escapeRequired("tableName", tableName),
            escapeRequired("recordBusinessKey", recordBusinessKey)
        );
    }

    private static String escapeRequired(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("actionId component is blank: " + fieldName);
        }
        // 转义保留字符 避免歧义 转义 % 为 %25 转义 : 为 %3A
        return value.replace("%", "%25").replace(":", "%3A");
    }
}
