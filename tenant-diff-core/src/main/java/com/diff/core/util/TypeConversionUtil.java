package com.diff.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 差异对比与 Apply 阶段的集中式类型转换工具。
 *
 * <p>
 * 跨租户场景中，同一逻辑字段在不同数据源/序列化路径下可能呈现不同的 Java 类型
 * （如 JSON 反序列化后 id 可能是 Integer 而非 Long），直接 {@code Objects.equals}
 * 会产生"伪差异"。本工具将各类型统一归一化后再比较，消除类型差异带来的误报。
 * </p>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>转换失败时返回 {@code null} 而非抛异常，由调用方决定如何处理</li>
 *   <li>布尔语义转换（true→1, false→0）默认关闭，仅在显式 opt-in 时启用</li>
 * </ul>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public final class TypeConversionUtil {
    private TypeConversionUtil() {
    }

    /**
     * 将任意值转为 {@link Long}（不支持布尔语义）。
     *
     * @param value 待转换值，允许 {@code null}
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static Long toLong(Object value) {
        return toLong(value, false);
    }

    /**
     * 将任意值转为 {@link Long}。
     *
     * @param value        待转换值，允许 {@code null}
     * @param allowBoolean 是否将 {@code Boolean} 映射为 1/0
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static Long toLong(Object value, boolean allowBoolean) {
        if (value == null) {
            return null;
        }
        if (allowBoolean && value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof BigInteger bi) {
            return bi.longValue();
        }
        if (value instanceof BigDecimal bd) {
            return bd.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(Objects.toString(value, ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将任意值转为 {@link Integer}（不支持布尔语义）。
     *
     * @param value 待转换值，允许 {@code null}
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static Integer toInteger(Object value) {
        return toInteger(value, false, false);
    }

    /**
     * 将任意值转为 {@link Integer}。
     *
     * @param value               待转换值，允许 {@code null}
     * @param allowBoolean        是否将 {@code Boolean} 映射为 1/0
     * @param allowBooleanStrings 是否将字符串 "true"/"false" 映射为 1/0
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static Integer toInteger(Object value, boolean allowBoolean, boolean allowBooleanStrings) {
        if (value == null) {
            return null;
        }
        if (allowBoolean && value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return l.intValue();
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String normalized = Objects.toString(value, "").trim();
        if (allowBooleanStrings) {
            if ("true".equalsIgnoreCase(normalized)) {
                return 1;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将任意值转为 {@link BigDecimal}。
     *
     * @param value 待转换值，允许 {@code null}
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(Objects.toString(value, ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将任意值转为 {@link LocalDateTime}（不解析字符串）。
     *
     * @param value 待转换值，允许 {@code null}
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static LocalDateTime toLocalDateTime(Object value) {
        return toLocalDateTime(value, false);
    }

    /**
     * 将任意值转为 {@link LocalDateTime}。
     *
     * @param value       待转换值，允许 {@code null}
     * @param allowString 是否尝试将字符串按 ISO-8601 格式解析
     * @return 转换结果，无法转换时返回 {@code null}
     */
    public static LocalDateTime toLocalDateTime(Object value, boolean allowString) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime();
        }
        if (allowString) {
            try {
                return LocalDateTime.parse(Objects.toString(value, ""));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
