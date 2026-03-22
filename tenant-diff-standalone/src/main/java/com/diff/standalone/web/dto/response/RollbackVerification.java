package com.diff.standalone.web.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回滚结果验证摘要，反映 rollback stage 之后剩余差异与整体成功性。
 *
 * <p>
 * 用于告知调用方本次 rollback 是否彻底覆盖目标 diff，以及剩余条数与人工审查描述。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RollbackVerification {
    /** rollback 是否全部成功完成。 */
    private boolean success;
    /** rollback 后依然存在的 diff 计数（若大于 0 代表存在 drift）。 */
    private int remainingDiffCount;
    /** 提示信息（如“剩余两条 diff 未覆盖”）。 */
    private String summary;
}
