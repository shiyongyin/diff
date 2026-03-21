package com.diff.standalone.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diff.standalone.persistence.entity.TenantDiffApplyRecordPo;

/**
 * MyBatis-Plus mapper for {@code xai_tenant_diff_apply_record}。
 *
 * <p>
 * <b>设计动机：</b>Apply 框架跨 tenant 操作，apply 记录关联多个租户的 diff 与快照，
 * 使用 {@code @InterceptorIgnore(tenantLine="true")} 禁用 tenant-line 拦截。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantDiffApplyRecordMapper extends BaseMapper<TenantDiffApplyRecordPo> {
}

