package com.diff.core.domain.apply;

import com.diff.core.domain.diff.DiffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Apply 执行过程中的动作级错误/告警描述。
 *
 * <p>
 * 每条 ApplyActionError 对应一条 {@link ApplyAction} 的执行失败或告警。
 * {@link #fatal} 标志区分致命错误（终止整个 Apply）和非致命告警
 * （记录后继续执行剩余动作）。这种设计允许 Apply 在部分失败时仍产出有用的结果。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyResult
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyActionError {
    /** 动作唯一标识（actionId）。 */   
    private String actionId;
    /** 业务类型。 */
    private String businessType;
    /** 业务键。 */
    private String businessKey;
    /** 表名。 */
    private String tableName;
    /** 记录业务键。 */
    private String recordBusinessKey;
    /** 操作类型。 */
    private DiffType diffType;
    /** 表依赖层级（冗余自 diff 结果，用于执行排序）。 */
    private Integer dependencyLevel;
    /** 错误/告警描述信息。 */
    private String message;
    /** true = 致命错误（终止执行）；false = 非致命告警（继续执行）。 */
    private boolean fatal;
}
