package com.diff.standalone.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;

/**
 * MyBatis-Plus mapper for {@code xai_tenant_diff_result}。
 *
 * <p>
 * <b>设计动机：</b>Diff 框架跨 tenant 操作，result 表存储的是跨租户对比结果，
 * 使用 {@code @InterceptorIgnore(tenantLine="true")} 禁用 tenant-line 拦截。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantDiffResultMapper extends BaseMapper<TenantDiffResultPo> {
}

