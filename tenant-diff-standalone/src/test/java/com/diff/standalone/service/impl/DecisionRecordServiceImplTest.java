package com.diff.standalone.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.persistence.mapper.TenantDiffDecisionRecordMapper;
import com.diff.standalone.web.dto.request.DecisionItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DecisionRecordServiceImplTest {

    @Mock
    private TenantDiffDecisionRecordMapper mapper;

    private DecisionRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DecisionRecordServiceImpl(mapper);
    }

    @Nested
    @DisplayName("saveDecisions")
    class SaveDecisions {

        @Test
        @DisplayName("空列表 → 返回 0，不调用 mapper")
        void emptyList() {
            assertEquals(0, service.saveDecisions(1L, "T", "K", Collections.emptyList()));
            verify(mapper, never()).insert(any(TenantDiffDecisionRecordPo.class));
            verify(mapper, never()).updateById(any(TenantDiffDecisionRecordPo.class));
        }

        @Test
        @DisplayName("null 列表 → 返回 0")
        void nullList() {
            assertEquals(0, service.saveDecisions(1L, "T", "K", null));
        }

        @Test
        @DisplayName("新增决策 → 调用 insert")
        void insertNew() {
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(mapper.insert(any(TenantDiffDecisionRecordPo.class))).thenReturn(1);

            DecisionItem item = DecisionItem.builder()
                .tableName("main_table")
                .recordBusinessKey("R1")
                .decision("ACCEPT")
                .decisionReason("looks good")
                .build();

            int result = service.saveDecisions(1L, "ORDER", "ORD-001", List.of(item));
            assertEquals(1, result);

            ArgumentCaptor<TenantDiffDecisionRecordPo> captor =
                ArgumentCaptor.forClass(TenantDiffDecisionRecordPo.class);
            verify(mapper).insert(captor.capture());

            TenantDiffDecisionRecordPo po = captor.getValue();
            assertEquals(1L, po.getSessionId());
            assertEquals("ORDER", po.getBusinessType());
            assertEquals("ORD-001", po.getBusinessKey());
            assertEquals("main_table", po.getTableName());
            assertEquals("R1", po.getRecordBusinessKey());
            assertEquals("ACCEPT", po.getDecision());
            assertEquals("looks good", po.getDecisionReason());
            assertNotNull(po.getDecisionTime());
            assertNotNull(po.getCreatedAt());
        }

        @Test
        @DisplayName("已存在 → 调用 updateById（upsert）")
        void upsertExisting() {
            TenantDiffDecisionRecordPo existing = TenantDiffDecisionRecordPo.builder()
                .id(42L)
                .sessionId(1L)
                .businessType("ORDER")
                .businessKey("ORD-001")
                .tableName("main_table")
                .recordBusinessKey("R1")
                .decision("ACCEPT")
                .build();
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(mapper.updateById(any(TenantDiffDecisionRecordPo.class))).thenReturn(1);

            DecisionItem item = DecisionItem.builder()
                .tableName("main_table")
                .recordBusinessKey("R1")
                .decision("SKIP")
                .decisionReason("not needed")
                .build();

            int result = service.saveDecisions(1L, "ORDER", "ORD-001", List.of(item));
            assertEquals(1, result);

            verify(mapper, never()).insert(any(TenantDiffDecisionRecordPo.class));
            verify(mapper).updateById(any(TenantDiffDecisionRecordPo.class));
            assertEquals("SKIP", existing.getDecision());
            assertEquals("not needed", existing.getDecisionReason());
        }

        @Test
        @DisplayName("混合 insert/update → 返回总数")
        void mixedInsertUpdate() {
            when(mapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null)
                .thenReturn(TenantDiffDecisionRecordPo.builder()
                    .id(99L).decision("ACCEPT").build());
            when(mapper.insert(any(TenantDiffDecisionRecordPo.class))).thenReturn(1);
            when(mapper.updateById(any(TenantDiffDecisionRecordPo.class))).thenReturn(1);

            List<DecisionItem> items = List.of(
                DecisionItem.builder().tableName("t1").recordBusinessKey("R1").decision("ACCEPT").build(),
                DecisionItem.builder().tableName("t1").recordBusinessKey("R2").decision("SKIP").build()
            );

            assertEquals(2, service.saveDecisions(1L, "T", "K", items));
            verify(mapper, times(1)).insert(any(TenantDiffDecisionRecordPo.class));
            verify(mapper, times(1)).updateById(any(TenantDiffDecisionRecordPo.class));
        }
    }

    @Nested
    @DisplayName("listDecisions")
    class ListDecisions {

        @Test
        @DisplayName("调用 mapper.selectList 并返回结果")
        void delegatesToMapper() {
            List<TenantDiffDecisionRecordPo> expected = List.of(
                TenantDiffDecisionRecordPo.builder().id(1L).decision("ACCEPT").build()
            );
            when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(expected);

            List<TenantDiffDecisionRecordPo> result =
                service.listDecisions(1L, "ORDER", "ORD-001");

            assertSame(expected, result);
            verify(mapper).selectList(any(LambdaQueryWrapper.class));
        }
    }
}
