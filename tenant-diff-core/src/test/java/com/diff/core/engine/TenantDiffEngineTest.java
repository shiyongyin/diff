package com.diff.core.engine;

import com.diff.core.domain.diff.*;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.model.RecordData;
import com.diff.core.domain.model.TableData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TenantDiffEngineTest {

    private final TenantDiffEngine engine = new TenantDiffEngine();

    // ---- helpers ----

    private static RecordData record(String businessKey, Map<String, Object> fields) {
        return RecordData.builder()
            .businessKey(businessKey)
            .fields(fields)
            .build();
    }

    private static RecordData recordWithFingerprint(String businessKey, Map<String, Object> fields, String fingerprint) {
        return RecordData.builder()
            .businessKey(businessKey)
            .fields(fields)
            .fingerprint(fingerprint)
            .build();
    }

    private static TableData table(String tableName, int depLevel, List<RecordData> records) {
        return TableData.builder()
            .tableName(tableName)
            .dependencyLevel(depLevel)
            .records(records)
            .build();
    }

    private static BusinessData business(String type, String key, List<TableData> tables) {
        return BusinessData.builder()
            .businessType(type)
            .businessTable(type)
            .businessKey(key)
            .tables(tables)
            .build();
    }

    // ---- tests ----

    @Test
    void bothSidesIdentical_allNoop() {
        Map<String, Object> fields = Map.of("name", "A", "price", 100);
        RecordData r = record("k1", fields);
        BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(r))));
        BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A", "price", 100))))));

        TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b));

        assertEquals(1, result.businessDiffs().size());
        RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
        assertEquals(DiffType.NOOP, rd.getDiffType());
    }

    @Test
    void sourceHasTargetMissing_insert() {
        BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));

        TenantDiffEngine.CompareResult result = engine.compare(List.of(a), Collections.emptyList());

        assertEquals(1, result.businessDiffs().size());
        assertEquals(DiffType.BUSINESS_INSERT, result.businessDiffs().get(0).getDiffType());
        RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
        assertEquals(DiffType.INSERT, rd.getDiffType());
    }

    @Test
    void sourceMissingTargetHas_delete() {
        BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));

        TenantDiffEngine.CompareResult result = engine.compare(Collections.emptyList(), List.of(b));

        assertEquals(1, result.businessDiffs().size());
        assertEquals(DiffType.BUSINESS_DELETE, result.businessDiffs().get(0).getDiffType());
    }

    @Test
    void sameKeyDifferentFields_update() {
        BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));
        BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "B"))))));

        TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b));

        RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
        assertEquals(DiffType.UPDATE, rd.getDiffType());
        assertNotNull(rd.getFieldDiffs());
        assertEquals(1, rd.getFieldDiffs().size());
        assertEquals("name", rd.getFieldDiffs().get(0).getFieldName());
    }

    @Test
    void emptyVsEmpty_noException() {
        TenantDiffEngine.CompareResult result = engine.compare(Collections.emptyList(), Collections.emptyList());

        assertNotNull(result);
        assertTrue(result.businessDiffs().isEmpty());
        assertEquals(0, result.statistics().getTotalRecords());
    }

    @Test
    void fingerprintMatch_skipsFieldComparison() {
        String fp = "same-fingerprint";
        RecordData r1 = recordWithFingerprint("k1", Map.of("name", "A", "extra", "x"), fp);
        RecordData r2 = recordWithFingerprint("k1", Map.of("name", "B", "extra", "y"), fp);
        BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(r1))));
        BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(r2))));

        TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b));

        RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
        assertEquals(DiffType.NOOP, rd.getDiffType());
        assertNull(rd.getFieldDiffs());
    }

    @Test
    void ignoreFieldsWork() {
        Map<String, Object> srcFields = new LinkedHashMap<>();
        srcFields.put("name", "A");
        srcFields.put("custom_ignore", "old");

        Map<String, Object> tgtFields = new LinkedHashMap<>();
        tgtFields.put("name", "A");
        tgtFields.put("custom_ignore", "new");

        BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", srcFields)))));
        BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", tgtFields)))));

        DiffRules rules = DiffRules.builder()
            .defaultIgnoreFields(Set.of())
            .ignoreFieldsByTable(Map.of("t1", Set.of("custom_ignore")))
            .build();

        TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);

        RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
        assertEquals(DiffType.NOOP, rd.getDiffType());
    }

    @Test
    void multiTableMultiBusiness_sortedCorrectly() {
        TableData parentTable = table("parent_t", 0, List.of(record("pk1", Map.of("v", "1"))));
        TableData childTable = table("child_t", 1, List.of(record("ck1", Map.of("v", "2"))));

        BusinessData a1 = business("T", "BK_B", List.of(parentTable, childTable));
        BusinessData a2 = business("T", "BK_A", List.of(table("parent_t", 0, List.of(record("pk1", Map.of("v", "1"))))));

        TenantDiffEngine.CompareResult result = engine.compare(List.of(a1, a2), Collections.emptyList());

        assertEquals(2, result.businessDiffs().size());
        assertEquals(2, result.statistics().getTotalBusinesses());

        // BK_B has 2 tables, sorted by dependency level
        BusinessDiff bkB = result.businessDiffs().stream()
            .filter(d -> "BK_B".equals(d.getBusinessKey()))
            .findFirst().orElseThrow();
        assertEquals(2, bkB.getTableDiffs().size());
        assertEquals(0, bkB.getTableDiffs().get(0).getDependencyLevel());
        assertEquals(1, bkB.getTableDiffs().get(1).getDependencyLevel());
    }

    // ================================================================
    // Phase 1.1 补全：空数据边界
    // ================================================================

    @Nested
    @DisplayName("空数据边界")
    class EmptyDataBoundary {

        @Test
        @DisplayName("null 输入等同于空列表，不抛异常")
        void nullInputs_treatedAsEmpty() {
            TenantDiffEngine.CompareResult result = engine.compare(null, null);
            assertNotNull(result);
            assertTrue(result.businessDiffs().isEmpty());
        }

        @Test
        @DisplayName("一方为 null、另一方有数据")
        void nullVsData_producesInsertOrDelete() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));

            // source null, target has data → DELETE
            TenantDiffEngine.CompareResult r1 = engine.compare(null, List.of(a));
            assertEquals(1, r1.businessDiffs().size());
            assertEquals(DiffType.BUSINESS_DELETE, r1.businessDiffs().get(0).getDiffType());

            // source has data, target null → INSERT
            TenantDiffEngine.CompareResult r2 = engine.compare(List.of(a), null);
            assertEquals(1, r2.businessDiffs().size());
            assertEquals(DiffType.BUSINESS_INSERT, r2.businessDiffs().get(0).getDiffType());
        }

        @Test
        @DisplayName("源有记录但字段为空 Map，目标有记录但字段也为空 Map → NOOP")
        void emptyFieldsMaps_noop() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of())))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of())))));

            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b));
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("源有字段、目标字段为空 Map → UPDATE（字段在源存在目标不存在）")
        void sourceHasFields_targetEmpty_update() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of())))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.UPDATE, rd.getDiffType());
            assertEquals(1, rd.getFieldDiffs().size());
            assertEquals("name", rd.getFieldDiffs().get(0).getFieldName());
        }
    }

    // ================================================================
    // Phase 1.1 补全：字段类型差异
    // ================================================================

    @Nested
    @DisplayName("字段类型差异")
    class FieldTypeDifference {

        @Test
        @DisplayName("字符串 \"123\" vs 数字 123 → NOOP（指纹通过 toString 归一化，值语义相同）")
        void stringVsNumber_noopViaFingerprint() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("val", "123"))))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("val", 123))))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            // MD5 指纹使用 toString() 归一化："123" 和 123 的 toString 都是 "123" → 指纹相同 → NOOP
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("Long vs Integer 同值 → NOOP（指纹通过 toString 归一化）")
        void longVsInteger_noopViaFingerprint() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("val", 100L))))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("val", 100))))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("绕过指纹优化时，String vs Integer 被 Objects.equals 判为不同 → UPDATE")
        void stringVsNumber_updateWhenFingerprintMismatch() {
            // 预设不同的指纹，强制走字段级对比
            RecordData r1 = recordWithFingerprint("k1", Map.of("val", "123"), "fp-A");
            RecordData r2 = recordWithFingerprint("k1", Map.of("val", 123), "fp-B");

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(r1))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(r2))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            // 指纹不同 → 走字段对比 → Objects.equals("123", 123) = false → UPDATE
            assertEquals(DiffType.UPDATE, rd.getDiffType());
        }

        @Test
        @DisplayName("null vs 空字符串 → UPDATE")
        void nullVsEmptyString_treatedAsUpdate() {
            Map<String, Object> srcFields = new HashMap<>();
            srcFields.put("name", null);
            Map<String, Object> tgtFields = new HashMap<>();
            tgtFields.put("name", "");

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", srcFields)))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", tgtFields)))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.UPDATE, rd.getDiffType());
        }
    }

    // ================================================================
    // Phase 1.1 补全：ignoreFields 边界
    // ================================================================

    @Nested
    @DisplayName("ignoreFields 边界")
    class IgnoreFieldsBoundary {

        @Test
        @DisplayName("所有字段都被忽略 → NOOP（即使字段值不同）")
        void allFieldsIgnored_noop() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("a", "1", "b", "2"))))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("a", "X", "b", "Y"))))));

            DiffRules rules = DiffRules.builder()
                .defaultIgnoreFields(Set.of("a", "b"))
                .ignoreFieldsByTable(Map.of())
                .build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("忽略字段列表包含不存在的字段 → 不报错，正常对比其他字段")
        void ignoreNonExistentField_noError() {
            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));

            DiffRules rules = DiffRules.builder()
                .defaultIgnoreFields(Set.of("non_existent_field_1", "non_existent_field_2"))
                .ignoreFieldsByTable(Map.of())
                .build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("默认忽略字段（id/tenantsid/version）自动生效")
        void defaultIgnoreFields_automaticallyApplied() {
            // id/tenantsid/version 不同，但它们在默认忽略列表中
            Map<String, Object> srcFields = new LinkedHashMap<>();
            srcFields.put("id", 1L);
            srcFields.put("tenantsid", 100L);
            srcFields.put("version", 1);
            srcFields.put("name", "A");

            Map<String, Object> tgtFields = new LinkedHashMap<>();
            tgtFields.put("id", 999L);
            tgtFields.put("tenantsid", 200L);
            tgtFields.put("version", 5);
            tgtFields.put("name", "A");

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", srcFields)))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", tgtFields)))));

            // 使用默认规则
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b));
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }
    }

    // ================================================================
    // Phase 1.1 补全：businessKey 冲突/重复
    // ================================================================

    @Nested
    @DisplayName("businessKey 冲突与重复")
    class BusinessKeyConflict {

        @Test
        @DisplayName("目标侧重复 businessKey → 第一条生效（putIfAbsent）")
        void duplicateTargetKeys_firstWins() {
            RecordData r1 = record("k1", Map.of("name", "first"));
            RecordData r2 = record("k1", Map.of("name", "second"));

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "first"))))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(r1, r2))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            // 目标侧 "first" 和源侧 "first" 相同 → NOOP（证明第一条生效）
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("源侧重复 businessKey → 第二条因 target 已被消费而变成 INSERT")
        void duplicateSourceKeys_secondBecomesInsert() {
            RecordData src1 = record("k1", Map.of("name", "A"));
            RecordData src2 = record("k1", Map.of("name", "B"));

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(src1, src2))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);

            List<RecordDiff> diffs = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs();
            assertEquals(2, diffs.size());
            // 第一条与 target 匹配 → NOOP
            // 第二条找不到 target（已被第一条消费）→ INSERT
            long insertCount = diffs.stream().filter(d -> d.getDiffType() == DiffType.INSERT).count();
            assertEquals(1, insertCount, "第二条重复 key 应产生 INSERT");
        }

        @Test
        @DisplayName("null businessKey 的记录被跳过，不参与对比")
        void nullBusinessKey_skipped() {
            RecordData nullKeyRecord = record(null, Map.of("name", "ghost"));
            RecordData normalRecord = record("k1", Map.of("name", "A"));

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(nullKeyRecord, normalRecord))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", Map.of("name", "A"))))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);

            // 只有 k1 参与对比，null key 被跳过
            List<RecordDiff> diffs = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs();
            assertEquals(1, diffs.size());
            assertEquals("k1", diffs.get(0).getRecordBusinessKey());
        }
    }

    // ================================================================
    // Phase 1.1 补全：指纹（fingerprint）
    // ================================================================

    @Nested
    @DisplayName("指纹对比")
    class FingerprintBehavior {

        @Test
        @DisplayName("字段顺序不同但内容相同 → 计算出相同 MD5 → NOOP")
        void sameFieldsDifferentOrder_sameFingerprint() {
            Map<String, Object> srcFields = new LinkedHashMap<>();
            srcFields.put("b", "2");
            srcFields.put("a", "1");

            Map<String, Object> tgtFields = new LinkedHashMap<>();
            tgtFields.put("a", "1");
            tgtFields.put("b", "2");

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", srcFields)))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", tgtFields)))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("预设指纹不同但实际字段相同 → 判定为 NOOP（走字段级对比）")
        void differentPresetFingerprint_sameFields_noop() {
            RecordData r1 = recordWithFingerprint("k1", Map.of("name", "A"), "fp-111");
            RecordData r2 = recordWithFingerprint("k1", Map.of("name", "A"), "fp-222");

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(r1))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(r2))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            // 指纹不同触发字段级对比，但字段实际相同 → NOOP
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }

        @Test
        @DisplayName("ignoreFields 在 MD5 计算中生效：被忽略字段不影响指纹")
        void ignoreFields_excludedFromFingerprint() {
            // 只有 ignore_me 字段不同
            Map<String, Object> srcFields = new LinkedHashMap<>();
            srcFields.put("name", "A");
            srcFields.put("ignore_me", "old");

            Map<String, Object> tgtFields = new LinkedHashMap<>();
            tgtFields.put("name", "A");
            tgtFields.put("ignore_me", "new");

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", srcFields)))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(record("k1", tgtFields)))));

            DiffRules rules = DiffRules.builder()
                .defaultIgnoreFields(Set.of("ignore_me"))
                .ignoreFieldsByTable(Map.of())
                .build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);
            RecordDiff rd = result.businessDiffs().get(0).getTableDiffs().get(0).getRecordDiffs().get(0);
            // ignore_me 被排除后，两侧指纹相同 → NOOP
            assertEquals(DiffType.NOOP, rd.getDiffType());
        }
    }

    // ================================================================
    // Phase 1.1 补全：统计信息验证
    // ================================================================

    @Nested
    @DisplayName("统计信息")
    class StatisticsVerification {

        @Test
        @DisplayName("混合场景统计正确：INSERT + UPDATE + NOOP")
        void mixedScenario_statisticsCorrect() {
            // k1: 两侧相同 → NOOP
            // k2: 不同 → UPDATE
            // k3: 只在源 → INSERT
            RecordData srcK1 = record("k1", Map.of("name", "same"));
            RecordData srcK2 = record("k2", Map.of("name", "A"));
            RecordData srcK3 = record("k3", Map.of("name", "new"));

            RecordData tgtK1 = record("k1", Map.of("name", "same"));
            RecordData tgtK2 = record("k2", Map.of("name", "B"));

            BusinessData a = business("T", "BK1", List.of(table("t1", 0, List.of(srcK1, srcK2, srcK3))));
            BusinessData b = business("T", "BK1", List.of(table("t1", 0, List.of(tgtK1, tgtK2))));

            DiffRules rules = DiffRules.builder().defaultIgnoreFields(Set.of()).ignoreFieldsByTable(Map.of()).build();
            TenantDiffEngine.CompareResult result = engine.compare(List.of(a), List.of(b), rules);

            DiffStatistics stats = result.statistics();
            assertEquals(1, stats.getTotalBusinesses());
            assertEquals(1, stats.getTotalTables());
            assertEquals(3, stats.getTotalRecords());
            assertEquals(1, stats.getInsertCount());
            assertEquals(1, stats.getUpdateCount());
            assertEquals(1, stats.getNoopCount());
            assertEquals(0, stats.getDeleteCount());
        }
    }
}
