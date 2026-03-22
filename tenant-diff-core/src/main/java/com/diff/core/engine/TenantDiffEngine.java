package com.diff.core.engine;


import com.diff.core.domain.diff.*;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.model.RecordData;
import com.diff.core.domain.model.TableData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 同库跨租户差异对比引擎（纯 Java 核心，无框架依赖）。
 *
 * <p>
 * 跨租户数据同步的核心挑战在于：不同租户的物理 id 不同，无法直接按 id 比较。
 * 本引擎通过 businessKey 进行逻辑对齐，在 business → table → record → field 四层
 * 递归对比后输出结构化的 {@link BusinessDiff} 列表，供下游 Apply 阶段消费。
 * </p>
 *
 * <h3>为什么采用 businessKey 而非物理 id 对齐</h3>
 * <p>
 * 物理 id 是各租户独立自增的，同一条业务记录在不同租户中 id 完全不同。
 * businessKey 是业务层面的唯一标识（如指令编号、模板名称），天然适合做跨租户配对。
 * </p>
 *
 * <h3>为什么需要 fingerprint 优化</h3>
 * <p>
 * 全量逐字段对比在记录数较多时开销显著。fingerprint（预计算或即时 MD5）可以
 * 在 O(1) 时间内判定记录未变（NOOP），避免不必要的字段级遍历。
 * </p>
 *
 * <h3>为什么结果需要稳定排序</h3>
 * <p>
 * Apply 阶段依赖 dependencyLevel 保证父表先于子表写入（INSERT）或子表先于父表删除（DELETE），
 * 不稳定的顺序会导致外键约束违反。按 key 的字典序二级排序则保证重跑结果可预测。
 * </p>
 *
 * <h3>线程安全</h3>
 * <p>
 * 本类无状态，同一实例可安全地在多线程中并发调用 {@link #compare} 方法。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see DiffRules
 * @see DiffDefaults
 * @see BusinessDiff
 */
public class TenantDiffEngine {

    /**
     * 对比结果。
     *
     * @param businessDiffs 业务级差异明细
     * @param statistics 全局统计信息（聚合视图）
     */
    public record CompareResult(List<BusinessDiff> businessDiffs, DiffStatistics statistics) {
    }

    /**
     * 使用默认规则对比两侧租户的业务模型集合。
     *
     * <p>便捷方法，等价于 {@code compare(tenantAModels, tenantBModels, DiffRules.defaults())}。</p>
     *
     * @param tenantAModels A 侧（源）业务模型列表，允许 {@code null}（视为空列表）
     * @param tenantBModels B 侧（目标）业务模型列表，允许 {@code null}（视为空列表）
     * @return 对比结果，包含业务级差异明细与全局统计
     */
    public CompareResult compare(List<BusinessData> tenantAModels, List<BusinessData> tenantBModels) {
        return compare(tenantAModels, tenantBModels, DiffRules.defaults());
    }

    /**
     * 对比两侧租户的业务模型集合。
     *
     * <p>
     * 对比流程：先按 {@code businessType#businessTable#businessKey} 组合键做业务级对齐，
     * 再递归到 table → record → field 层级。对比完成后对结果做依赖排序，
     * 使下游 {@link com.diff.core.apply.PlanBuilder} 可直接按序生成执行计划。
     * </p>
     *
     * @param tenantAModels A 侧（源）业务模型列表，允许 {@code null}（视为空列表）
     * @param tenantBModels B 侧（目标）业务模型列表，允许 {@code null}（视为空列表）
     * @param rules         对比规则，允许 {@code null}（内部回退到 {@link DiffRules#defaults()}）
     * @return 对比结果，包含业务级差异明细与全局统计，永不为 {@code null}
     */
    public CompareResult compare(List<BusinessData> tenantAModels, List<BusinessData> tenantBModels, DiffRules rules) {
        List<BusinessData> sources = tenantAModels == null ? Collections.emptyList() : tenantAModels;
        List<BusinessData> targets = tenantBModels == null ? Collections.emptyList() : tenantBModels;
        DiffRules effectiveRules = rules == null ? DiffRules.defaults() : rules;

        // 创建目标业务数据映射
        Map<String, BusinessData> targetMap = new HashMap<>();
        for (BusinessData target : targets) {
            if (target == null) {
                continue;
            }
            String key = compositeBusinessKey(target);
            targetMap.putIfAbsent(key, target);
        }

        List<BusinessDiff> results = new ArrayList<>();
        StatsAccumulator overallStats = new StatsAccumulator();

        // 对比业务数据
        for (BusinessData source : sources) {
            if (source == null) {
                continue;
            }
            String key = compositeBusinessKey(source);
            BusinessData target = targetMap.remove(key);
            BusinessDiff diff = compareBusiness(source, target, effectiveRules);
            results.add(diff);
            overallStats.add(diff.getStatistics());
        }

        // 对比额外目标业务数据
        for (BusinessData extraTarget : targetMap.values()) {
            BusinessDiff diff = compareBusiness(null, extraTarget, effectiveRules);
            results.add(diff);
            overallStats.add(diff.getStatistics());
        }

        // 设置总业务数
        overallStats.totalBusinesses = results.size();
        return new CompareResult(results, overallStats.build());
    }

    /**
     * 统计累加器。
     */
    private static final class StatsAccumulator {
        /** 总业务数。 */
        int totalBusinesses;
        /** 总表数。 */
        int totalTables;
        /** 总记录数。 */
        int totalRecords;
        /** INSERT 动作数量。 */
        int insertCount;
        /** UPDATE 动作数量。 */
        int updateCount;
        /** DELETE 动作数量。 */
        int deleteCount;
        /** NOOP 动作数量。 */
        int noopCount;

        /**
         * 添加统计信息。
         *
         * @param delta 统计信息，允许 {@code null}（视为空统计）
         */
        void add(DiffStatistics delta) {
            if (delta == null) {
                return;
            }
            totalBusinesses += delta.getTotalBusinesses() == null ? 0 : delta.getTotalBusinesses();
            totalTables += delta.getTotalTables() == null ? 0 : delta.getTotalTables();
            totalRecords += delta.getTotalRecords() == null ? 0 : delta.getTotalRecords();
            insertCount += delta.getInsertCount() == null ? 0 : delta.getInsertCount();
            updateCount += delta.getUpdateCount() == null ? 0 : delta.getUpdateCount();
            deleteCount += delta.getDeleteCount() == null ? 0 : delta.getDeleteCount();
            noopCount += delta.getNoopCount() == null ? 0 : delta.getNoopCount();
        }

        /**
         * 构建统计信息。
         *
         * @return 统计信息，永不为 {@code null}
         */
        DiffStatistics build() {
            return DiffStatistics.builder()
                .totalBusinesses(totalBusinesses)
                .totalTables(totalTables)
                .totalRecords(totalRecords)
                .insertCount(insertCount)
                .updateCount(updateCount)
                .deleteCount(deleteCount)
                .noopCount(noopCount)
                .build();
        }
    }

    /**
     * 对比业务数据。
     *
     * @param source 源业务数据，允许 {@code null}（视为空业务）
     * @param target 目标业务数据，允许 {@code null}（视为空业务）
     * @param rules 对比规则，允许 {@code null}（内部回退到 {@link DiffRules#defaults()}）
     * @return 业务差异明细，永不为 {@code null}
     */
    private BusinessDiff compareBusiness(BusinessData source, BusinessData target, DiffRules rules) {
        BusinessData base = source != null ? source : target;
        List<TableDiff> tableDiffs = compareTables(
            source == null ? null : source.getTables(),
            target == null ? null : target.getTables(),
            rules
        );

        DiffStatistics statistics = buildStatistics(tableDiffs);
        statistics.setTotalBusinesses(1);

        return BusinessDiff.builder()
            .businessType(base == null ? null : base.getBusinessType())
            .businessTable(base == null ? null : base.getBusinessTable())
            .businessKey(base == null ? null : base.getBusinessKey())
            .businessName(base == null ? null : base.getBusinessName())
            .diffType(resolveBusinessDiffType(source, target))
            .statistics(statistics)
            .tableDiffs(tableDiffs)
            .build();
    }

    /**
     * 解析业务差异类型。
     *
     * @param source 源业务数据，允许 {@code null}（视为空业务）
     * @param target 目标业务数据，允许 {@code null}（视为空业务）
     * @return 业务差异类型，永不为 {@code null}
     */
    private static DiffType resolveBusinessDiffType(BusinessData source, BusinessData target) {
        if (source != null && target == null) {
            return DiffType.BUSINESS_INSERT;
        }
        if (source == null && target != null) {
            return DiffType.BUSINESS_DELETE;
        }
        return null;
    }

    /**
     * 对比两侧的表数据集合。
     *
     * <p>对比策略：以表名为 key 进行对齐，分别处理 source 有/target 无、两侧都有、source 无/target 有三种情况。
     * 结果按依赖层级排序，便于 Apply 阶段按正确顺序执行。</p>
     */
    private List<TableDiff> compareTables(List<TableData> sourceTables, List<TableData> targetTables, DiffRules rules) {
        Map<String, TableData> sourceMap = toTableMap(sourceTables);
        Map<String, TableData> targetMap = toTableMap(targetTables);

        Set<String> tableNames = new HashSet<>();
        tableNames.addAll(sourceMap.keySet());
        tableNames.addAll(targetMap.keySet());

        // 对比表数据
        List<TableDiff> diffs = new ArrayList<>();
        for (String tableName : tableNames) {
            TableData s = sourceMap.get(tableName);
            TableData t = targetMap.get(tableName);
            diffs.add(compareTable(tableName, s, t, rules));
        }

        // 按依赖层级排序：保证 apply 阶段可按“父先子后”的顺序执行（DELETE 则反向）。
        diffs.sort(Comparator
            .comparing((TableDiff d) -> d.getDependencyLevel() == null ? Integer.MAX_VALUE : d.getDependencyLevel())
            .thenComparing(d -> d.getTableName() == null ? "" : d.getTableName())
        );
        return diffs;
    }

    /**
     * 将表数据列表转换为表数据映射。
     *
     * @param tables 表数据列表，允许 {@code null}（视为空列表）
     * @return 表数据映射，永不为 {@code null}
     */
    private static Map<String, TableData> toTableMap(List<TableData> tables) {
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, TableData> map = new HashMap<>();

        // 将表数据列表转换为表数据映射
        for (TableData table : tables) {
            if (table == null || table.getTableName() == null) {
                continue;
            }
            map.putIfAbsent(table.getTableName(), table);
        }
        return map;
    }

    /**
     * 对比表数据。
     *
     * @param tableName 表名
     * @param source 源表数据，允许 {@code null}（视为空表）
     * @param target 目标表数据，允许 {@code null}（视为空表）
     * @param rules 对比规则，允许 {@code null}（内部回退到 {@link DiffRules#defaults()}）
     * @return 表差异明细，永不为 {@code null}
     */
    private TableDiff compareTable(String tableName, TableData source, TableData target, DiffRules rules) {
        Integer dependencyLevel = source != null ? source.getDependencyLevel() : (target != null ? target.getDependencyLevel() : null);

        // 对比记录数据
        List<RecordDiff> recordDiffs = compareRecords(
            tableName,
            source == null ? null : source.getRecords(),
            target == null ? null : target.getRecords(),
            rules
        );

        // 构建表差异统计
        TableDiff.TableDiffCounts counts = buildCounts(recordDiffs);
        // 构建表差异类型
        DiffType tableDiffType;
        if (source != null && target == null) {
            tableDiffType = DiffType.TABLE_INSERT;
        } else if (source == null) {
            tableDiffType = DiffType.TABLE_DELETE;
        } else {
            tableDiffType = null;
        }

        // 构建表差异明细
        return TableDiff.builder()
            .tableName(tableName)
            .dependencyLevel(dependencyLevel)
            .diffType(tableDiffType)
            .counts(counts)
            .recordDiffs(recordDiffs)
            .build();
    }

    /**
     * 对比两侧的记录数据集合。
     *
     * <p>对齐策略：以 businessKey 为 key 进行配对，而非物理 id。
     * 这样可以在跨租户场景下正确识别"逻辑上相同"的记录。</p>
     */
    private List<RecordDiff> compareRecords(String tableName, List<RecordData> sourceRecords, List<RecordData> targetRecords, DiffRules rules) {
        List<RecordData> sources = sourceRecords == null ? Collections.emptyList() : sourceRecords;
        List<RecordData> targets = targetRecords == null ? Collections.emptyList() : targetRecords;
        // 创建目标记录数据映射
        Map<String, RecordData> targetMap = new HashMap<>();
        for (RecordData target : targets) {
            if (target == null || target.getBusinessKey() == null) {
                continue;
            }
            targetMap.putIfAbsent(target.getBusinessKey(), target);
        }
        // 创建记录差异列表
        List<RecordDiff> diffs = new ArrayList<>();
        Set<String> ignoreFields = rules.ignoreFieldsForTable(tableName);
        // 对比记录数据
        for (RecordData source : sources) {
            if (source == null || source.getBusinessKey() == null) {
                continue;
            }
            RecordData target = targetMap.remove(source.getBusinessKey());
            diffs.add(compareRecord(source, target, ignoreFields));
        }

        // 对比额外目标记录数据
        for (RecordData extraTarget : targetMap.values()) {
            diffs.add(compareRecord(null, extraTarget, ignoreFields));
        }

        // 按记录业务键排序 便于 Apply 阶段按序执行
        diffs.sort(Comparator.comparing(d -> d.getRecordBusinessKey() == null ? "" : d.getRecordBusinessKey()));
        return diffs;
    }

    /**
     * 比较两条记录的差异。
     *
     * <p>对比策略：
     * <ol>
     *     <li>source 有、target 无 → INSERT</li>
     *     <li>source 无、target 有 → DELETE</li>
     *     <li>两侧都有 → 先比较指纹（fingerprint），相同则跳过字段级对比；不同则逐字段对比</li>
     * </ol>
     * </p>
     *
     * <p>指纹优化：若记录提供了预计算的 fingerprint 或可在此处计算 MD5 指纹，
     * 指纹一致时直接判定为 NOOP，避免昂贵的字段级对比开销。</p>
     */
    private RecordDiff compareRecord(RecordData source, RecordData target, Set<String> ignoreFields) {
        LocalDateTime decisionTime = LocalDateTime.now();
        // source 有、target 无：表示需要 INSERT 到目标租户
        if (source != null && target == null) {
            return RecordDiff.builder()
                .recordBusinessKey(source.getBusinessKey())
                .diffType(DiffType.INSERT)
                .decision(DecisionType.ACCEPT)
                .decisionTime(decisionTime)
                .sourceFields(source.getFields())
                .targetFields(null)
                .fieldDiffs(null)
                .warnings(null)
                .build();
        }
        // source 无、target 有：表示需要从目标租户 DELETE
        if (source == null && target != null) {
            return RecordDiff.builder()
                .recordBusinessKey(target.getBusinessKey())
                .diffType(DiffType.DELETE)
                .decision(DecisionType.ACCEPT)
                .decisionTime(decisionTime)
                .sourceFields(null)
                .targetFields(target.getFields())
                .fieldDiffs(null)
                .warnings(null)
                .build();
        }
        // source 或 target 为空：表示需要跳过对比
        if (source == null || target == null) {
            return RecordDiff.builder()
                .recordBusinessKey(null)
                .diffType(DiffType.NOOP)
                .decision(DecisionType.ACCEPT)
                .decisionTime(decisionTime)
                .sourceFields(null)
                .targetFields(null)
                .fieldDiffs(null)
                .warnings(List.of("skip: source/target record is null"))
                .build();
        }

        // 计算或获取指纹，用于快速判断记录是否相同
        String sourceFp = fingerprintOrCompute(source, ignoreFields);
        String targetFp = fingerprintOrCompute(target, ignoreFields);
        if (sourceFp != null && sourceFp.equals(targetFp)) {
            // 指纹一致：快速判定记录内容未变，避免逐字段对比的性能开销
            return RecordDiff.builder()
                .recordBusinessKey(source.getBusinessKey())
                .diffType(DiffType.NOOP)
                .decision(DecisionType.ACCEPT)
                .decisionTime(decisionTime)
                .sourceFields(source.getFields())
                .targetFields(target.getFields())
                .fieldDiffs(null)
                .warnings(null)
                .build();
        }
        // 比较字段差异
        List<FieldDiff> fieldDiffs = compareFields(source.getFields(), target.getFields(), ignoreFields);
        DiffType diffType = fieldDiffs.isEmpty() ? DiffType.NOOP : DiffType.UPDATE;
        // 构建记录差异明细
        return RecordDiff.builder()
            .recordBusinessKey(source.getBusinessKey())
            .diffType(diffType)
            .decision(DecisionType.ACCEPT)
            .decisionTime(decisionTime)
            .sourceFields(source.getFields())
            .targetFields(target.getFields())
            .fieldDiffs(fieldDiffs.isEmpty() ? null : fieldDiffs)
            .warnings(null)
            .build();
    }

    /**
     * 比较字段差异。
     *
     * @param sourceFields 源字段，允许 {@code null}（视为空字段）
     * @param targetFields 目标字段，允许 {@code null}（视为空字段）
     * @param ignoreFields 忽略字段，允许 {@code null}（视为空集合）
     * @return 字段差异列表，永不为 {@code null}
     */
    private static List<FieldDiff> compareFields(Map<String, Object> sourceFields, Map<String, Object> targetFields, Set<String> ignoreFields) {
        Map<String, Object> src = sourceFields == null ? Collections.emptyMap() : sourceFields;
        Map<String, Object> tgt = targetFields == null ? Collections.emptyMap() : targetFields;

        Set<String> keys = new HashSet<>();
        keys.addAll(src.keySet());
        keys.addAll(tgt.keySet());
        // 移除忽略字段
        keys.removeAll(ignoreFields == null ? Collections.emptySet() : ignoreFields);

        List<String> sortedKeys = new ArrayList<>(keys);
        // 按字段名排序
        sortedKeys.sort(Comparator.naturalOrder());
        // 构建字段差异列表
        List<FieldDiff> diffs = new ArrayList<>();
        for (String key : sortedKeys) {
            Object s = src.get(key);
            Object t = tgt.get(key);
            if (Objects.equals(s, t)) {
                continue;
            }
            // 构建字段差异明细
            diffs.add(FieldDiff.builder()
                .fieldName(key)
                .sourceValue(s)
                .targetValue(t)
                .changeDescription("from [" + String.valueOf(s) + "] to [" + String.valueOf(t) + "]")
                .build());
        }
        return diffs;
    }

    /**
     * 差异类型计数器。
     */
    private static final class DiffTypeCounts {
        /** INSERT 动作数量。 */
        int insert;
        /** UPDATE 动作数量。 */
        private int update;
        /** DELETE 动作数量。 */
        private int delete;
        /** NOOP 动作数量。 */
        int noop;

        private void add(DiffType diffType) {
            if (diffType == null) {
                return;
            }
            switch (diffType) {
                case INSERT -> insert++;
                case UPDATE -> update++;
                case DELETE -> delete++;
                case NOOP -> noop++;
                default -> {
                }
            }
        }
    }

    /**
     * 构建表差异统计。
     *
     * @param recordDiffs 记录差异列表，允许 {@code null}（视为空列表）
     * @return 表差异统计，永不为 {@code null}
     */
    private static TableDiff.TableDiffCounts buildCounts(List<RecordDiff> recordDiffs) {
        DiffTypeCounts counts = new DiffTypeCounts();
        // 统计记录差异类型
        if (recordDiffs != null) {
            for (RecordDiff diff : recordDiffs) {
                if (diff == null || diff.getDiffType() == null) {
                    continue;
                }
                counts.add(diff.getDiffType());
            }
        }
        // 构建表差异统计
        return TableDiff.TableDiffCounts.builder()
            .insertCount(counts.insert)
            .updateCount(counts.update)
            .deleteCount(counts.delete)
            .noopCount(counts.noop)
            .build();
    }

    /**
     * 构建表差异统计。
     *
     * @param tableDiffs 表差异列表，允许 {@code null}（视为空列表）
     * @return 表差异统计，永不为 {@code null}
     */
    private static DiffStatistics buildStatistics(List<TableDiff> tableDiffs) {
        int totalTables = tableDiffs == null ? 0 : tableDiffs.size();
        int totalRecords = 0;
        DiffTypeCounts counts = new DiffTypeCounts();
        // 统计表差异类型
        if (tableDiffs != null) {
            for (TableDiff tableDiff : tableDiffs) {
                if (tableDiff == null || tableDiff.getRecordDiffs() == null) {
                    continue;
                }
                // 统计记录差异类型
                for (RecordDiff recordDiff : tableDiff.getRecordDiffs()) {
                    if (recordDiff == null || recordDiff.getDiffType() == null) {
                        continue;
                    }
                    totalRecords++;
                    counts.add(recordDiff.getDiffType());
                }
            }
        }

        // 构建表差异统计
        return DiffStatistics.builder()
            .totalBusinesses(0)
            .totalTables(totalTables)
            .totalRecords(totalRecords)
            .insertCount(counts.insert)
            .updateCount(counts.update)
            .deleteCount(counts.delete)
            .noopCount(counts.noop)
            .build();
    }

    /**
     * 使用长度前缀编码，避免当值中包含分隔符字符时发生冲突。
     * 格式："len1:val1|len2:val2|len3:val3"
     */
    private static String compositeBusinessKey(BusinessData data) {
        String businessType = data.getBusinessType() == null ? "" : data.getBusinessType();
        String businessTable = data.getBusinessTable() == null ? "" : data.getBusinessTable();
        String businessKey = data.getBusinessKey() == null ? "" : data.getBusinessKey();
        return businessType.length() + ":" + businessType + "|"
            + businessTable.length() + ":" + businessTable + "|"
            + businessKey.length() + ":" + businessKey;
    }

    /**
     * 计算记录指纹。
     *
     * @param record 记录数据，允许 {@code null}（视为空记录）
     * @param ignoreFields 忽略字段，允许 {@code null}（视为空集合）
     * @return 记录指纹，永不为 {@code null}
     */
    private static String fingerprintOrCompute(RecordData record, Set<String> ignoreFields) {
        String fp = record.getFingerprint();
        if (fp != null && !fp.isBlank()) {
            return fp;
        }
        return computeMd5Fingerprint(record.getFields(), ignoreFields);
    }

    /**
     * 计算字段集合的 MD5 指纹。
     *
     * <p>算法：将字段按 key 排序后拼接为 "key=value;" 格式，再计算 MD5。
     * 排序保证相同内容的不同顺序产生相同指纹。</p>
     */
    private static String computeMd5Fingerprint(Map<String, Object> fields, Set<String> ignoreFields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        // 使用 TreeMap 保证字段按 key 排序，生成稳定的指纹
        Map<String, Object> sorted = new TreeMap<>(fields);
        StringBuilder content = new StringBuilder(512);

        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (ignoreFields != null && ignoreFields.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            content.append(key)
                .append("=")
                .append(value != null ? value.toString() : "NULL")
                .append(";");
        }
        return md5Hex(content.toString());
    }

    /**
     * 计算 MD5 指纹。
     *
     * @param content 内容
     * @return MD5 指纹，永不为 {@code null}
     */
    private static String md5Hex(String content) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute MD5 fingerprint", e);
        }
    }
}
