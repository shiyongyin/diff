package com.diff.standalone.persistence;

import java.util.Set;

/**
 * tenant-diff 框架表名常量与前缀解析工具。
 *
 * <p>
 * Standalone 模块默认使用 {@code xai_tenant_diff_} 前缀创建和访问 5 张框架表。
 * 当业务方配置自定义 {@code table-prefix} 时，需要确保 DDL 初始化器与 MyBatis 运行时
 * 使用完全一致的映射规则；否则会出现“建表成功，但查询仍访问默认表名”的漂移。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-03-08
 * @see com.diff.standalone.config.TenantDiffSchemaInitializer
 * @see com.diff.standalone.config.TenantDiffStandaloneConfiguration
 */
public final class TenantDiffTableNames {

    public static final String BUILT_IN_PREFIX = "xai_tenant_diff_";

    public static final String SESSION = BUILT_IN_PREFIX + "session";
    public static final String RESULT = BUILT_IN_PREFIX + "result";
    public static final String APPLY_RECORD = BUILT_IN_PREFIX + "apply_record";
    public static final String APPLY_LEASE = BUILT_IN_PREFIX + "apply_lease";
    public static final String SNAPSHOT = BUILT_IN_PREFIX + "snapshot";
    public static final String DECISION_RECORD = BUILT_IN_PREFIX + "decision_record";

    private static final Set<String> FRAMEWORK_TABLES = Set.of(
        SESSION,
        RESULT,
        APPLY_RECORD,
        APPLY_LEASE,
        SNAPSHOT,
        DECISION_RECORD
    );

    private TenantDiffTableNames() {
    }

    /**
     * 规范化框架表前缀。
     *
     * <p>空值、空白值统一回退到内置默认前缀，避免把框架表名错误地替换为空串。</p>
     *
     * @param tablePrefix 原始配置值
     * @return 可直接使用的表前缀；空值回退为内置默认前缀
     */
    public static String normalizePrefix(String tablePrefix) {
        if (tablePrefix == null || tablePrefix.isBlank()) {
            return BUILT_IN_PREFIX;
        }
        return tablePrefix.trim();
    }

    /**
     * 判断给定表名是否属于框架托管表。
     *
     * <p>仅框架元数据表参与前缀映射，业务表和插件自定义表保持原样。</p>
     *
     * @param tableName 逻辑表名
     * @return true 表示需要参与前缀映射
     */
    public static boolean isFrameworkTable(String tableName) {
        return FRAMEWORK_TABLES.contains(tableName);
    }

    /**
     * 将框架逻辑表名映射为当前配置下的物理表名。
     *
     * <p>非框架表保持原样返回，避免误伤业务表。</p>
     *
     * @param logicalTableName MyBatis 侧看到的逻辑表名（默认以 {@code xai_tenant_diff_} 开头）
     * @param tablePrefix      配置的表前缀
     * @return 实际应访问的物理表名
     */
    public static String resolvePhysicalTableName(String logicalTableName, String tablePrefix) {
        if (!isFrameworkTable(logicalTableName)) {
            return logicalTableName;
        }
        String normalizedPrefix = normalizePrefix(tablePrefix);
        if (BUILT_IN_PREFIX.equals(normalizedPrefix)) {
            return logicalTableName;
        }
        return normalizedPrefix + logicalTableName.substring(BUILT_IN_PREFIX.length());
    }
}
