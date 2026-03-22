package com.diff.standalone.apply;


import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于内存 diff 明细执行 Apply 计划（不依赖结果表持久化）。
 *
 * <p>
 * 该实现主要用于回滚场景：回滚流程中 diff 是由"快照 vs 当前"即时生成的，
 * 无需、也不应依赖 {@code xai_tenant_diff_result} 表中的 diffJson。
 * </p>
 *
 * <p>
 * v1 回滚仅支持 target=主库方向。若原始 Apply 的 targetDataSourceKey 指向外部数据源，
 * 需在调用方入口处拒绝回滚。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public class InMemoryApplyExecutor implements StandaloneBusinessDiffApplyExecutor {
    /** 执行核心。 */
    private final ApplyExecutorCore core;
    /** 数据源注册表。 */
    private final DiffDataSourceRegistry dataSourceRegistry;

    public InMemoryApplyExecutor(BusinessApplySupportRegistry supportRegistry, DiffDataSourceRegistry dataSourceRegistry) {
        // 创建执行核心
        this.core = new ApplyExecutorCore(supportRegistry);
        // 创建数据源注册表
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 执行 Apply 计划。
     *
     * @param targetTenantId 目标租户 ID
     * @param plan 执行计划
     * @param diffs 业务级 diff 明细
     * @param mode 执行模式
     * @return 执行结果
     */
    @Override
    public ApplyResult execute(Long targetTenantId, ApplyPlan plan, List<BusinessDiff> diffs, ApplyMode mode) {
        // 默认用主数据源（回滚场景 v1 仅支持主库）
        return execute(targetTenantId, plan, diffs, mode, null);
    }

    /**
     * 执行 Apply 计划（指定目标数据源）。
     *
     * @param targetTenantId 目标租户 ID
     * @param plan 执行计划
     * @param diffs 业务级 diff 明细
     * @param mode 执行模式
     * @param targetDataSourceKey 目标数据源 key（null/"primary" 表示主数据源）
     * @return 执行结果
     */
    public ApplyResult execute(Long targetTenantId, ApplyPlan plan, List<BusinessDiff> diffs, ApplyMode mode, String targetDataSourceKey) {
        if (targetTenantId == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        if (plan.getDirection() == null) {
            throw new IllegalArgumentException("plan.direction is null");
        }
        // 获取目标数据源
        JdbcTemplate targetJdbc = dataSourceRegistry.resolve(targetDataSourceKey);
        // 构建业务 diff 加载器
        BusinessDiffLoader loader = buildLoader(diffs);
        // 执行 Apply 计划
        return core.execute(plan, mode, targetTenantId, loader, targetJdbc);
    }

    /**
     * 构建业务 diff 加载器。
     *
     * @param diffs 业务级 diff 明细
     * @return 业务 diff 加载器
     */
    private static BusinessDiffLoader buildLoader(List<BusinessDiff> diffs) {
        // 创建业务 diff 缓存
        Map<String, BusinessDiff> cache = new HashMap<>();
        // 遍历业务 diff 明细
        if (diffs != null) {
            for (BusinessDiff diff : diffs) {
                if (diff == null) {
                    continue;
                }
                // 构建缓存键
                String cacheKey = diff.getBusinessType() + "|" + diff.getBusinessKey();
                // 将业务 diff 明细添加到缓存中
                cache.putIfAbsent(cacheKey, diff);
            }
        }

        // 构建业务 diff 加载器
        return action -> {
            if (action == null) {
                throw new IllegalArgumentException("action is null");
            }
            // 获取业务类型
            String businessType = action.getBusinessType();
            String businessKey = action.getBusinessKey();
            // 验证业务类型和业务键是否为空
            if (businessType == null || businessType.isBlank() || businessKey == null || businessKey.isBlank()) {
                throw new IllegalArgumentException("action businessType/businessKey is blank");
            }
            // 构建缓存键
            String cacheKey = businessType + "|" + businessKey;
            // 获取缓存中的业务 diff 明细
            BusinessDiff cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            // 如果缓存中的业务 diff 明细为空 则抛出异常
            throw new IllegalArgumentException("business diff detail not found: " + cacheKey);
        };
    }
}
