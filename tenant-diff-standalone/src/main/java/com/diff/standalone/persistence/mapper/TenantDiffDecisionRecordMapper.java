package com.diff.standalone.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;

/**
 * MyBatis-Plus mapper for {@code xai_tenant_diff_decision_record}。
 *
 * <p>
 * Diff 框架跨 tenant 操作，决策记录表存储跨租户差异的决策与审计信息。
 * 若嵌入带 tenant-line 拦截器的租户环境，需在接口上添加 {@code @InterceptorIgnore(tenantLine="true")}。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface TenantDiffDecisionRecordMapper extends BaseMapper<TenantDiffDecisionRecordPo> {
}
