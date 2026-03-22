package com.diff.standalone.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.diff.core.engine.DiffRules;
import com.diff.core.engine.TenantDiffEngine;
import com.diff.standalone.web.dto.response.PageResult;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.web.dto.response.TenantDiffBusinessSummary;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffStatistics;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.diff.SessionStatus;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import com.diff.core.domain.diff.DiffSessionOptions;
import com.diff.standalone.web.dto.response.DiffSessionSummaryResponse;
import com.diff.standalone.model.BuildWarning;
import com.diff.standalone.model.SessionWarning;
import com.diff.standalone.model.StandaloneTenantModelBuilder;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.config.TenantDiffProperties;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.util.StandaloneLoadOptionsResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import org.slf4j.MDC;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Standalone 模式下的租户差异服务实现（MyBatis-Plus 持久化 + 无 DAP 依赖）。
 *
 * <p>
 * 该实现负责：
 * <ul>
 *     <li>创建对比会话（session）并持久化</li>
 *     <li>构建两侧 tenant 的业务模型（通过 {@link StandaloneTenantModelBuilder} 路由到各业务插件）</li>
 *     <li>执行 Diff 引擎（{@link TenantDiffEngine}）生成差异结果并持久化</li>
 *     <li>提供会话汇总/业务摘要分页/业务明细查询</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>设计动机（WHY）</b>：
 * <ul>
 *     <li><b>session 生命周期</b>：先创建 session 再 runCompare，便于异步/重试与状态追踪；失败时保留 FAILED 状态便于排查。</li>
 *     <li><b>模型构建</b>：通过插件路由到各业务类型，单业务键失败仅记 warning 不中断全局，保证部分成功可查。</li>
 *     <li><b>错误恢复</b>：compare 在事务内"先删后插"保证幂等，异常时显式更新 session 为 FAILED，避免脏状态。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 关键设计点：
 * <ul>
 *     <li><b>可重跑/幂等</b>：同一 session 重跑 compare 时，会先删除旧结果再写入新结果。</li>
 *     <li><b>可移植</b>：分页查询使用显式 {@code LIMIT/OFFSET}，避免强依赖 MyBatis-Plus 分页拦截器。</li>
 *     <li><b>容错</b>：模型构建阶段的异常会被收集为 warning 并记录日志，避免单个 businessKey 失败影响全局。</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class TenantDiffStandaloneServiceImpl implements TenantDiffStandaloneService {
    private final TenantDiffSessionMapper sessionMapper;
    private final TenantDiffResultMapper resultMapper;
    private final StandaloneTenantModelBuilder modelBuilder;
    private final TenantDiffEngine diffEngine;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TenantDiffProperties properties;

    public TenantDiffStandaloneServiceImpl(
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        StandaloneTenantModelBuilder modelBuilder,
        TenantDiffEngine diffEngine,
        ObjectMapper objectMapper,
        TransactionTemplate transactionTemplate,
        TenantDiffProperties properties
    ) {
        this.sessionMapper = sessionMapper;
        this.resultMapper = resultMapper;
        this.modelBuilder = modelBuilder;
        this.diffEngine = diffEngine;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /**
     * 创建并持久化一条对比会话。
     *
     * <p>会话仅记录"对比双方 + scope + options + 状态"，实际对比由 {@link #runCompare(Long)} 触发。</p>
     */
    @Override
    public Long createSession(CreateDiffSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }
        if (request.getSourceTenantId() == null) {
            throw new IllegalArgumentException("sourceTenantId is null");
        }
        if (request.getTargetTenantId() == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        if (request.getScope() == null || request.getScope().getBusinessTypes() == null || request.getScope().getBusinessTypes().isEmpty()) {
            throw new IllegalArgumentException("scope.businessTypes must not be empty");
        }

        String scopeJson = toJsonOrNull(request.getScope());
        String optionsJson = toJsonOrNull(request.getOptions());

        LocalDateTime now = LocalDateTime.now();
        TenantDiffSessionPo po = TenantDiffSessionPo.builder()
            .sessionKey(UUID.randomUUID().toString().replace("-", ""))
            .sourceTenantId(request.getSourceTenantId())
            .targetTenantId(request.getTargetTenantId())
            .scopeJson(scopeJson)
            .optionsJson(optionsJson)
            .status(SessionStatus.CREATED.name())
            .errorMsg(null)
            .createdAt(now)
            .finishedAt(null)
            .build();

        sessionMapper.insert(po);
        if (po.getId() == null) {
            throw new IllegalStateException("insert session failed: id is null");
        }
        return po.getId();
    }

    /**
     * 执行对比并落库结果。
     *
     * <p>
     * 流程：
     * <ul>
     *     <li>更新 session 状态为 RUNNING</li>
     *     <li>构建两侧租户模型并调用 {@link TenantDiffEngine} 执行 compare</li>
     *     <li>为保证可重跑：删除该 session 旧结果后插入新结果</li>
     *     <li>更新 session 状态为 SUCCESS/FAILED</li>
     * </ul>
     * </p>
     */
    @Override
    public void runCompare(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }

        TenantDiffSessionPo session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
        }
        SessionStatus currentStatus = session.getStatus() == null ? null : SessionStatus.fromValue(session.getStatus());
        if (currentStatus == SessionStatus.APPLYING || currentStatus == SessionStatus.ROLLING_BACK) {
            throw new TenantDiffException(ErrorCode.SESSION_COMPARE_CONFLICT);
        }

        MDC.put("sessionId", String.valueOf(sessionId));
        long startMs = System.currentTimeMillis();
        try {
            log.info("开始执行对比: sessionId={}", sessionId);
            updateSessionStatus(sessionId, SessionStatus.RUNNING, null, null, null);

            // 模型构建在事务外执行，避免长事务；compare 为纯内存计算，失败时无需回滚 DB。
            CompareExecutionContext compareContext = doCompare(session);

            transactionTemplate.execute(status -> {
                // 为保证可重跑的幂等性：同一 session 重跑时用"先删后插"替换结果集。
                resultMapper.delete(new QueryWrapper<TenantDiffResultPo>()
                    .eq("session_id", sessionId)
                );
                batchSaveResult(sessionId, compareContext.compareResult().businessDiffs());
                updateSessionStatus(sessionId, SessionStatus.SUCCESS, null, LocalDateTime.now(),
                    toJsonOrNull(compareContext.warnings()));
                return null;
            });
            log.info("对比完成: sessionId={}, 耗时={}ms", sessionId, System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            String errorMsg = buildErrorMsg(e);
            try {
                updateSessionStatus(sessionId, SessionStatus.FAILED, errorMsg, LocalDateTime.now(), null);
            } catch (Exception updateError) {
                log.warn("更新会话状态失败: sessionId={}", sessionId, updateError);
            }
            throw e;
        } finally {
            MDC.remove("sessionId");
        }
    }

    private CompareExecutionContext doCompare(TenantDiffSessionPo session) {
        if (session == null) {
            throw new IllegalArgumentException("session is null");
        }
        if (session.getSourceTenantId() == null || session.getTargetTenantId() == null) {
            throw new IllegalArgumentException("session tenant ids are null");
        }

        com.diff.core.domain.scope.TenantModelScope scope =
            parseJsonRequired(session.getScopeJson(), com.diff.core.domain.scope.TenantModelScope.class, "scopeJson");

        DiffSessionOptions options = parseJsonOrDefault(
            session.getOptionsJson(),
            DiffSessionOptions.class,
            DiffSessionOptions.builder().build(),
            "optionsJson"
        );
        LoadOptions sourceLoadOptions = StandaloneLoadOptionsResolver.resolveSource(options);
        LoadOptions targetLoadOptions = StandaloneLoadOptionsResolver.resolveTarget(options);
        DiffRules diffRules = options.getDiffRules() == null
            ? DiffRules.builder().defaultIgnoreFields(properties.getDefaultIgnoreFields()).build()
            : options.getDiffRules();

        // buildWithWarnings 将单 businessKey 加载失败记为 warning 而非抛异常，避免局部脏数据导致全局失败。
        StandaloneTenantModelBuilder.BuildResult source = modelBuilder.buildWithWarnings(session.getSourceTenantId(), scope, sourceLoadOptions);
        StandaloneTenantModelBuilder.BuildResult target = modelBuilder.buildWithWarnings(session.getTargetTenantId(), scope, targetLoadOptions);

        List<SessionWarning> warnings = new ArrayList<>();
        warnings.addAll(logWarnings(session.getId(), "source", source.warnings()));
        warnings.addAll(logWarnings(session.getId(), "target", target.warnings()));

        return new CompareExecutionContext(
            diffEngine.compare(source.models(), target.models(), diffRules),
            warnings);
    }

    @Override
    public DiffSessionSummaryResponse getSessionSummary(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }

        TenantDiffSessionPo session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new TenantDiffException(ErrorCode.SESSION_NOT_FOUND);
        }

        DiffStatistics statistics = aggregateStatistics(sessionId);
        List<SessionWarning> warnings = parseSessionWarnings(session.getWarningJson());
        return DiffSessionSummaryResponse.builder()
            .sessionId(session.getId())
            .sourceTenantId(session.getSourceTenantId())
            .targetTenantId(session.getTargetTenantId())
            .status(session.getStatus() == null ? null : SessionStatus.fromValue(session.getStatus()))
            .statistics(statistics)
            .createdAt(session.getCreatedAt())
            .finishedAt(session.getFinishedAt())
            .errorMsg(session.getErrorMsg())
            .warningCount(warnings.size())
            .warnings(warnings)
            .build();
    }

    /**
     * 分页查询业务摘要。
     *
     * <p>
     * 仅返回摘要字段（diffType、statisticsJson 等），不返回大字段 diffJson。
     * </p>
     */
    private static final int MAX_PAGE_SIZE = 200;

    @Override
    public PageResult<TenantDiffBusinessSummary> listBusinessSummaries(Long sessionId, String businessType, DiffType diffType, int pageNo, int pageSize) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        int effectivePageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        QueryWrapper<TenantDiffResultPo> countQw = buildSummaryConditions(sessionId, businessType, diffType);
        long total = resultMapper.selectCount(countQw);

        QueryWrapper<TenantDiffResultPo> listQw = buildSummaryConditions(sessionId, businessType, diffType);
        listQw.select(
            "session_id",
            "business_type",
            "business_table",
            "business_key",
            "business_name",
            "diff_type",
            "statistics_json",
            "created_at"
        ).orderByAsc("business_key");

        long offset = (long) (pageNo - 1) * effectivePageSize;
        listQw.last("LIMIT " + effectivePageSize + " OFFSET " + offset);
        List<TenantDiffResultPo> pageRows = resultMapper.selectList(listQw);

        List<TenantDiffBusinessSummary> items = new ArrayList<>();
        if (pageRows != null) {
            for (TenantDiffResultPo po : pageRows) {
                if (po == null) {
                    continue;
                }
                items.add(mapSummary(po));
            }
        }

        return PageResult.<TenantDiffBusinessSummary>builder()
            .total(total)
            .pageNo(pageNo)
            .pageSize(effectivePageSize)
            .items(items)
            .build();
    }

    private static QueryWrapper<TenantDiffResultPo> buildSummaryConditions(Long sessionId, String businessType, DiffType diffType) {
        QueryWrapper<TenantDiffResultPo> qw = new QueryWrapper<TenantDiffResultPo>()
            .eq("session_id", sessionId);
        if (businessType != null && !businessType.isBlank()) {
            qw.eq("business_type", businessType);
        }
        if (diffType != null) {
            qw.eq("diff_type", diffType.name());
        }
        return qw;
    }

    /**
     * 查询业务差异明细（diffJson）。
     *
     * <p>若 diffJson 不存在则返回 empty；反序列化失败则抛出异常。</p>
     */
    @Override
    public Optional<BusinessDiff> getBusinessDetail(Long sessionId, String businessType, String businessKey) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }
        if (businessType == null || businessType.isBlank()) {
            throw new IllegalArgumentException("businessType is blank");
        }
        if (businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey is blank");
        }

        TenantDiffResultPo po = resultMapper.selectOne(new QueryWrapper<TenantDiffResultPo>()
            .eq("session_id", sessionId)
            .eq("business_type", businessType)
            .eq("business_key", businessKey)
            .select("diff_json")
            .last("LIMIT 1")
        );
        if (po == null || po.getDiffJson() == null || po.getDiffJson().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(po.getDiffJson(), BusinessDiff.class));
        } catch (Exception e) {
            log.error("差异结果 JSON 反序列化失败: sessionId={}, businessType={}, businessKey={}", sessionId, businessType, businessKey, e);
            throw new IllegalStateException("diff_json deserialize failed: " + e.getMessage(), e);
        }
    }

    private DiffStatistics aggregateStatistics(Long sessionId) {
        List<TenantDiffResultPo> rows = resultMapper.selectList(new QueryWrapper<TenantDiffResultPo>()
            .eq("session_id", sessionId)
            .select("statistics_json")
        );
        if (rows == null || rows.isEmpty()) {
            return DiffStatistics.builder().build();
        }

        int totalBusinesses = 0;
        int totalTables = 0;
        int totalRecords = 0;
        int insertCount = 0;
        int updateCount = 0;
        int deleteCount = 0;
        int noopCount = 0;

        for (TenantDiffResultPo row : rows) {
            if (row == null) {
                continue;
            }
            DiffStatistics stats = parseStatistics(row.getStatisticsJson());
            totalBusinesses += safeInt(stats.getTotalBusinesses());
            totalTables += safeInt(stats.getTotalTables());
            totalRecords += safeInt(stats.getTotalRecords());
            insertCount += safeInt(stats.getInsertCount());
            updateCount += safeInt(stats.getUpdateCount());
            deleteCount += safeInt(stats.getDeleteCount());
            noopCount += safeInt(stats.getNoopCount());
        }

        return DiffStatistics.builder()
            .totalBusinesses(totalBusinesses)
            .totalTables(totalTables)
            .totalRecords(totalRecords)
            .insertCount(insertCount)
            .updateCount(updateCount)
            .deleteCount(deleteCount)
            .noopCount(noopCount)
            .build();
    }

    private void batchSaveResult(Long sessionId, List<BusinessDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (BusinessDiff diff : diffs) {
            if (diff == null) {
                continue;
            }
            validateBusinessDiffForSave(diff);

            DiffType storedDiffType = resolveTopDiffType(diff);
            String statisticsJson = toJsonOrNull(diff.getStatistics());
            String diffJson = toJsonOrNull(diff);
            if (diffJson == null || diffJson.isBlank()) {
                throw new IllegalStateException("diff_json is blank");
            }

            TenantDiffResultPo po = TenantDiffResultPo.builder()
                .sessionId(sessionId)
                .businessType(diff.getBusinessType())
                .businessTable(diff.getBusinessTable())
                .businessKey(diff.getBusinessKey())
                .businessName(diff.getBusinessName())
                .diffType(storedDiffType == null ? null : storedDiffType.name())
                .statisticsJson(statisticsJson)
                .diffJson(diffJson)
                .createdAt(now)
                .build();
            resultMapper.insert(po);
        }
    }

    private static void validateBusinessDiffForSave(BusinessDiff diff) {
        if (diff.getBusinessType() == null || diff.getBusinessType().isBlank()) {
            throw new IllegalArgumentException("businessType is blank");
        }
        if (diff.getBusinessTable() == null || diff.getBusinessTable().isBlank()) {
            throw new IllegalArgumentException("businessTable is blank");
        }
        if (diff.getBusinessKey() == null || diff.getBusinessKey().isBlank()) {
            throw new IllegalArgumentException("businessKey is blank");
        }
    }

    private TenantDiffBusinessSummary mapSummary(TenantDiffResultPo po) {
        DiffStatistics stats = parseStatistics(po.getStatisticsJson());
        return TenantDiffBusinessSummary.builder()
            .sessionId(po.getSessionId())
            .businessType(po.getBusinessType())
            .businessTable(po.getBusinessTable())
            .businessKey(po.getBusinessKey())
            .businessName(po.getBusinessName())
            .diffType(po.getDiffType() == null ? null : DiffType.fromValue(po.getDiffType()))
            .statistics(stats)
            .createdAt(po.getCreatedAt())
            .build();
    }

    private DiffStatistics parseStatistics(String statisticsJson) {
        if (statisticsJson == null || statisticsJson.isBlank()) {
            return DiffStatistics.builder().build();
        }
        try {
            return objectMapper.readValue(statisticsJson, DiffStatistics.class);
        } catch (Exception e) {
            log.error("统计信息 JSON 反序列化失败: {}", statisticsJson, e);
            throw new IllegalStateException("statistics_json deserialize failed: " + e.getMessage(), e);
        }
    }

    private void updateSessionStatus(Long sessionId, SessionStatus status, String errorMsg, LocalDateTime finishedAt,
                                     String warningJson) {
        TenantDiffSessionPo update = new TenantDiffSessionPo();
        update.setId(sessionId);
        update.setStatus(status == null ? null : status.name());
        update.setErrorMsg(errorMsg);
        update.setFinishedAt(finishedAt);
        update.setWarningJson(warningJson);
        sessionMapper.updateById(update);
    }

    private List<SessionWarning> logWarnings(Long sessionId, String side, List<BuildWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        List<SessionWarning> sessionWarnings = new ArrayList<>();
        for (BuildWarning warning : warnings) {
            if (warning == null || warning.message() == null || warning.message().isBlank()) {
                continue;
            }
            SessionWarning sessionWarning = new SessionWarning(
                side,
                warning.businessType(),
                warning.businessKey(),
                warning.message());
            sessionWarnings.add(sessionWarning);
            log.warn("租户差异对比警告: sessionId={}, side={}, businessType={}, businessKey={}, msg={}",
                sessionId, side, warning.businessType(), warning.businessKey(), warning.message());
        }
        return sessionWarnings;
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON serialize failed: " + e.getMessage(), e);
        }
    }

    private <T> T parseJsonRequired(String json, Class<T> type, String fieldName) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON parse failed for " + fieldName + ": " + e.getMessage(), e);
        }
    }

    private <T> T parseJsonOrDefault(String json, Class<T> type, T defaultValue, String fieldName) {
        if (json == null || json.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON parse failed for " + fieldName + ": " + e.getMessage(), e);
        }
    }

    private List<SessionWarning> parseSessionWarnings(String warningJson) {
        if (warningJson == null || warningJson.isBlank()) {
            return List.of();
        }
        try {
            List<SessionWarning> warnings = objectMapper.readValue(
                warningJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, SessionWarning.class));
            return warnings == null ? List.of() : Collections.unmodifiableList(warnings);
        } catch (Exception e) {
            throw new IllegalStateException("warning_json deserialize failed: " + e.getMessage(), e);
        }
    }

    private record CompareExecutionContext(
        TenantDiffEngine.CompareResult compareResult,
        List<SessionWarning> warnings
    ) {
    }

    private static DiffType resolveTopDiffType(BusinessDiff diff) {
        if (diff == null) {
            return null;
        }
        if (diff.getDiffType() != null) {
            return diff.getDiffType();
        }

        DiffStatistics statistics = diff.getStatistics();
        if (statistics == null) {
            return DiffType.NOOP;
        }

        int insertCount = safeInt(statistics.getInsertCount());
        int updateCount = safeInt(statistics.getUpdateCount());
        int deleteCount = safeInt(statistics.getDeleteCount());

        // 将 record 级变化聚合为 business 行的可过滤 diffType，便于 listBusinessSummaries 按 diffType 筛选。
        if (updateCount > 0 || (insertCount > 0 && deleteCount > 0)) {
            return DiffType.UPDATE;
        }
        if (insertCount > 0) {
            return DiffType.INSERT;
        }
        if (deleteCount > 0) {
            return DiffType.DELETE;
        }
        return DiffType.NOOP;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static String buildErrorMsg(Exception e) {
        if (e == null) {
            return null;
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }
}
