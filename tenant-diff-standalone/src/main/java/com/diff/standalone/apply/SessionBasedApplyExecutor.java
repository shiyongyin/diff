package com.diff.standalone.apply;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.diff.core.domain.diff.DiffSessionOptions;
import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.exception.ApplyExecutionException;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.util.StandaloneLoadOptionsResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone Apply 执行器实现：以物理 {@code id} 执行，并强制 {@code tenantsid} 约束。
 *
 * <p>
 * 记录定位策略：仅使用 {@code tenantsid + id}。
 * 这意味着 UPDATE/DELETE 依赖 diffJson 中能提供目标记录的 {@code id}（通常来自模型加载时读取到的 id 字段）。
 * </p>
 *
 * <p>
 * <b>多数据源事务策略（WHY 手动事务管理）</b>：Spring 默认 {@code @Transactional} 仅绑定主数据源，
 * 当 Apply 目标为外部数据源（如独立 ERP 库）时，主库事务无法覆盖。因此对非主数据源，
 * 本类创建临时 {@link DataSourceTransactionManager} + {@link TransactionTemplate} 手动控制事务边界，
 * 确保 Apply 要么全部成功要么全部回滚，避免跨库半成功状态。
 * </p>
 *
 * <p>
 * 多数据源策略汇总：
 * <ul>
 *     <li>主数据源（dataSourceKey=null/"primary"）：依赖调用方（Service 层）的 {@code @Transactional}</li>
 *     <li>外部数据源：使用手动 {@link DataSourceTransactionManager} + {@link TransactionTemplate}</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class SessionBasedApplyExecutor implements StandaloneApplyExecutor {
    private final TenantDiffSessionMapper sessionMapper;
    private final TenantDiffResultMapper resultMapper;
    private final ObjectMapper objectMapper;
    private final DiffDataSourceRegistry dataSourceRegistry;
    private final ApplyExecutorCore core;

    public SessionBasedApplyExecutor(
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        BusinessApplySupportRegistry supportRegistry,
        ObjectMapper objectMapper,
        DiffDataSourceRegistry dataSourceRegistry
    ) {
        this.sessionMapper = sessionMapper;
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
        this.dataSourceRegistry = dataSourceRegistry;
        this.core = new ApplyExecutorCore(supportRegistry);
    }

    /**
     * 执行 Apply 计划。
     *
     * <p>
     * 模式说明：
     * <ul>
     *     <li>{@link ApplyMode#DRY_RUN}：仅做安全校验与预估，不写库。</li>
     *     <li>{@link ApplyMode#EXECUTE}：真实执行 SQL 写库。外部数据源使用手动事务管理。</li>
     * </ul>
     * </p>
     */
    @Override
    public ApplyResult execute(ApplyPlan plan, ApplyMode mode) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is null");
        }
        ApplyMode effectiveMode = ApplyExecutorCore.resolveEffectiveMode(plan, mode);

        Long targetTenantId = null;
        BusinessDiffLoader loader = null;
        String targetDsKey = null;
        if (effectiveMode == ApplyMode.EXECUTE) {
            Long sessionId = plan.getSessionId();
            if (sessionId == null) {
                throw new IllegalArgumentException("plan.sessionId is null");
            }

            TenantDiffSessionPo session = sessionMapper.selectById(sessionId);
            if (session == null) {
                throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
            }
            if (session.getSourceTenantId() == null || session.getTargetTenantId() == null) {
                throw new IllegalArgumentException("session tenant ids are null");
            }

            ApplyDirection direction = plan.getDirection();
            if (direction == null) {
                throw new IllegalArgumentException("plan.direction is null");
            }
            targetTenantId = direction == ApplyDirection.A_TO_B ? session.getTargetTenantId() : session.getSourceTenantId();
            targetDsKey = resolveDataSourceKeyForDirection(session, direction);

            Map<String, BusinessDiff> businessCache = new HashMap<>();
            loader = action -> loadBusinessDiff(sessionId, action, businessCache);
        }

        JdbcTemplate targetJdbc = dataSourceRegistry.resolve(targetDsKey);
        log.info("Apply 开始, sessionId={}, targetDataSourceKey={}, mode={}", plan.getSessionId(), targetDsKey, effectiveMode);

        if (isExternalDataSource(targetDsKey)) {
            return executeWithManualTransaction(plan, mode, targetTenantId, loader, targetJdbc, targetDsKey);
        }
        return core.execute(plan, mode, targetTenantId, loader, targetJdbc);
    }

    /**
     * 外部数据源：手动管理事务。
     *
     * <p>因为 Spring 默认 TransactionManager 只绑定主数据源，
     * 外部数据源的 JdbcTemplate 执行不受 {@code @Transactional} 管控。
     * 此处创建临时 {@link DataSourceTransactionManager} 手动控制事务边界。</p>
     */
    private ApplyResult executeWithManualTransaction(
        ApplyPlan plan, ApplyMode mode, Long targetTenantId,
        BusinessDiffLoader loader, JdbcTemplate targetJdbc, String targetDsKey
    ) {
        DataSource targetDs = targetJdbc.getDataSource();
        if (targetDs == null) {
            throw new IllegalStateException("targetJdbc.getDataSource() returned null, dataSourceKey=" + targetDsKey);
        }
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(targetDs);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        return txTemplate.execute(status -> {
            try {
                return core.execute(plan, mode, targetTenantId, loader, targetJdbc);
            } catch (ApplyExecutionException e) {
                status.setRollbackOnly();
                log.error("外部数据源 Apply 事务回滚, dataSourceKey={}", targetDsKey, e);
                throw e;
            }
        });
    }

    /**
     * 按 Apply 方向解析写入端的 dataSourceKey。
     *
     * <p>
     * 方向映射：
     * <ul>
     *     <li>{@link ApplyDirection#A_TO_B} → 目标端（B）的 dataSourceKey</li>
     *     <li>{@link ApplyDirection#B_TO_A} → 源端（A）的 dataSourceKey</li>
     * </ul>
     * </p>
     *
     * <p><b>（F01 修复）</b>解析失败直接抛异常，不静默回退到主数据源，
     * 防止数据写入错误的数据库。</p>
     *
     * @param session   会话记录
     * @param direction Apply 方向
     * @return dataSourceKey（null 表示使用主数据源）
     */
    private String resolveDataSourceKeyForDirection(TenantDiffSessionPo session, ApplyDirection direction) {
        String optionsJson = session.getOptionsJson();
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            DiffSessionOptions options = objectMapper.readValue(optionsJson, DiffSessionOptions.class);
            LoadOptions loadOptions = StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
            return loadOptions == null ? null : loadOptions.getDataSourceKey();
        } catch (Exception e) {
            throw new IllegalStateException(
                "无法解析 session optionsJson 中的 dataSourceKey, sessionId=" + session.getId()
                + ", direction=" + direction, e);
        }
    }

    private static boolean isExternalDataSource(String key) {
        return key != null && !key.isBlank() && !DiffDataSourceRegistry.PRIMARY_KEY.equals(key.trim());
    }

    private BusinessDiff loadBusinessDiff(Long sessionId, ApplyAction action, Map<String, BusinessDiff> cache) {
        String businessType = action.getBusinessType();
        String businessKey = action.getBusinessKey();
        if (businessType == null || businessType.isBlank() || businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("action businessType/businessKey is blank");
        }
        String cacheKey = businessType + "|" + businessKey;
        BusinessDiff cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        TenantDiffResultPo po = resultMapper.selectOne(new QueryWrapper<TenantDiffResultPo>()
            .eq("session_id", sessionId)
            .eq("business_type", businessType)
            .eq("business_key", businessKey)
            .select("diff_json")
            .last("LIMIT 1")
        );
        if (po == null || po.getDiffJson() == null || po.getDiffJson().isBlank()) {
            throw new IllegalArgumentException("business diff detail not found: " + cacheKey);
        }
        try {
            BusinessDiff loaded = objectMapper.readValue(po.getDiffJson(), BusinessDiff.class);
            cache.put(cacheKey, loaded);
            return loaded;
        } catch (Exception e) {
            throw new IllegalArgumentException("diff_json deserialize failed: " + e.getMessage(), e);
        }
    }
}
