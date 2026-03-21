package com.diff.standalone.web.dto.request;


import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyOptions;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standalone Apply 执行/预览请求体。
 *
 * <p>
 * 前端只传 sessionId + direction + options（筛选条件），
 * 后端根据 sessionId 从数据库加载 diff 结果重新构建 Plan，不信任前端提供的 actions。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyExecuteRequest {

    @NotNull(message = "sessionId 不能为空")
    private Long sessionId;

    @NotNull(message = "direction 不能为空")
    private ApplyDirection direction;

    private ApplyOptions options;
}
