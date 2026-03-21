package com.diff.standalone.web.dto.response;


import com.diff.core.domain.diff.DiffStatistics;
import com.diff.core.domain.diff.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Diff Session 汇总信息（用于 UI/查询）。
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffSessionSummaryResponse {
    private Long sessionId;
    private Long sourceTenantId;
    private Long targetTenantId;
    private SessionStatus status;
    private DiffStatistics statistics;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private String errorMsg;
}

