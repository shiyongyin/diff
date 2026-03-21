package com.diff.standalone.web.dto.response;

import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyRecordStatus;
import com.diff.core.domain.apply.ApplyResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Apply 执行响应（审计 + 快照 + 执行结果）。
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDiffApplyExecuteResponse {
    private Long applyId;
    private Long sessionId;
    private ApplyDirection direction;
    private ApplyRecordStatus status;
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private ApplyResult applyResult;
}

