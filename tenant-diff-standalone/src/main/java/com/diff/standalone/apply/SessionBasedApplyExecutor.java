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
    /** 会话 mapper。 用于查询会话记录 */
    private final TenantDiffSessionMapper sessionMapper;
    /** 结果 mapper。 用于查询结果记录 */
    private final TenantDiffResultMapper resultMapper;
    /** 对象 mapper。 用于序列化和反序列化对象 */
    private final ObjectMapper objectMapper;
    /** 数据源注册表。 用于注册数据源 */
    private final DiffDataSourceRegistry dataSourceRegistry;
    /** 执行核心。 用于执行 Apply 计划 */
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
        // 解析有效的执行模式
        ApplyMode effectiveMode = ApplyExecutorCore.resolveEffectiveMode(plan, mode);
        // 目标租户 ID
        Long targetTenantId = null;
        // 业务 diff 加载器
        BusinessDiffLoader loader = null;
        // 目标数据源 key
        String targetDsKey = null;
        if (effectiveMode == ApplyMode.EXECUTE) {
            // 获取会话 ID
            Long sessionId = plan.getSessionId();
            // 如果会话 ID 为空 则抛出异常
            if (sessionId == null) {
                throw new IllegalArgumentException("plan.sessionId is null");
            }

            // 查询会话记录
            TenantDiffSessionPo session = sessionMapper.selectById(sessionId);
            // 如果会话记录为空 则抛出异常
            if (session == null) {
                throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
            }
            // 如果源租户 ID 或目标租户 ID 为空 则抛出异常
            if (session.getSourceTenantId() == null || session.getTargetTenantId() == null) {
                throw new IllegalArgumentException("session tenant ids are null");
            }

            // 获取方向
            ApplyDirection direction = plan.getDirection();
            // 如果方向为空 则抛出异常
            if (direction == null) {
                throw new IllegalArgumentException("plan.direction is null");
            }
            // 获取目标租户 ID
            targetTenantId = direction == ApplyDirection.A_TO_B ? session.getTargetTenantId() : session.getSourceTenantId();
            // 解析目标数据源 key
            targetDsKey = resolveDataSourceKeyForDirection(session, direction);

            // 创建业务 diff 缓存
            Map<String, BusinessDiff> businessCache = new HashMap<>();
            // 构建业务 diff 加载器
            loader = action -> loadBusinessDiff(sessionId, action, businessCache);
        }

        // 获取目标数据源的 JdbcTemplate
        JdbcTemplate targetJdbc = dataSourceRegistry.resolve(targetDsKey);
        // 记录日志
        log.info("Apply 开始, sessionId={}, targetDataSourceKey={}, mode={}", plan.getSessionId(), targetDsKey, effectiveMode);

        // 如果目标数据源为外部数据源 则执行手动事务
        if (isExternalDataSource(targetDsKey)) {
            return executeWithManualTransaction(plan, mode, targetTenantId, loader, targetJdbc, targetDsKey);
        }
        // 执行 Apply 计划
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
        // 获取目标数据源
        DataSource targetDs = targetJdbc.getDataSource();
        // 如果目标数据源为空 则抛出异常
        if (targetDs == null) {
            throw new IllegalStateException("targetJdbc.getDataSource() returned null, dataSourceKey=" + targetDsKey);
        }

        // 创建数据源事务管理器
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(targetDs);
        // 创建事务模板
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        // 执行事务
        return txTemplate.execute(status -> {
            // 执行 Apply 计划
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
        // 获取会话选项 JSON
        String optionsJson = session.getOptionsJson();
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            // 解析会话选项
            DiffSessionOptions options = objectMapper.readValue(optionsJson, DiffSessionOptions.class);
            // 解析加载选项
            LoadOptions loadOptions = StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
            // 如果加载选项为空 则返回空
            return loadOptions == null ? null : loadOptions.getDataSourceKey();
        } catch (Exception e) {
            throw new IllegalStateException(
                "无法解析 session optionsJson 中的 dataSourceKey, sessionId=" + session.getId()
                + ", direction=" + direction, e);
        }
    }

    /**
     * 判断是否为外部数据源。
     *
     * @param key 数据源 key
     * @return 是否为外部数据源
     */
    private static boolean isExternalDataSource(String key) {
        return key != null && !key.isBlank() && !DiffDataSourceRegistry.PRIMARY_KEY.equals(key.trim());
    }

    /**
     * 加载业务 diff。
     *
     * @param sessionId 会话 ID
     * @param action 动作
     * @param cache 业务 diff 缓存
     * @return 业务 diff
     */
    private BusinessDiff loadBusinessDiff(Long sessionId, ApplyAction action, Map<String, BusinessDiff> cache) {
        // 获取业务类型
        String businessType = action.getBusinessType();
        // 获取业务键
        String businessKey = action.getBusinessKey();
        // 如果业务类型或业务键为空 则抛出异常
        if (businessType == null || businessType.isBlank() || businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("action businessType/businessKey is blank");
        }
        // 构建缓存键
        String cacheKey = businessType + "|" + businessKey;
        // 获取缓存中的业务 diff
        BusinessDiff cached = cache.get(cacheKey);
        // 如果缓存中的业务 diff 不为空 则返回
        if (cached != null) {
            return cached;
        }

        // 查询结果记录
        TenantDiffResultPo po = resultMapper.selectOne(new QueryWrapper<TenantDiffResultPo>()
            .eq("session_id", sessionId)
            .eq("business_type", businessType)
            .eq("business_key", businessKey)
            .select("diff_json")
            .last("LIMIT 1")
        );
        // 如果结果记录为空 或 diffJson 为空 则抛出异常
        if (po == null || po.getDiffJson() == null || po.getDiffJson().isBlank()) {
            throw new IllegalArgumentException("business diff detail not found: " + cacheKey);
        }
        try {
            // 反序列化业务 diff
            BusinessDiff loaded = objectMapper.readValue(po.getDiffJson(), BusinessDiff.class);
            // 将业务 diff 添加到缓存中
            cache.put(cacheKey, loaded);
            return loaded;
        } catch (Exception e) {
            throw new IllegalArgumentException("diff_json deserialize failed: " + e.getMessage(), e);
        }
    }
}
