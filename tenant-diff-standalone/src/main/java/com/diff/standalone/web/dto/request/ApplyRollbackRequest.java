package com.diff.standalone.web.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standalone Apply 回滚请求体。
 *
 * <p>通过 {@code applyId} 指定需要回滚的 apply 记录。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyRollbackRequest {
    @NotNull
    private Long applyId;
}

