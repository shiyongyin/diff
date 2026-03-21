package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 差异记录的用户决策类型——控制该条 diff 是否参与 Apply。
 *
 * <p>
 * v1 版本中引擎默认将所有记录标记为 {@link #ACCEPT}。
 * 设计为枚举是为了后续支持"选择性同步"：用户可在 UI 上将部分记录标记为
 * {@link #SKIP}，Apply 阶段跳过这些记录。
 * </p>
 *
 * <p>
 * <b>契约警告</b>：枚举名会序列化到 JSON / 落库，<b>不要随意重命名</b>。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum DecisionType {
    /** 接受该差异，Apply 时执行。 */
    ACCEPT,
    /** 跳过该差异，Apply 时忽略。 */
    SKIP;

    /**
     * Jackson 反序列化入口。
     *
     * @param value 枚举名称字符串，不允许 {@code null}
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当 value 为 {@code null} 或无法匹配时
     */
    @JsonCreator
    public static DecisionType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DecisionType value must not be null");
        }
        return DecisionType.valueOf(value);
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
