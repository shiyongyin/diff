package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 记录级差异——对比引擎输出的最细粒度 diff 单元。
 *
 * <p>
 * 每条 RecordDiff 携带两侧的原始字段快照（{@link #sourceFields} / {@link #targetFields}），
 * 以及变更字段明细（{@link #fieldDiffs}）。Apply 执行器从此处获取需要写入目标库的字段值。
 * </p>
 *
 * <h3>为什么保留原始快照而非只存 fieldDiffs</h3>
 * <p>
 * INSERT 场景需要全量字段（无 fieldDiffs），DELETE 场景需要 targetFields 中的 id 用于定位。
 * 即使是 UPDATE，Apply 也需要 sourceFields 完整内容做字段变换。
 * {@link #fieldDiffs} 仅在 UPDATE 且字段确有变化时才输出，主要用于前端展示差异高亮。
 * </p>
 *
 * <h3>为什么需要 showFields</h3>
 * <p>
 * 前端展示时不需要看到所有列，showFields 是可选的投影字段集，由插件/视图层按需填充。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see TableDiff
 * @see FieldDiff
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordDiff {
    /** 记录的业务键——用于在 Apply 阶段从 diff 结果中定位对应记录。 */
    @JsonProperty("recordBusinessKey")
    private String recordBusinessKey;

    /** 差异类型（INSERT / UPDATE / DELETE / NOOP）。 */
    @JsonProperty("diffType")
    private DiffType diffType;

    /** 用户决策（ACCEPT / SKIP），v1 默认全部 ACCEPT。 */
    @JsonProperty("decision")
    private DecisionType decision;

    /** 决策原因（可选说明）。 */
    @JsonProperty("decisionReason")
    private String decisionReason;

    /** 决策时间。 */
    @JsonProperty("decisionTime")
    private LocalDateTime decisionTime;

    /** 源侧（A）原始字段快照（包含 id/tenantsid 等系统字段）。INSERT 时此值有效。 */
    @JsonProperty("sourceFields")
    private Map<String, Object> sourceFields;

    /** 目标侧（B）原始字段快照。DELETE 时此值有效。 */
    @JsonProperty("targetFields")
    private Map<String, Object> targetFields;

    /** 可选展示投影字段（前端差异高亮用，由插件/视图层按需填充）。 */
    @JsonProperty("showFields")
    private Map<String, Object> showFields;

    /** 字段级差异明细（仅 UPDATE 时输出，NOOP/INSERT/DELETE 时为 {@code null}）。 */
    @JsonProperty("fieldDiffs")
    private List<FieldDiff> fieldDiffs;

    /** 对比过程中的警告信息（如 source/target 为 null 等边界情况）。 */
    @JsonProperty("warnings")
    private List<String> warnings;
}
