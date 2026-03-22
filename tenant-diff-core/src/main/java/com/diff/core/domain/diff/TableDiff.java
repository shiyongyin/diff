package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表级差异——承载同一张表内所有记录的 diff 汇总。
 *
 * <p>
 * {@link #dependencyLevel} 在此处冗余保留（原值来自 {@link com.diff.core.domain.model.TableData}），
 * 原因是下游 Apply/PlanBuilder 需要按依赖层级排序动作，
 * 如果不冗余就需要反查原始 Schema，增加不必要的耦合。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see BusinessDiff
 * @see RecordDiff
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableDiff {
    /** 物理表名。 */
    @JsonProperty("tableName")
    private String tableName;

    /**
     * 表依赖层级（0 = 主表，1 = 子表，2 = 孙表），驱动 Apply 执行顺序。
     */
    @JsonProperty("dependencyLevel")
    private Integer dependencyLevel;

    /** 表级差异类型（TABLE_INSERT / TABLE_DELETE / {@code null} 表示两侧都有该表）。 */
    @JsonProperty("diffType")
    private DiffType diffType;

    /** 记录级差异按类型计数汇总。 */
    @JsonProperty("counts")
    private TableDiffCounts counts;

    /** 该表内的逐记录差异明细。 */
    @JsonProperty("recordDiffs")
    private List<RecordDiff> recordDiffs;

    /**
     * 记录级差异按类型计数。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TableDiffCounts {
        /** INSERT 动作数量。 */
        @JsonProperty("insertCount")
        @Builder.Default
        private Integer insertCount = 0;

        /** UPDATE 动作数量。 */
        @JsonProperty("updateCount")
        @Builder.Default
        private Integer updateCount = 0;

        /** DELETE 动作数量。 */
        @JsonProperty("deleteCount")
        @Builder.Default
        private Integer deleteCount = 0;

        /** NOOP 动作数量。 */
        @JsonProperty("noopCount")
        @Builder.Default
        private Integer noopCount = 0;
    }
}
