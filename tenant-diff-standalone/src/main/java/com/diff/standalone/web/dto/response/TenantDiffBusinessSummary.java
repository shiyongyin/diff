package com.diff.standalone.web.dto.response;


import com.diff.core.domain.diff.DiffStatistics;
import com.diff.core.domain.diff.DiffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 业务级差异摘要（用于列表/分页查询，不包含大字段 diffJson）。
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDiffBusinessSummary {
    private Long sessionId;
    private String businessType;
    private String businessTable;
    private String businessKey;
    private String businessName;
    private DiffType diffType;
    private DiffStatistics statistics;
    private LocalDateTime createdAt;
}

