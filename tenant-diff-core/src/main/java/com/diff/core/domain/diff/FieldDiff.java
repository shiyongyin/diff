package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段级差异——描述记录中单个字段的源值与目标值差异。
 *
 * <p>
 * 仅在 {@link DiffType#UPDATE} 且实际存在字段变化时生成。
 * 主要用于前端差异高亮展示，Apply 执行器不依赖此对象（它直接使用
 * {@link RecordDiff#getSourceFields()} 中的完整字段集）。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see RecordDiff
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldDiff {
    /** 字段名（数据库列名）。 */
    @JsonProperty("fieldName")
    private String fieldName;

    /** 源侧（A）的字段值。 */
    @JsonProperty("sourceValue")
    private Object sourceValue;

    /** 目标侧（B）的字段值。 */
    @JsonProperty("targetValue")
    private Object targetValue;

    /** 人类可读的变更描述（如 "from [old] to [new]"）。 */
    @JsonProperty("changeDescription")
    private String changeDescription;
}
