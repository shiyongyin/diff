package com.diff.standalone.service.impl;

import com.diff.core.domain.diff.SessionStatus;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.config.TenantDiffProperties;
import com.diff.standalone.model.SessionWarning;
import com.diff.standalone.model.StandaloneTenantModelBuilder;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.web.dto.response.DiffSessionSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantDiffStandaloneServiceImplTest {

    @Mock private TenantDiffSessionMapper sessionMapper;
    @Mock private TenantDiffResultMapper resultMapper;
    @Mock private StandaloneTenantModelBuilder modelBuilder;
    @Mock private com.diff.core.engine.TenantDiffEngine diffEngine;
    @Mock private TransactionTemplate transactionTemplate;

    private TenantDiffStandaloneServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new TenantDiffStandaloneServiceImpl(
            sessionMapper,
            resultMapper,
            modelBuilder,
            diffEngine,
            objectMapper,
            transactionTemplate,
            new TenantDiffProperties()
        );
    }

    @Test
    void getSessionSummary_parsesWarningJson() throws Exception {
        TenantDiffSessionPo session = TenantDiffSessionPo.builder()
            .id(1L)
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .status(SessionStatus.SUCCESS.name())
            .warningJson(objectMapper.writeValueAsString(List.of(
                new SessionWarning("source", "TYPE_A", "BK-1", "加载业务数据失败: boom")
            )))
            .build();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(resultMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        DiffSessionSummaryResponse summary = service.getSessionSummary(1L);

        assertEquals(1, summary.getWarningCount());
        assertEquals(1, summary.getWarnings().size());
        assertEquals("source", summary.getWarnings().get(0).side());
        assertEquals("TYPE_A", summary.getWarnings().get(0).businessType());
    }

    @Test
    void runCompare_whenSessionApplying_throwsSessionCompareConflict() {
        TenantDiffSessionPo session = TenantDiffSessionPo.builder()
            .id(1L)
            .status(SessionStatus.APPLYING.name())
            .build();
        when(sessionMapper.selectById(1L)).thenReturn(session);

        TenantDiffException ex = assertThrows(TenantDiffException.class, () -> service.runCompare(1L));

        assertEquals(ErrorCode.SESSION_COMPARE_CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("无法重跑对比"));
    }
}
