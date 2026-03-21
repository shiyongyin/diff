package com.diff.standalone.service.support;

import com.diff.core.domain.diff.*;
import com.diff.core.domain.schema.BusinessSchema;

import java.util.*;

/**
 * Diff 视图过滤工具——将引擎原始输出转换为前端友好的视图。
 *
 * <p>
 * 提供三种能力的组合：
 * <ol>
 *     <li><b>NOOP 过滤</b>：移除 {@link DiffType#NOOP} 记录</li>
 *     <li><b>showFields 投影</b>：按 {@link BusinessSchema#getShowFieldsByTable()} 投影展示字段</li>
 *     <li><b>大字段裁剪</b>：可选将 sourceFields/targetFields 置为 {@code null}</li>
 * </ol>
 * 过滤后重算 {@link TableDiff.TableDiffCounts} 和 {@link DiffStatistics}。
 * </p>
 *
 * <p>全部 static 方法，无状态，线程安全。构建新对象树返回，不修改入参。
 * 对不可变子对象（{@link FieldDiff} 等）共享引用而非深拷贝。</p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see DiffDetailView
 */
public final class DiffViewFilter {

    private DiffViewFilter() {}

    /**
     * 过滤 NOOP 记录、投影 showFields、可选裁剪 sourceFields/targetFields。
     *
     * @param original       原始 BusinessDiff（不会被修改）
     * @param schema         业务 Schema（用于 showFieldsByTable，可为 {@code null}）
     * @param stripRawFields 是否将 sourceFields/targetFields 置为 {@code null}
     * @return 过滤后的新 BusinessDiff；入参为 {@code null} 时返回 {@code null}
     */
    public static BusinessDiff filterAndProject(
            BusinessDiff original,
            BusinessSchema schema,
            boolean stripRawFields) {
        if (original == null) {
            return null;
        }

        Map<String, List<String>> showConfig =
            (schema != null && schema.getShowFieldsByTable() != null)
                ? schema.getShowFieldsByTable()
                : Collections.emptyMap();

        List<TableDiff> newTableDiffs = new ArrayList<>();
        int totalInsert = 0, totalUpdate = 0, totalDelete = 0;

        if (original.getTableDiffs() != null) {
            for (TableDiff td : original.getTableDiffs()) {
                if (td == null) {
                    continue;
                }

                List<RecordDiff> filtered = new ArrayList<>();
                if (td.getRecordDiffs() != null) {
                    for (RecordDiff rd : td.getRecordDiffs()) {
                        if (rd == null || rd.getDiffType() == DiffType.NOOP) {
                            continue;
                        }
                        filtered.add(projectRecord(
                            rd, td.getTableName(), showConfig, stripRawFields));
                    }
                }

                TableDiff.TableDiffCounts counts = recount(filtered);
                totalInsert += safeInt(counts.getInsertCount());
                totalUpdate += safeInt(counts.getUpdateCount());
                totalDelete += safeInt(counts.getDeleteCount());

                newTableDiffs.add(TableDiff.builder()
                    .tableName(td.getTableName())
                    .dependencyLevel(td.getDependencyLevel())
                    .diffType(td.getDiffType())
                    .counts(counts)
                    .recordDiffs(filtered)
                    .build());
            }
        }

        int totalRecords = totalInsert + totalUpdate + totalDelete;
        DiffStatistics newStats = DiffStatistics.builder()
            .totalBusinesses(1)
            .totalTables(newTableDiffs.size())
            .totalRecords(totalRecords)
            .insertCount(totalInsert)
            .updateCount(totalUpdate)
            .deleteCount(totalDelete)
            .noopCount(0)
            .build();

        return BusinessDiff.builder()
            .businessType(original.getBusinessType())
            .businessTable(original.getBusinessTable())
            .businessKey(original.getBusinessKey())
            .businessName(original.getBusinessName())
            .diffType(original.getDiffType())
            .statistics(newStats)
            .tableDiffs(newTableDiffs)
            .build();
    }

    private static RecordDiff projectRecord(
            RecordDiff rd, String tableName,
            Map<String, List<String>> showConfig,
            boolean stripRawFields) {
        RecordDiff.RecordDiffBuilder builder = RecordDiff.builder()
            .recordBusinessKey(rd.getRecordBusinessKey())
            .diffType(rd.getDiffType())
            .decision(rd.getDecision())
            .decisionReason(rd.getDecisionReason())
            .decisionTime(rd.getDecisionTime())
            .fieldDiffs(rd.getFieldDiffs())
            .warnings(rd.getWarnings())
            .showFields(buildShowFields(rd, tableName, showConfig));

        if (stripRawFields) {
            builder.sourceFields(null).targetFields(null);
        } else {
            builder.sourceFields(rd.getSourceFields())
                   .targetFields(rd.getTargetFields());
        }
        return builder.build();
    }

    private static Map<String, Object> buildShowFields(
            RecordDiff rd, String tableName,
            Map<String, List<String>> showConfig) {
        List<String> fieldNames = showConfig.get(tableName);
        if (fieldNames == null || fieldNames.isEmpty()) {
            return null;
        }

        Map<String, Object> primary =
            (rd.getSourceFields() != null) ? rd.getSourceFields() : rd.getTargetFields();
        if (primary == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : fieldNames) {
            if (primary.containsKey(name)) {
                result.put(name, primary.get(name));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static TableDiff.TableDiffCounts recount(List<RecordDiff> records) {
        int insert = 0, update = 0, delete = 0;
        for (RecordDiff rd : records) {
            if (rd == null || rd.getDiffType() == null) {
                continue;
            }
            switch (rd.getDiffType()) {
                case INSERT -> insert++;
                case UPDATE -> update++;
                case DELETE -> delete++;
                default -> {}
            }
        }
        return TableDiff.TableDiffCounts.builder()
            .insertCount(insert)
            .updateCount(update)
            .deleteCount(delete)
            .noopCount(0)
            .build();
    }

    private static int safeInt(Integer v) {
        return v == null ? 0 : v;
    }
}
