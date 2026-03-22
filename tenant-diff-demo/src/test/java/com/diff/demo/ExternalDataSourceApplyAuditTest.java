package com.diff.demo;

import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyRecordStatus;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.diff.DiffSessionOptions;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.scope.TenantModelScope;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "tenant-diff.datasources.ext.url=jdbc:h2:mem:tenant_diff_ext;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
    "tenant-diff.datasources.ext.driver-class-name=org.h2.Driver",
    "tenant-diff.datasources.ext.username=sa",
    "tenant-diff.datasources.ext.password="
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("外部数据源 Apply 审计补偿测试")
class ExternalDataSourceApplyAuditTest {

    @Autowired
    private TenantDiffStandaloneService diffService;

    @Autowired
    private TenantDiffStandaloneApplyService applyService;

    @Autowired
    private DiffDataSourceRegistry dataSourceRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcTemplate externalJdbc;

    @BeforeEach
    void setUpExternalDataSource() {
        externalJdbc = dataSourceRegistry.resolve("ext");
        externalJdbc.execute("""
            CREATE TABLE IF NOT EXISTS example_product (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenantsid BIGINT,
                product_code VARCHAR(64),
                product_name VARCHAR(255),
                price DECIMAL(10,2),
                status VARCHAR(32) DEFAULT 'ACTIVE',
                version INT DEFAULT 0,
                data_modify_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
        externalJdbc.update("DELETE FROM example_product");
        externalJdbc.update(
            "INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES (?, ?, ?, ?, ?)",
            2L, "PROD-002", "高级套餐B-改", new BigDecimal("249.00"), "ACTIVE");
    }

    @Test
    @DisplayName("外部数据源 Apply 失败时，主库 FAILED 审计保留且外部库数据回滚")
    void externalDataSourceApplyFailure_keepsFailedAuditAndRollsBackExternalWrites() {
        CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .scope(TenantModelScope.builder()
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .build())
            .options(DiffSessionOptions.builder()
                .targetLoadOptions(LoadOptions.builder().dataSourceKey("ext").build())
                .build())
            .build();

        Long sessionId = diffService.createSession(request);
        diffService.runCompare(sessionId);

        ApplyPlan validPlan = applyService.buildPlan(
            sessionId,
            ApplyDirection.A_TO_B,
            ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .allowDelete(false)
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                .build()
        );

        List<ApplyAction> actionsWithFailure = new ArrayList<>(validPlan.getActions());
        actionsWithFailure.add(ApplyAction.builder()
            .businessType("FAKE_TYPE")
            .businessKey("FAKE-001")
            .tableName("non_existent_table")
            .dependencyLevel(0)
            .recordBusinessKey("FAKE-001")
            .diffType(DiffType.INSERT)
            .build());

        ApplyPlan planWithFailure = ApplyPlan.builder()
            .planId(validPlan.getPlanId())
            .sessionId(validPlan.getSessionId())
            .direction(validPlan.getDirection())
            .options(validPlan.getOptions())
            .actions(actionsWithFailure)
            .statistics(validPlan.getStatistics())
            .build();

        assertThrows(Exception.class, () -> applyService.execute(planWithFailure));

        Integer prod003Count = externalJdbc.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-003'",
            Integer.class
        );
        assertEquals(0, prod003Count, "外部数据源事务应回滚，不能留下 PROD-003");

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM xai_tenant_diff_apply_record WHERE session_id = ? ORDER BY id DESC LIMIT 1",
            String.class, sessionId
        );
        assertEquals(ApplyRecordStatus.FAILED.name(), status);

        String targetDataSourceKey = jdbcTemplate.queryForObject(
            "SELECT target_data_source_key FROM xai_tenant_diff_apply_record WHERE session_id = ? ORDER BY id DESC LIMIT 1",
            String.class, sessionId
        );
        assertEquals("ext", targetDataSourceKey);

        String failureStage = jdbcTemplate.queryForObject(
            "SELECT failure_stage FROM xai_tenant_diff_apply_record WHERE session_id = ? ORDER BY id DESC LIMIT 1",
            String.class, sessionId
        );
        assertNotNull(failureStage);
        assertTrue(!failureStage.isBlank());
    }
}
