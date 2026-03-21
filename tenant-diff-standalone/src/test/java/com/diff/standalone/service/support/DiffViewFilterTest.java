package com.diff.standalone.service.support;

import com.diff.core.domain.diff.*;
import com.diff.core.domain.schema.BusinessSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiffViewFilterTest {

    private static RecordDiff record(String key, DiffType type,
                                     Map<String, Object> source,
                                     Map<String, Object> target) {
        return RecordDiff.builder()
            .recordBusinessKey(key)
            .diffType(type)
            .decision(DecisionType.ACCEPT)
            .sourceFields(source)
            .targetFields(target)
            .fieldDiffs(type == DiffType.UPDATE
                ? List.of(FieldDiff.builder()
                    .fieldName("price")
                    .sourceValue(100)
                    .targetValue(200)
                    .changeDescription("from [200] to [100]")
                    .build())
                : null)
            .build();
    }

    private static TableDiff table(String name, int level, List<RecordDiff> records) {
        return TableDiff.builder()
            .tableName(name)
            .dependencyLevel(level)
            .recordDiffs(records)
            .build();
    }

    private static BusinessDiff business(String type, String key, List<TableDiff> tables) {
        return BusinessDiff.builder()
            .businessType(type)
            .businessTable("main_table")
            .businessKey(key)
            .businessName("test")
            .diffType(DiffType.UPDATE)
            .statistics(DiffStatistics.builder()
                .insertCount(1).updateCount(1).deleteCount(1).noopCount(2)
                .totalRecords(5).totalTables(1).totalBusinesses(1)
                .build())
            .tableDiffs(tables)
            .build();
    }

    private static final Map<String, Object> SRC_FIELDS = Map.of(
        "id", 1L, "tenantsid", 100L, "code", "C001", "name", "测试", "price", 100
    );
    private static final Map<String, Object> TGT_FIELDS = Map.of(
        "id", 2L, "tenantsid", 200L, "code", "C001", "name", "测试", "price", 200
    );

    private static final BusinessSchema SCHEMA_WITH_SHOW = BusinessSchema.builder()
        .showFieldsByTable(Map.of(
            "main_table", List.of("code", "name", "price")
        ))
        .build();

    @Nested
    @DisplayName("null / 空输入处理")
    class NullSafety {

        @Test
        @DisplayName("null BusinessDiff → 返回 null")
        void nullInput() {
            assertNull(DiffViewFilter.filterAndProject(null, SCHEMA_WITH_SHOW, false));
        }

        @Test
        @DisplayName("空 tableDiffs → 返回空结构")
        void emptyTableDiffs() {
            BusinessDiff input = business("T", "K", Collections.emptyList());
            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);
            assertNotNull(result);
            assertTrue(result.getTableDiffs().isEmpty());
            assertEquals(0, result.getStatistics().getTotalRecords());
        }

        @Test
        @DisplayName("null schema → showFields 为 null，NOOP 仍被过滤")
        void nullSchema() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.INSERT, SRC_FIELDS, null),
                    record("R2", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS)
                ))
            ));
            BusinessDiff result = DiffViewFilter.filterAndProject(input, null, false);
            assertEquals(1, result.getTableDiffs().get(0).getRecordDiffs().size());
            assertNull(result.getTableDiffs().get(0).getRecordDiffs().get(0).getShowFields());
        }
    }

    @Nested
    @DisplayName("NOOP 过滤")
    class NoopFiltering {

        @Test
        @DisplayName("NOOP 记录被过滤，非 NOOP 保留")
        void filtersNoopRecords() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.INSERT, SRC_FIELDS, null),
                    record("R2", DiffType.UPDATE, SRC_FIELDS, TGT_FIELDS),
                    record("R3", DiffType.DELETE, null, TGT_FIELDS),
                    record("R4", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS),
                    record("R5", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            List<RecordDiff> records = result.getTableDiffs().get(0).getRecordDiffs();
            assertEquals(3, records.size());
            assertTrue(records.stream().noneMatch(r -> r.getDiffType() == DiffType.NOOP));
        }

        @Test
        @DisplayName("全部 NOOP → recordDiffs 为空列表")
        void allNoop() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS),
                    record("R2", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);
            assertTrue(result.getTableDiffs().get(0).getRecordDiffs().isEmpty());
        }
    }

    @Nested
    @DisplayName("统计重算")
    class StatisticsRecalculation {

        @Test
        @DisplayName("过滤后 counts 和 statistics 正确重算，noopCount=0")
        void recalculated() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.INSERT, SRC_FIELDS, null),
                    record("R2", DiffType.UPDATE, SRC_FIELDS, TGT_FIELDS),
                    record("R3", DiffType.DELETE, null, TGT_FIELDS),
                    record("R4", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            TableDiff.TableDiffCounts counts = result.getTableDiffs().get(0).getCounts();
            assertEquals(1, counts.getInsertCount());
            assertEquals(1, counts.getUpdateCount());
            assertEquals(1, counts.getDeleteCount());
            assertEquals(0, counts.getNoopCount());

            DiffStatistics stats = result.getStatistics();
            assertEquals(3, stats.getTotalRecords());
            assertEquals(1, stats.getInsertCount());
            assertEquals(1, stats.getUpdateCount());
            assertEquals(1, stats.getDeleteCount());
            assertEquals(0, stats.getNoopCount());
        }
    }

    @Nested
    @DisplayName("showFields 投影")
    class ShowFieldsProjection {

        @Test
        @DisplayName("INSERT → 从 sourceFields 投影 showFields")
        void insertProjectsFromSource() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.INSERT, SRC_FIELDS, null)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            Map<String, Object> showFields = result.getTableDiffs().get(0).getRecordDiffs().get(0).getShowFields();
            assertNotNull(showFields);
            assertEquals("C001", showFields.get("code"));
            assertEquals("测试", showFields.get("name"));
            assertEquals(100, showFields.get("price"));
            assertFalse(showFields.containsKey("id"), "系统字段不应出现在 showFields");
            assertFalse(showFields.containsKey("tenantsid"), "系统字段不应出现在 showFields");
        }

        @Test
        @DisplayName("DELETE → 从 targetFields 投影 showFields")
        void deleteProjectsFromTarget() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.DELETE, null, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            Map<String, Object> showFields = result.getTableDiffs().get(0).getRecordDiffs().get(0).getShowFields();
            assertNotNull(showFields);
            assertEquals(200, showFields.get("price"));
        }

        @Test
        @DisplayName("UPDATE → 从 sourceFields 投影 showFields")
        void updateProjectsFromSource() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.UPDATE, SRC_FIELDS, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            Map<String, Object> showFields = result.getTableDiffs().get(0).getRecordDiffs().get(0).getShowFields();
            assertNotNull(showFields);
            assertEquals(100, showFields.get("price"));
        }

        @Test
        @DisplayName("showFields 保留字段顺序")
        void preservesFieldOrder() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.INSERT, SRC_FIELDS, null)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            Map<String, Object> showFields = result.getTableDiffs().get(0).getRecordDiffs().get(0).getShowFields();
            List<String> keys = List.copyOf(showFields.keySet());
            assertEquals(List.of("code", "name", "price"), keys);
        }

        @Test
        @DisplayName("未配置 showFieldsByTable 的表 → showFields 为 null")
        void unconfiguredTableReturnsNull() {
            BusinessDiff input = business("T", "K", List.of(
                table("other_table", 1, List.of(
                    record("R1", DiffType.INSERT, SRC_FIELDS, null)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);
            assertNull(result.getTableDiffs().get(0).getRecordDiffs().get(0).getShowFields());
        }
    }

    @Nested
    @DisplayName("大字段裁剪")
    class RawFieldStripping {

        @Test
        @DisplayName("stripRawFields=false → sourceFields/targetFields 保留")
        void preservesRawFields() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.UPDATE, SRC_FIELDS, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            RecordDiff rd = result.getTableDiffs().get(0).getRecordDiffs().get(0);
            assertNotNull(rd.getSourceFields());
            assertNotNull(rd.getTargetFields());
        }

        @Test
        @DisplayName("stripRawFields=true → sourceFields/targetFields 为 null，showFields 保留")
        void stripsRawFields() {
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(
                    record("R1", DiffType.UPDATE, SRC_FIELDS, TGT_FIELDS)
                ))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, true);

            RecordDiff rd = result.getTableDiffs().get(0).getRecordDiffs().get(0);
            assertNull(rd.getSourceFields());
            assertNull(rd.getTargetFields());
            assertNotNull(rd.getShowFields());
        }
    }

    @Nested
    @DisplayName("入参不可变性")
    class Immutability {

        @Test
        @DisplayName("过滤后原始 BusinessDiff 未被修改")
        void originalUnmodified() {
            List<RecordDiff> originalRecords = List.of(
                record("R1", DiffType.INSERT, SRC_FIELDS, null),
                record("R2", DiffType.NOOP, SRC_FIELDS, TGT_FIELDS)
            );
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, originalRecords)
            ));

            DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, true);

            assertEquals(2, input.getTableDiffs().get(0).getRecordDiffs().size(),
                "原始对象的 recordDiffs 数量不应改变");
            assertNotNull(input.getTableDiffs().get(0).getRecordDiffs().get(0).getSourceFields(),
                "原始对象的 sourceFields 不应被置 null");
        }
    }

    @Nested
    @DisplayName("FieldDiff 共享引用")
    class FieldDiffSharing {

        @Test
        @DisplayName("过滤后 fieldDiffs 与原始对象共享同一引用")
        void fieldDiffsShared() {
            RecordDiff updateRecord = record("R1", DiffType.UPDATE, SRC_FIELDS, TGT_FIELDS);
            BusinessDiff input = business("T", "K", List.of(
                table("main_table", 0, List.of(updateRecord))
            ));

            BusinessDiff result = DiffViewFilter.filterAndProject(input, SCHEMA_WITH_SHOW, false);

            assertSame(
                updateRecord.getFieldDiffs(),
                result.getTableDiffs().get(0).getRecordDiffs().get(0).getFieldDiffs(),
                "fieldDiffs 应共享引用以节省内存");
        }
    }
}
