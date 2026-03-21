package com.diff.standalone.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diff.standalone.persistence.entity.TenantDiffSnapshotPo;

/**
 * MyBatis-Plus mapper for {@code xai_tenant_diff_snapshot}。
 *
 * <p>
 * <b>设计动机：</b>快照表存储 Apply 前各 tenant 的业务数据，Diff/Apply 框架跨 tenant 操作，
 * 使用 {@code @InterceptorIgnore(tenantLine="true")} 禁用 tenant-line 拦截。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@InterceptorIgnore(tenantLine = "true")
public interface TenantDiffSnapshotMapper extends BaseMapper<TenantDiffSnapshotPo> {
}

