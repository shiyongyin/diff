package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Diff 结果的统计信息——用于摘要展示、过滤决策与业务级 diffType 推导。
 *
 * <p>
 * 各计数字段均为记录级统计（INSERT/UPDATE/DELETE/NOOP），
 * {@link #totalRecords} 通常等于四项之和。
 * {@link #totalTables} / {@link #totalBusinesses} 为聚合维度计数，
 * 具体含义由填充场景决定（单业务统计 vs 全局统计）。
 * </p>
 *
 * <h3>为什么需要统计信息</h3>
 * <p>
 * 前端需要按统计信息做摘要展示和分页过滤；
 * 当 diff 结果被持久化后重建视图时，统计信息可用于反推业务级 diffType
 * （遵循 {@link DiffType} 中定义的推导规则），避免重新遍历全部记录。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see DiffType
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiffStatistics {
    @JsonProperty("totalBusinesses")
    @Builder.Default
    private Integer totalBusinesses = 0;

    @JsonProperty("totalTables")
    @Builder.Default
    private Integer totalTables = 0;

    @JsonProperty("totalRecords")
    @Builder.Default
    private Integer totalRecords = 0;

    @JsonProperty("insertCount")
    @Builder.Default
    private Integer insertCount = 0;

    @JsonProperty("updateCount")
    @Builder.Default
    private Integer updateCount = 0;

    @JsonProperty("deleteCount")
    @Builder.Default
    private Integer deleteCount = 0;

    @JsonProperty("noopCount")
    @Builder.Default
    private Integer noopCount = 0;
}
