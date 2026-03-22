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
    /** 归属的 session ID。 */
    private Long sessionId;
    /** 业务类型（plugin businessType）。 */
    private String businessType;
    /** 业务表名（如 example_product）。 */
    private String businessTable;
    /** 业务主键（如 PROD-001）。 */
    private String businessKey;
    /** 业务名称（可选，用于展示）。 */
    private String businessName;
    /** 当前业务对象在 source/target 之间的 diff 类型。 */
    private DiffType diffType;
    /** 统计信息：INSERT/UPDATE/DELETE 计数。 */
    private DiffStatistics statistics;
    /** 记录创建时间（用于排序/展示）。 */
    private LocalDateTime createdAt;
}
