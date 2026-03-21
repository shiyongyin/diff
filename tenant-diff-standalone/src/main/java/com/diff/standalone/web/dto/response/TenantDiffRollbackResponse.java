package com.diff.standalone.web.dto.response;


import com.diff.core.domain.apply.ApplyResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回滚执行响应（将 TARGET tenant 恢复到 apply 前快照）。
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
}

