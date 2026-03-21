package com.diff.standalone.util;

/**
 * 版本号标准化工具类。
 *
 * <p>
 * 用于统一处理 standalone diff 服务/插件中的版本号输入，
 * 确保空白字符串和 null 的行为一致。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public final class TenantDiffVersionUtil {

    private TenantDiffVersionUtil() {
    }

    /**
     * 标准化版本号字符串。
     *
     * <p>
     * 处理规则：
     * <ul>
     *     <li>{@code null} → {@code null}</li>
     *     <li>空白字符串（trim 后为空）→ {@code null}</li>
     *     <li>其他 → trim 后的字符串</li>
     * </ul>
     * </p>
     *
     * @param version 原始版本号（可为 null）
     * @return 标准化后的版本号，若输入为 null 或空白则返回 null
     */
    public static String normalize(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
