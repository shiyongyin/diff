package com.diff.standalone.web.dto.request;


import com.diff.core.domain.diff.DiffSessionOptions;
import com.diff.core.domain.scope.TenantModelScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建 Diff Session 的请求（tenant A vs tenant B）。
 *
 * <p>
 * 该请求描述“对比谁”和“比哪些”：
 * <ul>
 *     <li>sourceTenantId / targetTenantId：对比双方</li>
 *     <li>scope：限定业务类型与 businessKey 范围</li>
 *     <li>options：可选执行参数（加载选项、对比规则等）</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDiffSessionRequest {
    @NotNull(message = "sourceTenantId 不能为空")
    private Long sourceTenantId;

    @NotNull(message = "targetTenantId 不能为空")
    private Long targetTenantId;

    @NotNull(message = "scope 不能为空")
    @Valid
    private TenantModelScope scope;

    private DiffSessionOptions options;
}

