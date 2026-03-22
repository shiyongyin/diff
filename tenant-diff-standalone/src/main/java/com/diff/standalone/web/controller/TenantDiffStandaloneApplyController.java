package com.diff.standalone.web.controller;


import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.standalone.web.dto.response.ApplyPreviewResponse;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
import com.diff.standalone.web.dto.response.TenantDiffRollbackResponse;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneRollbackService;
import com.diff.standalone.web.ApiResponse;
import com.diff.standalone.web.dto.request.ApplyExecuteRequest;
import com.diff.standalone.web.dto.request.ApplyRollbackRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Standalone 模式下的 Apply / Rollback REST API。
 *
 * <p>
 * Apply 用于将 diff 结果转换为可执行动作并落库审计，同时执行 SQL 变更；Rollback 用于将目标 tenant 恢复到 apply 前快照。
 * </p>
 *
 * <p>
 * <b>设计动机：</b>preview 与 execute 拆分为独立端点，遵循“先预览、后确认、再执行”的安全流程。
 * 用户必须先调用 preview 查看影响范围（INSERT/UPDATE/DELETE 数量），确认无误后再调用 execute 真实写库，
 * 降低误操作风险；后端不信任前端传入的 actions，始终从数据库重建 Plan。
 * </p>
 *
 * <p>
 * 注意：当前 {@link #execute(ApplyExecuteRequest)} 会强制执行（EXECUTE），不会进行 DRY_RUN。
 * 调用方应在调用前自行完成审核/确认（例如先在上层做预估或灰度验证），避免误操作。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@RestController
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
@RequestMapping("/api/tenantDiff/standalone/apply")
public class TenantDiffStandaloneApplyController {
    private final TenantDiffStandaloneApplyService applyService;
    private final TenantDiffStandaloneRollbackService rollbackService;

    /**
     * 依赖注入的构造函数，分别传入 Apply、Rollback 服务，保持 Controller 层仅处理 HTTP 语义。
     *
     * @param applyService    用于构建 Plan 与执行 Apply 的核心服务
     * @param rollbackService 用于执行回滚并报告结果的服务
     */
    public TenantDiffStandaloneApplyController(
        TenantDiffStandaloneApplyService applyService,
        TenantDiffStandaloneRollbackService rollbackService
    ) {
        this.applyService = applyService;
        this.rollbackService = rollbackService;
    }

    /**
     * 预览 Apply 影响范围（不写库）。
     *
     * <p>
     * 后端从数据库加载 diff 结果重建 Plan，返回按 businessType 分组的操作统计，
     * 前端可据此展示确认页："即将执行 N 条 INSERT、M 条 UPDATE"。
     * </p>
     *
     * @param request 包含 sessionId、direction、options
     * @return 预览结果，含 statistics 与 businessTypePreviews
     */
    @PostMapping("/preview")
    public ApiResponse<ApplyPreviewResponse> preview(@RequestBody @Valid ApplyExecuteRequest request) {
        return ApiResponse.ok(applyService.preview(
            request.getSessionId(),
            request.getDirection(),
            request.getOptions()
        ));
    }

    /**
     * 执行 Apply（真实写库）。
     *
     * <p>
     * 前端传入 sessionId + direction + options（筛选条件），
     * 后端从数据库加载 diff 结果重建 Plan 后执行，不信任前端提供的 actions 列表。
     * </p>
     *
     * @param request 包含 sessionId、direction、options
     * @return 执行结果，含 applyId、status、applyResult
     */
    @PostMapping("/execute")
    public ApiResponse<TenantDiffApplyExecuteResponse> execute(@RequestBody @Valid ApplyExecuteRequest request) {
        ApplyOptions execOptions = request.getOptions() == null
            ? ApplyOptions.builder().build()
            : request.getOptions();
        // P0: 不信任客户端 mode，强制 EXECUTE，确保阈值校验不可绕过。
        execOptions.setMode(ApplyMode.EXECUTE);

        ApplyPlan plan = applyService.buildPlan(
            request.getSessionId(),
            request.getDirection(),
            execOptions
        );
        return ApiResponse.ok(applyService.execute(plan));
    }

    /**
     * 回滚某次 Apply（基于 applyId）。
     *
     * <p>
     * 回滚实现为 v1 “业务级覆盖恢复”：取 apply 前 TARGET 快照与当前 TARGET 再次 diff，生成恢复计划并执行。
     * </p>
     *
     * @param request 包含 applyId
     * @return 回滚结果，含 applyId、applyResult
     */
    @PostMapping("/rollback")
    public ApiResponse<TenantDiffRollbackResponse> rollback(@RequestBody @Valid ApplyRollbackRequest request) {
        return ApiResponse.ok(rollbackService.rollback(request.getApplyId(), request.isAcknowledgeDrift()));
    }
}
