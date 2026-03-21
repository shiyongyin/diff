package com.diff.core.domain.scope;


import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 租户模型构建范围——限定一次 diff/apply 操作需要覆盖的业务域与业务对象。
 *
 * <p>
 * 为什么需要 scope 而非总是全量对比：全量对比在业务对象数量较多时
 * 耗时过长且产出大量无关差异。通过 scope 可以精确控制"只对比哪些业务类型
 * 的哪些业务对象"，降低执行时间和结果噪音。
 * </p>
 *
 * <h3>业务键获取策略</h3>
 * <ul>
 *   <li>优先使用 {@link #businessKeysByType} 中显式指定的 keys</li>
 *   <li>未指定时，构建器调用 plugin.listBusinessKeys() 动态获取，
 *       并通过 {@link #filter} 传递过滤条件</li>
 * </ul>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ScopeFilter
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantModelScope {
    /** 需要构建/对比的业务类型列表（至少一个）。 */
    @NotEmpty(message = "businessTypes 不能为空")
    @Builder.Default
    private List<String> businessTypes = Collections.emptyList();

    /**
     * 显式指定的业务键 allow-list：businessType → businessKeys。
     *
     * <p>未提供某业务类型的 keys 时，构建器会调用 plugin.listBusinessKeys() 动态获取。</p>
     */
    @Builder.Default
    private Map<String, List<String>> businessKeysByType = Collections.emptyMap();

    /** 可选过滤条件——传递给 plugin.listBusinessKeys() 做范围收敛。 */
    private ScopeFilter filter;
}
