package com.diff.standalone.web.dto.response;


import com.diff.standalone.model.SessionWarning;
import com.diff.core.domain.diff.DiffStatistics;
import com.diff.core.domain.diff.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    /** 会话 ID。 */
    private Long sessionId;
    /** 源租户 ID。 */
    private Long sourceTenantId;
    /** 目标租户 ID。 */
    private Long targetTenantId;
    /** 会话当前状态。 */
    private SessionStatus status;
    /** 会话的差异统计。 */
    private DiffStatistics statistics;
    /** 会话创建时间。 */
    private LocalDateTime createdAt;
    /** 会话完成时间（若尚未完成则为 null）。 */
    private LocalDateTime finishedAt;
    /** 发生错误时的 message。 */
    private String errorMsg;
    /** compare 时发生的 warning 数量。 */
    private Integer warningCount;
    /** warning 详情（结构化）。 */
    private List<SessionWarning> warnings;
}
