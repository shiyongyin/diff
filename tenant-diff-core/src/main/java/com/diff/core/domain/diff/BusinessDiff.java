package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 业务级差异输出模型——对比引擎的最顶层输出单元。
 *
 * <p>
 * 一个 BusinessDiff 对应一个业务对象（通过 businessType + businessKey 唯一定位），
 * 包含该业务对象下所有表的 diff 汇总。前端展示和 Apply 执行均以此为入口。
 * </p>
 *
 * <h3>为什么 diffType 可能为 null</h3>
 * <p>
 * 当两侧都存在该业务对象时，业务级 diffType 由记录级统计推导
 * （参见 {@link DiffType} 中的推导规则），引擎层不直接设置，
 * 由视图重建/持久化层按需计算填充。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see TableDiff
 * @see DiffStatistics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessDiff {
    /** 业务类型标识（如 INSTRUCTION、OCR_TEMPLATE）。 */
    @JsonProperty("businessType")
    private String businessType;

    /** 业务主表名。 */
    @JsonProperty("businessTable")
    private String businessTable;

    /** 业务键——跨租户对齐标识。 */
    @JsonProperty("businessKey")
    private String businessKey;

    /** 业务名称（辅助展示）。 */
    @JsonProperty("businessName")
    private String businessName;

    /**
     * 业务级差异类型。
     *
     * <p>
     * BUSINESS_INSERT / BUSINESS_DELETE 由引擎直接判定；
     * 两侧都存在时为 {@code null}，需由上层按 {@link DiffType} 推导规则计算。
     * </p>
     */
    @JsonProperty("diffType")
    private DiffType diffType;

    /** 该业务对象下的记录级统计汇总。 */
    @JsonProperty("statistics")
    private DiffStatistics statistics;

    /** 该业务对象下的表级差异明细列表。 */
    @JsonProperty("tableDiffs")
    private List<TableDiff> tableDiffs;
}
