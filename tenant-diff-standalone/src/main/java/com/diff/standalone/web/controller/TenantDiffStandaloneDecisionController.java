package com.diff.standalone.web.controller;

import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.service.DecisionRecordService;
import com.diff.standalone.web.ApiResponse;
import com.diff.standalone.web.dto.request.SaveDecisionsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审查决策 REST API——提供 diff 记录的逐条 ACCEPT/SKIP 管理。
 *
 * <p>
 * 仅当 {@link DecisionRecordService} bean 存在时生效（opt-in），
 * 不影响未启用 decision 功能的项目。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/tenant-diff/decision")
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
public class TenantDiffStandaloneDecisionController {

    private final DecisionRecordService decisionRecordService;

    /**
     * 仅依赖 {@link DecisionRecordService} 的构造函数，保证控制器职责聚焦在 HTTP-REST 转换上。
     *
     * @param decisionRecordService 决策持久化与查询逻辑所在的服务
     */
    public TenantDiffStandaloneDecisionController(DecisionRecordService decisionRecordService) {
        this.decisionRecordService = decisionRecordService;
    }

    /**
     * 批量保存审查决策（upsert 语义）。
     *
     * <p>
     * 会根据 {@code sessionId/businessType/businessKey} 覆盖旧决策，避免重复提交时产生冗余记录，
     * 前端可在审核页多次提交同一 businessKey 仍保持幂等。
     * </p>
     *
     * @param request 请求体
     * @return 实际保存的条数
     */
    @PostMapping("/save")
    public ApiResponse<Integer> saveDecisions(@Valid @RequestBody SaveDecisionsRequest request) {
        int saved = decisionRecordService.saveDecisions(
            request.getSessionId(),
            request.getBusinessType(),
            request.getBusinessKey(),
            request.getDecisions()
        );
        return ApiResponse.ok(saved);
    }

    /**
     * 查询指定业务对象的所有决策记录。
     *
     * <p>
     * 返回的历史记录可用于支持“查看已决策记录”页，或者在后续 Apply 中复核审查轨迹。
     * </p>
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型
     * @param businessKey  业务键
     * @return 决策记录列表
     */
    @GetMapping("/list")
    public ApiResponse<List<TenantDiffDecisionRecordPo>> listDecisions(
        @RequestParam("sessionId") @NotNull(message = "sessionId 不能为空") Long sessionId,
        @RequestParam("businessType") @NotNull(message = "businessType 不能为空") String businessType,
        @RequestParam("businessKey") @NotNull(message = "businessKey 不能为空") String businessKey
    ) {
        List<TenantDiffDecisionRecordPo> records =
            decisionRecordService.listDecisions(sessionId, businessType, businessKey);
        return ApiResponse.ok(records);
    }
}
