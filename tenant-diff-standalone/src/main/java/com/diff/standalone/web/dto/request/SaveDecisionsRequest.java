package com.diff.standalone.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量保存审查决策请求。
 *
 * @author tenant-diff
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveDecisionsRequest {

    @NotNull(message = "sessionId 不能为空")
    private Long sessionId;

    @NotBlank(message = "businessType 不能为空")
    private String businessType;

    @NotBlank(message = "businessKey 不能为空")
    private String businessKey;

    @NotEmpty(message = "decisions 不能为空")
    @Valid
    private List<DecisionItem> decisions;
}
