package com.diff.core.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 标准化表模型容器——承载同一业务对象下某张表的全部记录。
 *
 * <p>
 * 业务对象通常涉及多张表（主表 + 子表 + 孙表），各表之间存在外键依赖。
 * {@link #dependencyLevel} 用于表达依赖层级（0 = 主表，1 = 子表，2 = 孙表...），
 * 对比引擎和 Apply 执行器据此决定处理顺序：INSERT 时先主后子，DELETE 时先子后主。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see BusinessData
 * @see RecordData
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableData {
    /** 物理表名。 */
    @JsonProperty("tableName")
    private String tableName;

    /**
     * 表依赖层级（0 = 主表，1 = 子表，2 = 孙表...）。
     *
     * <p>
     * 该值驱动对比排序与 Apply 执行顺序，{@code null} 时排序兜底到最后。
     * </p>
     */
    @JsonProperty("dependencyLevel")
    private Integer dependencyLevel;

    /** 该表的记录列表。 */
    @JsonProperty("records")
    private List<RecordData> records;
}
