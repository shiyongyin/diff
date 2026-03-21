package com.diff.core.domain.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务 Schema 元数据——描述一个业务类型涉及的表结构、依赖关系与字段特性。
 *
 * <p>
 * 对比引擎和 Apply 执行器本身是业务无关的，它们依赖 BusinessSchema 获取
 * 业务专属的元信息。每个业务类型插件通过 {@code schema()} 方法提供自己的 Schema，
 * 驱动以下关键行为：
 * </p>
 * <ul>
 *   <li><b>依赖排序</b>：{@link #tables} 中的 dependencyLevel 决定
 *       INSERT 先主后子、DELETE 先子后主的执行顺序</li>
 *   <li><b>外键替换</b>：{@link #relations} 中的 child.fk → parent table 映射，
 *       让 Apply 阶段能从 {@link com.diff.core.apply.IdMapping} 查找父记录新 id
 *       并替换子表外键字段</li>
 *   <li><b>字段过滤</b>：{@link #ignoreFieldsByTable} 提供按表的额外忽略字段，
 *       与 {@link com.diff.core.engine.DiffRules} 的默认忽略集合取并集</li>
 *   <li><b>类型归一化</b>：{@link #fieldTypesByTable} 提供字段类型提示
 *       （如 JSON/datetime/bigint），用于 Apply 前的值转换</li>
 * </ul>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see com.diff.core.engine.TenantDiffEngine
 * @see com.diff.core.spi.apply.BusinessApplySupport
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessSchema {
    /**
     * 表依赖层级映射：tableName → dependencyLevel（0 = 主表，1 = 子表，2 = 孙表）。
     *
     * <p>驱动对比排序与 Apply 执行顺序。</p>
     */
    @Builder.Default
    private Map<String, Integer> tables = Collections.emptyMap();

    /**
     * 表间外键关系列表：child.fk → parent table。
     *
     * <p>Apply 阶段据此将子表外键替换为目标租户中父表记录的新 id。</p>
     */
    @Builder.Default
    private List<TableRelation> relations = Collections.emptyList();

    /**
     * 按表忽略字段：用于 diff/compare 阶段过滤业务专属的非比较字段。
     */
    @Builder.Default
    private Map<String, Set<String>> ignoreFieldsByTable = Collections.emptyMap();

    /**
     * 按表字段类型提示：用于 Apply 前的值归一化（JSON/datetime/bigint 等）。
     */
    @Builder.Default
    private Map<String, Map<String, String>> fieldTypesByTable = Collections.emptyMap();

    /**
     * 按表前端展示字段（有序）：diff 返回时从 sourceFields/targetFields 投影到 showFields。
     *
     * <p>
     * 使用 {@code List}（而非 {@code Set}）以保留字段展示顺序，前端按此顺序渲染列。
     * 未配置的表不进行 showFields 投影，{@link com.diff.core.domain.diff.RecordDiff#getShowFields()} 保持 {@code null}。
     * </p>
     *
     * @since 1.0.0
     */
    @Builder.Default
    private Map<String, List<String>> showFieldsByTable = Collections.emptyMap();

    /**
     * 表间外键关系描述。
     *
     * <p>
     * 一条关系表示：{@code childTable} 的 {@code fkColumn} 引用 {@code parentTable} 的主键。
     * Apply 阶段在 INSERT 子表前，会从 {@link com.diff.core.apply.IdMapping}
     * 查找 parentTable 记录的新 id，替换 fkColumn 的值。
     * </p>
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableRelation {
        /** 子表名。 */
        private String childTable;
        /** 子表中引用父表的外键列名。 */
        private String fkColumn;
        /** 被引用的父表名。 */
        private String parentTable;
    }
}
