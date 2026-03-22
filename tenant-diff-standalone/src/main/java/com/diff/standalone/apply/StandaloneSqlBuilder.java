package com.diff.standalone.apply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Standalone 场景下的 SQL 构建器（适配 Spring JDBC {@code JdbcTemplate} 占位符风格）。
 *
 * <p>
 * 占位符格式：{@code ?}（由 {@code JdbcTemplate} 在底层进行参数绑定）。
 * </p>
 *
 * <p>
 * <b>tenantsid 强制约束（WHY）</b>：多租户场景下，任何写操作必须显式限定 tenantsid，
 * 否则可能误写或误删其他租户数据。本类在所有 INSERT/UPDATE/DELETE 中强制注入 tenantsid 条件，
 * 从源头杜绝跨租户污染。
 * </p>
 *
 * <p>
 * <b>列名字典序排序（WHY）</b>：字段顺序不确定会导致生成的 SQL 不稳定，影响日志排查、测试断言和缓存 key。
 * 按字典序排序后，相同字段集合始终生成相同 SQL 文本，便于可观测性与回归测试。
 * </p>
 *
 * <p>
 * v1 约束汇总：
 * <ul>
 *     <li>所有写操作强制带 {@code tenantsid} 约束，避免误写跨租户数据</li>
 *     <li>INSERT/UPDATE 会忽略 {@code id}/{@code tenantsid} 字段，由调用方/数据库负责</li>
 *     <li>列名按字典序排序，保证 SQL 稳定性（便于排查与测试）</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public final class StandaloneSqlBuilder {
    /** 租户 ID 列名。 */   
    private static final String COL_TENANTSID = "tenantsid";
    /** ID 列名。 */
    private static final String COL_ID = "id";
 

    private StandaloneSqlBuilder() {
    }

    /**
     * SQL 语句与占位符参数对。
     *
     * @param sql 带 {@code ?} 占位符的 SQL 文本
     * @param args 与占位符一一对应的参数值
     * @author tenant-diff
     * @since 2026-01-20
     */
    public record SqlAndArgs(String sql, Object[] args) {
    }

    /**
     * 构建 INSERT 语句。
     *
     * <p>安全策略：
     * <ul>
     *     <li>强制设置 tenantsid，防止跨租户数据写入</li>
     *     <li>自动过滤 id/tenantsid 字段，由数据库自动生成</li>
     *     <li>列名按字典序排列，保证 SQL 稳定可预测</li>
     * </ul>
     * </p>
     */
    public static SqlAndArgs buildInsert(String tableName, Long targetTenantId, Map<String, Object> fields) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is blank");
        }
        if (targetTenantId == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        Map<String, Object> map = fields == null ? Collections.emptyMap() : fields;

        // 过滤 id/tenantsid，这些字段由数据库或框架管理
        List<String> cols = new ArrayList<>(map.keySet());
        cols.removeIf(c -> c == null || c.isBlank() || COL_ID.equalsIgnoreCase(c) || COL_TENANTSID.equalsIgnoreCase(c));
        // 按字典序排列，保证 SQL 稳定
        cols.sort(String::compareTo);

        // 构建所有列
        List<String> allCols = new ArrayList<>();
        allCols.add(COL_TENANTSID);
        allCols.addAll(cols);

        // 构建 SQL
        StringBuilder sql = new StringBuilder(256);
        sql.append("INSERT INTO ").append(tableName).append(" (");
        sql.append(String.join(", ", allCols));
        sql.append(") VALUES (");

        // tenantsid 固定为第一个参数 占位符
        sql.append("?");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(", ?");
        }
        sql.append(")");

        // 构建参数
        List<Object> args = new ArrayList<>();
        // tenantsid 固定为第一个参数
        args.add(targetTenantId);
        // 其他列按顺序添加
        for (String col : cols) {
            args.add(map.get(col));
        }

        // 返回 SQL 语句与参数
        return new SqlAndArgs(sql.toString(), args.toArray());
    }

    /**
     * 构建 UPDATE 语句（按 id 定位）。
     *
     * <p>安全策略：
     * <ul>
     *     <li>WHERE 条件强制包含 tenantsid + id，双重约束防止误更新</li>
     *     <li>自动过滤 id/tenantsid 字段，不允许修改这些系统字段</li>
     *     <li>若无可更新字段则返回 null</li>
     * </ul>
     * </p>
     */
    public static SqlAndArgs buildUpdateById(String tableName, Long targetTenantId, Long targetId, Map<String, Object> fields) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is blank");
        }
        if (targetTenantId == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        if (targetId == null) {
            throw new IllegalArgumentException("targetId is null");
        }

        Map<String, Object> map = fields == null ? Collections.emptyMap() : fields;

        // 过滤 id/tenantsid，这些字段不允许被更新
        List<String> cols = new ArrayList<>(map.keySet());
        cols.removeIf(c -> c == null || c.isBlank() || COL_ID.equalsIgnoreCase(c) || COL_TENANTSID.equalsIgnoreCase(c));
        cols.sort(String::compareTo);

        // 若无可更新字段，返回 null 表示不需要执行 UPDATE
        if (cols.isEmpty()) {
            return null;
        }

        // 构建 SQL
        StringBuilder sql = new StringBuilder(256);
        sql.append("UPDATE ").append(tableName).append(" SET ");

        // 构建参数
        List<Object> args = new ArrayList<>();
        // 索引 用于构建 SQL
        int idx = 0;
        for (String col : cols) {
            if (idx > 0) {
                // 如果索引大于0 则添加逗号
                sql.append(", ");
            }
            // 添加列名和占位符
            sql.append(col).append(" = ?");
            // 添加参数
            args.add(map.get(col));
            idx++;
        }

        // 添加 WHERE 条件
        sql.append(" WHERE ").append(COL_TENANTSID).append(" = ?");
        args.add(targetTenantId);
        idx++;
        // 添加 ID 条件
        sql.append(" AND ").append(COL_ID).append(" = ?");
        args.add(targetId);

        // 返回 SQL 语句与参数
        return new SqlAndArgs(sql.toString(), args.toArray());
    }

    /**
     * 构建 DELETE 语句（按 id 定位）。
     *
     * <p>安全策略：WHERE 条件强制包含 tenantsid + id，双重约束防止误删除其他租户数据。</p>
     */
    public static SqlAndArgs buildDeleteById(String tableName, Long targetTenantId, Long targetId) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is blank");
        }
        if (targetTenantId == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        if (targetId == null) {
            throw new IllegalArgumentException("targetId is null");
        }
        // 双重条件约束：tenantsid + id，确保只删除目标租户的指定记录
        String sql = "DELETE FROM " + tableName + " WHERE " + COL_TENANTSID + " = ? AND " + COL_ID + " = ?";
        return new SqlAndArgs(sql, new Object[]{targetTenantId, targetId});
    }
}

