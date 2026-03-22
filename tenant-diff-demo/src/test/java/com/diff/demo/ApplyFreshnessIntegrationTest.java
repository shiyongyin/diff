package com.diff.demo;

import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.core.domain.scope.TenantModelScope;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "tenant-diff.apply.max-compare-age=PT1S")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Apply Compare 时效保护测试")
class ApplyFreshnessIntegrationTest {

    @Autowired
    private TenantDiffStandaloneService diffService;

    @Autowired
    private TenantDiffStandaloneApplyService applyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Compare 结果过旧时拒绝 Apply")
    void execute_whenCompareTooOld_shouldReject() {
        CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .scope(TenantModelScope.builder()
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .build())
            .build();
        Long sessionId = diffService.createSession(request);
        diffService.runCompare(sessionId);

        jdbcTemplate.update(
            "UPDATE xai_tenant_diff_session SET finished_at = ? WHERE id = ?",
            LocalDateTime.now().minusMinutes(5),
            sessionId
        );

        ApplyPlan plan = applyService.buildPlan(
            sessionId, ApplyDirection.A_TO_B,
            ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                .allowDelete(false)
                .build()
        );

        TenantDiffException ex = assertThrows(TenantDiffException.class, () -> applyService.execute(plan));
        assertEquals(ErrorCode.APPLY_COMPARE_TOO_OLD, ex.getErrorCode());
    }
}
