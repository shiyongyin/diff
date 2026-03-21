package com.diff.standalone.service;


import com.diff.standalone.web.dto.response.PageResult;
import com.diff.standalone.web.dto.response.TenantDiffBusinessSummary;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffType;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import com.diff.standalone.web.dto.response.DiffSessionSummaryResponse;

import java.util.Optional;

/**
 * Standalone 租户差异对比服务（MyBatis-Plus + 无 DAP 依赖）。
 *
 * <p>
 * 该接口抽象了“会话 + 对比 + 查询”的核心能力：
 * <ul>
 *     <li>会话（Session）：记录对比双方租户、scope、options、执行状态等</li>
 *     <li>对比（Compare）：构建两侧业务模型后执行 diff 引擎并落库结果</li>
 *     <li>查询：提供会话汇总、业务摘要分页、业务明细等</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>设计动机</b>：将 Diff 编排集中在单一服务层，便于统一管理 session 生命周期、
 * 模型构建与错误恢复策略；与 Apply/Rollback 解耦，因 Diff 为只读分析、Apply 为写操作，
 * 二者事务边界与失败语义不同。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface TenantDiffStandaloneService {

    /**
     * 创建一个对比会话并落库。
     *
     * @param request 创建请求（包含 source/target tenant、scope 与可选 options）
     * @return 新创建的 sessionId
     * @throws IllegalArgumentException 入参不合法（tenantId/scope 等缺失）
     * @throws IllegalStateException 持久化失败
     */
    Long createSession(CreateDiffSessionRequest request);

    /**
     * 执行一次对比并落库结果。
     *
     * <p>
     * 设计说明：该方法允许重复执行（幂等语义靠“先删后插”实现），用于同一 session 的重跑/修复场景。
     * </p>
     *
     * @param sessionId 会话 ID
     * @throws IllegalArgumentException sessionId 为空
     * @throws com.diff.core.domain.exception.TenantDiffException 会话不存在或执行失败
     */
    void runCompare(Long sessionId);

    /**
     * 获取会话汇总信息（含统计字段）。
     *
     * @param sessionId 会话 ID
     * @return 会话汇总（含 source/target tenant、状态、统计信息等）
     * @throws IllegalArgumentException sessionId 为空
     * @throws com.diff.core.domain.exception.TenantDiffException 会话不存在
     */
    DiffSessionSummaryResponse getSessionSummary(Long sessionId);

    /**
     * 分页查询业务摘要列表（可选按 businessType/diffType 过滤）。
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型过滤（可为 null 表示不过滤）
     * @param diffType     Diff 类型过滤（可为 null 表示不过滤）
     * @param pageNo       页码（从 1 开始）
     * @param pageSize     每页条数
     * @return 分页结果（仅含 businessKey、diffType、statistics 等摘要，不含 diffJson）
     * @throws IllegalArgumentException sessionId 为空或分页参数非法
     * @throws com.diff.core.domain.exception.TenantDiffException 会话不存在
     */
    PageResult<TenantDiffBusinessSummary> listBusinessSummaries(Long sessionId, String businessType, DiffType diffType, int pageNo, int pageSize);

    /**
     * 查询某个业务对象的差异明细（diffJson）。
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型
     * @param businessKey  业务主键
     * @return 若不存在则返回 {@link Optional#empty()}
     * @throws IllegalArgumentException sessionId/businessType/businessKey 为空
     * @throws IllegalStateException    diffJson 反序列化失败
     */
    Optional<BusinessDiff> getBusinessDetail(Long sessionId, String businessType, String businessKey);
}

