package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 决策执行状态——记录每条 diff 决策在 Apply 阶段的实际执行结果。
 *
 * <p>
 * 用于审计追踪：哪些记录被成功应用、哪些被跳过、哪些执行失败。
 * 配合 {@link DecisionType} 可以完整还原"用户决策 → 实际执行"的全链路。
 * </p>
 *
 * <p>
 * <b>契约警告</b>：枚举名会序列化到 JSON / 落库，<b>不要随意重命名</b>。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum DecisionExecutionStatus {
    /** 待执行。 */
    PENDING,
    /** 因决策为 SKIP 而跳过。 */
    SKIPPED,
    /** 执行成功。 */
    SUCCESS,
    /** 执行失败。 */
    FAILED;

    /**
     * Jackson 反序列化入口。
     *
     * @param value 枚举名称字符串，不允许 {@code null}
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当 value 为 {@code null} 或无法匹配时
     */
    @JsonCreator
    public static DecisionExecutionStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DecisionExecutionStatus value must not be null");
        }
        return DecisionExecutionStatus.valueOf(value);
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
