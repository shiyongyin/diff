package com.diff.standalone.web.controller;


import com.diff.core.domain.schema.BusinessSchema;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.web.dto.response.PageResult;
import com.diff.standalone.web.dto.response.TenantDiffBusinessSummary;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffType;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import com.diff.standalone.web.dto.response.DiffSessionSummaryResponse;
import com.diff.standalone.model.StandaloneTenantModelBuilder;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.service.support.DiffDetailView;
import com.diff.standalone.service.support.DiffViewFilter;
import com.diff.standalone.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Standalone 模式下的“差异会话（Session）”相关 REST API。
 *
 * <p>
 * 该 Controller 作为独立运行入口（Spring MVC），不依赖 DAP；核心能力由
 * {@link TenantDiffStandaloneService} 提供（会话创建、对比执行、结果查询）。
 * </p>
 *
 * <p>
 * <b>设计动机：</b>{@link #create(CreateDiffSessionRequest)} 采用“创建即同步 compare”的流程，
 * 旨在简化 UX：用户一次请求即可获得完整 Diff 结果，无需轮询或二次调用。
 * 权衡：适用于工具化/小规模 tenant 对比；若数据量大或耗时较长，建议上层改造为异步任务并提供进度查询。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@RestController
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
@RequestMapping("/api/tenantDiff/standalone/session")
public class TenantDiffStandaloneSessionController {
    private final TenantDiffStandaloneService service;
    private final StandaloneTenantModelBuilder modelBuilder;

    /**
     * 构造时注入核心服务与模型构建器，保持 controller 层聚焦 HTTP -> 服务调用的转换。
     *
     * @param service      负责 session 生命周期管理与 diff 数据查询的 facade
     * @param modelBuilder 用于获取 businessType 对应 schema，支持筛选视图投影
     */
    public TenantDiffStandaloneSessionController(
        TenantDiffStandaloneService service,
        StandaloneTenantModelBuilder modelBuilder
    ) {
        this.service = service;
        this.modelBuilder = modelBuilder;
    }

    /**
     * 创建一个差异会话并立即执行对比。
     *
     * <p>
     * 行为：
     * <ul>
     *     <li>落库创建 session</li>
     *     <li>同步执行 compare，生成 business/table/record/field 维度的差异结果并落库</li>
     *     <li>返回该 session 的汇总信息（含统计信息）</li>
     * </ul>
     * </p>
     *
     * <p>
     * 说明：异常会被捕获并包装为 {@link ApiResponse#fail(String)} 返回（message 为异常信息）。
     * </p>
     *
     * @param request 创建请求，包含 sourceTenantId、targetTenantId、scope、options
     * @return 成功时返回 session 汇总（含 statistics）；失败时 success=false
     */
    @PostMapping("/create")
    public ApiResponse<DiffSessionSummaryResponse> create(@RequestBody @Valid CreateDiffSessionRequest request) {
        Long sessionId = service.createSession(request);
        service.runCompare(sessionId);
        return ApiResponse.ok(service.getSessionSummary(sessionId));
    }

    /**
     * 获取会话汇总信息（不含明细 diffJson）。
     *
     * @param sessionId 会话 ID
     * @return 成功时返回 session 汇总；不存在时由 service 层处理
     */
    @GetMapping("/get")
    public ApiResponse<DiffSessionSummaryResponse> get(@RequestParam("sessionId") @NotNull(message = "sessionId 不能为空") Long sessionId) {
        return ApiResponse.ok(service.getSessionSummary(sessionId));
    }

    /**
     * 分页查询业务级摘要列表。
     *
     * <p>
     * 支持按 {@code businessType} 与 {@code diffType} 过滤；分页采用显式 {@code LIMIT/OFFSET}，
     * 避免依赖 MyBatis-Plus 分页拦截器，从而保持模块可移植性。
     * </p>
     *
     * @param sessionId    会话 ID
     * @param businessType 可选，按业务类型过滤
     * @param diffType     可选，按 DiffType（INSERT/UPDATE/DELETE）过滤
     * @param pageNo       页码，从 1 开始
     * @param pageSize     每页条数
     * @return 分页结果，items 为 {@link TenantDiffBusinessSummary} 列表
     */
    @GetMapping("/listBusiness")
    public ApiResponse<PageResult<TenantDiffBusinessSummary>> listBusiness(
        @RequestParam("sessionId") Long sessionId,
        @RequestParam(value = "businessType", required = false) String businessType,
        @RequestParam(value = "diffType", required = false) String diffType,
        @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        DiffType parsed = parseDiffType(diffType);
        return ApiResponse.ok(service.listBusinessSummaries(sessionId, businessType, parsed, pageNo, pageSize));
    }

    /**
     * 查询某个业务对象的差异明细。
     *
     * <p>
     * 通过 {@code (sessionId, businessType, businessKey)} 定位到一条业务级 diffJson 并反序列化为 {@link BusinessDiff}。
     * 若不存在则返回 {@code success=false, message="not found"}。
     * </p>
     *
     * <p>可通过 {@code view} 参数控制返回详细程度：
     * <ul>
     *     <li>{@code FULL}（默认）：原始引擎输出，含 NOOP + 全量字段</li>
     *     <li>{@code FILTERED}：过滤 NOOP + 投影 showFields，保留 sourceFields/targetFields</li>
     *     <li>{@code COMPACT}：过滤 NOOP + 投影 showFields + 裁剪 sourceFields/targetFields</li>
     * </ul>
     * </p>
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型
     * @param businessKey  业务主键
     * @param view         视图模式（默认 FULL）
     * @return 成功时返回 {@link BusinessDiff}；未找到时 success=false
     */
    @GetMapping("/getBusinessDetail")
    public ApiResponse<BusinessDiff> getBusinessDetail(
        @RequestParam("sessionId") @NotNull(message = "sessionId 不能为空") Long sessionId,
        @RequestParam("businessType") @NotNull(message = "businessType 不能为空") String businessType,
        @RequestParam("businessKey") @NotNull(message = "businessKey 不能为空") String businessKey,
        @RequestParam(value = "view", required = false, defaultValue = "FULL") DiffDetailView view
    ) {
        Optional<BusinessDiff> diff = service.getBusinessDetail(sessionId, businessType, businessKey);
        if (diff.isEmpty()) {
            throw new TenantDiffException(ErrorCode.BUSINESS_DETAIL_NOT_FOUND);
        }
        BusinessDiff result = diff.get();
        if (view != DiffDetailView.FULL) {
            BusinessSchema schema = modelBuilder.getSchema(businessType);
            boolean stripRawFields = (view == DiffDetailView.COMPACT);
            result = DiffViewFilter.filterAndProject(result, schema, stripRawFields);
        }
        return ApiResponse.ok(result);
    }

    private static DiffType parseDiffType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DiffType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("diffType 参数非法: " + value);
        }
    }

}
