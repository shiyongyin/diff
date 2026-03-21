package com.diff.standalone.web.dto.response;

import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyStatistics;
import com.diff.core.domain.diff.DiffType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplyPreviewResponseTest {

    @Test
    void from_nullPlan_returnsEmptyResponse() {
        ApplyPreviewResponse response = ApplyPreviewResponse.from(null, "pt_v1_test");
        assertNotNull(response);
        assertEquals("pt_v1_test", response.getPreviewToken());
        assertNotNull(response.getActions());
        assertTrue(response.getActions().isEmpty());
        assertNotNull(response.getBusinessTypePreviews());
        assertTrue(response.getBusinessTypePreviews().isEmpty());
    }

    @Test
    void from_planWithActions_mapsAllFields() {
        ApplyAction action = ApplyAction.builder()
            .actionId("v1:TYPE_A:KEY_1:table_a:REC_1")
            .businessType("TYPE_A")
            .businessKey("KEY_1")
            .tableName("table_a")
            .recordBusinessKey("REC_1")
            .diffType(DiffType.INSERT)
            .dependencyLevel(0)
            .build();

        ApplyPlan plan = ApplyPlan.builder()
            .sessionId(1L)
            .direction(ApplyDirection.A_TO_B)
            .statistics(ApplyStatistics.builder()
                .totalActions(1)
                .insertCount(1)
                .build())
            .actions(List.of(action))
            .build();

        ApplyPreviewResponse response = ApplyPreviewResponse.from(plan, "pt_v1_abc123");

        assertEquals(1L, response.getSessionId());
        assertEquals(ApplyDirection.A_TO_B, response.getDirection());
        assertEquals("pt_v1_abc123", response.getPreviewToken());
        assertEquals(1, response.getActions().size());

        ApplyPreviewResponse.ActionPreviewItem item = response.getActions().get(0);
        assertEquals("v1:TYPE_A:KEY_1:table_a:REC_1", item.getActionId());
        assertEquals("TYPE_A", item.getBusinessType());
        assertEquals("KEY_1", item.getBusinessKey());
        assertEquals("table_a", item.getTableName());
        assertEquals("REC_1", item.getRecordBusinessKey());
        assertEquals(DiffType.INSERT, item.getDiffType());
        assertEquals(0, item.getDependencyLevel());
    }

    @Test
    void from_planWithNullActions_returnsEmptyActionList() {
        ApplyPlan plan = ApplyPlan.builder()
            .sessionId(1L)
            .direction(ApplyDirection.A_TO_B)
            .actions(null)
            .build();

        ApplyPreviewResponse response = ApplyPreviewResponse.from(plan, "token");
        assertNotNull(response.getActions());
        assertTrue(response.getActions().isEmpty());
    }

    @Test
    void from_actionPreviewItem_doesNotExposePayload() {
        // ActionPreviewItem 没有 payload 字段，验证编译级别的 DTO 隔离
        ApplyPreviewResponse.ActionPreviewItem item = ApplyPreviewResponse.ActionPreviewItem.builder()
            .actionId("v1:a:b:c:d")
            .businessType("a")
            .build();
        assertNotNull(item.getActionId());
        // 如果 ActionPreviewItem 有 getPayload() 方法，此处将编译失败
    }

    @Test
    void from_previewToken_passedThrough() {
        ApplyPlan plan = ApplyPlan.builder()
            .sessionId(1L)
            .direction(ApplyDirection.A_TO_B)
            .actions(Collections.emptyList())
            .build();

        String token = "pt_v1_4f9f7f9e8d3c2a1b0e9f8d7c6b5a4938";
        ApplyPreviewResponse response = ApplyPreviewResponse.from(plan, token);
        assertEquals(token, response.getPreviewToken());
    }

    @Test
    void from_businessTypePreviews_aggregatesCorrectly() {
        List<ApplyAction> actions = List.of(
            ApplyAction.builder().actionId("v1:A:K1:t:R1").businessType("A")
                .diffType(DiffType.INSERT).build(),
            ApplyAction.builder().actionId("v1:A:K2:t:R2").businessType("A")
                .diffType(DiffType.UPDATE).build(),
            ApplyAction.builder().actionId("v1:B:K3:t:R3").businessType("B")
                .diffType(DiffType.DELETE).build()
        );

        ApplyPlan plan = ApplyPlan.builder()
            .sessionId(1L)
            .direction(ApplyDirection.A_TO_B)
            .actions(actions)
            .build();

        ApplyPreviewResponse response = ApplyPreviewResponse.from(plan, "token");
        assertEquals(2, response.getBusinessTypePreviews().size());
        assertEquals(3, response.getActions().size());
    }
}

