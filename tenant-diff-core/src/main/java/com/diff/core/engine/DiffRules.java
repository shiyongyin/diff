package com.diff.core.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 差异对比规则（控制哪些字段应被对比引擎忽略）。
 *
 * <p>
 * 跨租户对比时，很多字段（如 id、tenantsid、version 等系统字段）在不同租户间必然不同，
 * 但这些差异不具备业务含义。通过 DiffRules 声明忽略字段，可以避免产生大量无意义的 diff 噪音，
 * 让最终结果只关注业务层面的真实变更。
 * </p>
 *
 * <h3>合并策略</h3>
 * <p>
 * {@link #ignoreFieldsForTable(String)} 会将 {@link #defaultIgnoreFields} 与该表特有的忽略字段
 * 做并集返回，确保系统级字段始终被过滤，同时允许按表粒度追加业务专属的忽略项。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see DiffDefaults
 * @see TenantDiffEngine
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffRules {
    @Builder.Default
    private Set<String> defaultIgnoreFields = DiffDefaults.DEFAULT_IGNORE_FIELDS;

    /**
     * 按表维度配置忽略字段；与 {@link #defaultIgnoreFields} 做并集。
     */
    @Builder.Default
    private Map<String, Set<String>> ignoreFieldsByTable = Collections.emptyMap();

    /**
     * 创建使用内置默认忽略字段的规则实例。
     *
     * @return 默认规则，永不为 {@code null}
     */
    public static DiffRules defaults() {
        return DiffRules.builder().build();
    }

    /**
     * 获取指定表的完整忽略字段集合（默认集合 ∪ 该表专属集合）。
     *
     * @param tableName 表名，允许 {@code null}（此时仅返回默认集合）
     * @return 不可变的忽略字段集合，永不为 {@code null}
     */
    public Set<String> ignoreFieldsForTable(String tableName) {
        Set<String> defaults = defaultIgnoreFields == null ? DiffDefaults.DEFAULT_IGNORE_FIELDS : defaultIgnoreFields;
        Set<String> byTable = ignoreFieldsByTable == null ? null : ignoreFieldsByTable.get(tableName);
        if (byTable == null || byTable.isEmpty()) {
            return defaults;
        }
        Set<String> merged = new HashSet<>(defaults);
        merged.addAll(byTable);
        return Collections.unmodifiableSet(merged);
    }
}
