package com.diff.core.engine;

import java.util.Set;

/**
 * 默认对比规则常量（集中管理跨租户对比时应忽略的系统字段）。
 *
 * <p>
 * 这些字段在不同租户间天然不同（如自增 id、租户标识 tenantsid），或属于系统自动维护的
 * 管理字段（如 version、data_modify_time），将它们纳入对比只会产生无业务价值的噪音。
 * </p>
 *
 * <p>
 * 与旧版 DiffComparisonComponent.DEFAULT_IGNORE_FIELDS 保持对齐，
 * 确保引擎升级后 diff 结果不会因默认规则变化而产生大范围"伪差异"。
 * </p>
 *
 * <p>可通过配置 {@code tenant-diff.standalone.default-ignore-fields} 覆盖此默认值。</p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see DiffRules
 */
public final class DiffDefaults {
    private DiffDefaults() {
    }

    /**
     * 内置默认忽略字段集合（用于 record 内容对比）。
     *
     * <p>与旧版 DiffComparisonComponent.DEFAULT_IGNORE_FIELDS 保持对齐，避免升级后 diff 结果发生大范围变化。</p>
     *
     * <p>可通过配置 {@code tenant-diff.standalone.default-ignore-fields} 覆盖此默认值。</p>
     */
    public static final Set<String> DEFAULT_IGNORE_FIELDS = Set.of(
        // System fields
        "id",
        "tenantsid",
        "version",

        // Management fields
        "data_modify_time",

        // Relation foreign keys (parent ids)
        "xai_instruction_id",
        "xai_instr_recommended_id",
        "xai_enumeration_id",
        "xai_operation_id",
        "xai_ocr_template_id",

        // Inheritance tracking fields (base* series)
        "baseinstructionid",
        "baseparamid",
        "basedisplayid",
        "baserecommendedid",
        "baseenumid",
        "baseenumvalueid",
        "baseconditionid",
        "baseoperationid",
        "basepromptid"
    );
}
