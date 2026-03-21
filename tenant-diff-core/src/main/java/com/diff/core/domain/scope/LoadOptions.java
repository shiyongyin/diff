package com.diff.core.domain.scope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件模型构建的可选加载参数——控制插件从数据库加载数据时的行为。
 *
 * <p>
 * 不同业务类型插件可按需读取这些参数决定加载范围/策略。
 * 设计为可选参数对象而非多个方法重载，便于后续新增参数时保持接口兼容。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see com.diff.core.domain.diff.DiffSessionOptions
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadOptions {
    /** 是否包含增量数据（默认 false，仅加载全量）。 */
    @Builder.Default
    private boolean includeIncremental = false;

    /**
     * API_DEFINITION 主表版本号（xai_api_structure_node.api_version）。
     *
     * <p>仅 ApiDefinitionStandalonePlugin 会读取该值；其他插件忽略。</p>
     */
    private String apiDefinitionVersion;

    /**
     * 数据源 key——用于多数据源场景。
     *
     * <p>
     * {@code null} 或 {@code "primary"} 表示使用 Spring 主数据源。
     * 插件据此从 {@code DiffDataSourceRegistry} 解析对应的 JdbcTemplate。
     * </p>
     */
    private String dataSourceKey;
}
