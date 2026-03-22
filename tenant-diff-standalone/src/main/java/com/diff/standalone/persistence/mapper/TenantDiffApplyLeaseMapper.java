package com.diff.standalone.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diff.standalone.persistence.entity.TenantDiffApplyLeasePo;

/**
 * MyBatis-Plus mapper for {@code xai_tenant_diff_apply_lease}。
 *
 * <p>
 * <b>设计动机：</b>租约表记录跨 session 的互斥状态，涉及多个租户/数据源交叉访问，因此禁用
 * tenant-line 拦截器以便框架逻辑能跨租户写入同一张表。</p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantDiffApplyLeaseMapper extends BaseMapper<TenantDiffApplyLeasePo> {
}
