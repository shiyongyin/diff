package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 对比方向——决定以哪一侧租户为"基准"进行差异计算。
 *
 * <p>
 * A_TO_B 意味着"以 A 为源、B 为目标"：A 有 B 无 → INSERT，A 无 B 有 → DELETE。
 * B_TO_A 则反之。方向选择会影响后续 Apply 的语义（参见 {@link com.diff.core.apply.PlanBuilder}）。
 * </p>
 *
 * <p>
 * <b>契约警告</b>：枚举名会序列化到 JSON / 落库，<b>不要随意重命名</b>。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum CompareDirection {
    A_TO_B,
    B_TO_A;

    /**
     * Jackson 反序列化入口。
     *
     * @param value 枚举名称字符串，不允许 {@code null}
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当 value 为 {@code null} 或无法匹配时
     */
    @JsonCreator
    public static CompareDirection fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("CompareDirection value must not be null");
        }
        return CompareDirection.valueOf(value);
    }

    /**
     * Jackson 序列化入口——返回枚举名称。
     *
     * @return 枚举名称字符串
     */
    @JsonValue
    public String toValue() {
        return name();
    }
}
