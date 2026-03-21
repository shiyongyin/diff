package com.diff.standalone.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;

/**
 * MyBatis-Plus mapper for {@code xai_tenant_diff_session}。
 *
 * <p>
 * <b>设计动机：</b>Diff 框架跨 tenant 操作（对比 source tenant 与 target tenant），
 * 需读写多个租户的数据，因此使用 {@code @InterceptorIgnore(tenantLine="true")} 禁用 tenant-line 拦截，
 * 否则拦截器会强制追加 tenant_id 条件导致跨租户查询失败。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantDiffSessionMapper extends BaseMapper<TenantDiffSessionPo> {
}

