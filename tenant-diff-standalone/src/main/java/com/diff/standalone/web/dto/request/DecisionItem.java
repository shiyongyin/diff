package com.diff.standalone.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条审查决策——前端提交的最小决策单元。
 *
 * @author tenant-diff
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionItem {

    /** 表名。 */
    @NotBlank(message = "tableName 不能为空")
    private String tableName;

    /** 记录业务键。 */
    @NotBlank(message = "recordBusinessKey 不能为空")
    private String recordBusinessKey;

    /**
     * 决策类型：ACCEPT / SKIP。
     *
     * <p>对应 {@link com.diff.core.domain.diff.DecisionType}。</p>
     */
    @NotBlank(message = "decision 不能为空")
    private String decision;

    /** 决策原因（可选）。 */
    private String decisionReason;
}
