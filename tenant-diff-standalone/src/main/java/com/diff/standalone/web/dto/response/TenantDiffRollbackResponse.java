package com.diff.standalone.web.dto.response;


import com.diff.core.domain.apply.ApplyResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回滚执行响应（将 TARGET tenant 恢复到 apply 前快照），提供执行结果与对冲验证信息。
 *
 * <p>
 * {@link #verification} 包含剩余 diff 数量与总体成功标记，{@link #driftDetected} 标记是否发现演进偏移，
 * {@link #diagnostics} 则反馈回滚过程中的辅助提示（如 schema 变化或重复操作）。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDiffRollbackResponse {
    private Long applyId;
    private ApplyResult applyResult;
    private RollbackVerification verification;
    private Boolean driftDetected;
    private String diagnostics;
}
