package com.diff.standalone.service;


import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.standalone.web.dto.response.ApplyPreviewResponse;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;

/**
 * Standalone Apply 编排服务（审计 + 快照 + 执行）。
 *
 * <p>
 * 该接口关注"执行前后治理"，而非具体 SQL 细节：
 * <ul>
 *     <li>从数据库加载 Diff 结果构建 Plan（不信任前端）</li>
 *     <li>记录 Apply 计划（planJson）与执行状态</li>
 *     <li>保存 Apply 前 TARGET 快照，用于回滚</li>
 *     <li>委托执行器按计划执行真实 SQL</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>设计动机：与 Diff 服务分离</b>。Apply 与 Diff 具有不同的生命周期与事务边界：
 * Diff 为只读分析、可重跑；Apply 为写操作、需审计与快照。分离后便于独立配置事务传播、
 * 超时与回滚策略，避免将写操作混入 Diff 的只读事务中。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface TenantDiffStandaloneApplyService {

    /**
     * 预览 Apply 影响范围（previewActionLimit 校验 + previewToken 计算）。
     *
     * @param sessionId Diff 会话 ID
     * @param direction 应用方向
     * @param options   筛选选项（可为 null）
     * @return 预览响应，含 statistics、actions、previewToken
     */
    ApplyPreviewResponse preview(Long sessionId, ApplyDirection direction, ApplyOptions options);

    /**
     * 从数据库加载 Diff 结果，结合筛选条件构建 ApplyPlan。
     *
     * @param sessionId Diff 会话 ID
     * @param direction 应用方向（A_TO_B 或 B_TO_A）
     * @param options   筛选与安全选项（可为 null，使用默认值）
     * @return 后端重建的 ApplyPlan（不信任前端直传，保证数据来源可信）
     * @throws IllegalArgumentException sessionId 或 direction 为空
     * @throws com.diff.core.domain.exception.TenantDiffException 会话不存在或无 Diff 结果
     */
    ApplyPlan buildPlan(Long sessionId, ApplyDirection direction, ApplyOptions options);

    /**
     * 执行 Apply 并返回审计信息。
     *
     * @param plan 需要执行的计划（应由 {@link #buildPlan} 构建，不接受前端直传）
     * @return 执行结果（包含 applyId、状态、时间戳与 {@link com.diff.core.domain.apply.ApplyResult}）
     * @throws IllegalArgumentException plan 或其 sessionId/direction 为空
     * @throws com.diff.core.domain.exception.TenantDiffException 会话不存在、未就绪或并发冲突
     * @throws com.diff.core.domain.exception.ApplyExecutionException 执行失败（含部分成功时的部分结果）
     */
    TenantDiffApplyExecuteResponse execute(ApplyPlan plan);
}
