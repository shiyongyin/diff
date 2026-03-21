package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Diff Session 的生命周期状态。
 *
 * <p>
 * 状态流转：{@code CREATED → RUNNING → SUCCESS/FAILED}。
 * 后续 Apply/Rollback 会将 Session 标记为 {@code APPLYING} / {@code ROLLING_BACK}。
 * </p>
 *
 * <p>
 * <b>契约警告</b>：枚举名会序列化到 JSON / 落库，属于对外契约，<b>不要随意重命名</b>。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum SessionStatus {
    /** 已创建，尚未开始对比。 */
    CREATED,
    /** 对比执行中。 */
    RUNNING,
    /** 对比成功完成。 */
    SUCCESS,
    /** 对比失败。 */
    FAILED,
    /** 正在执行 Apply。 */
    APPLYING,
    /** 正在执行 Rollback。 */
    ROLLING_BACK;

    /**
     * Jackson 反序列化入口。
     *
     * @param value 枚举名称字符串，不允许 {@code null}
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当 value 为 {@code null} 或无法匹配时
     */
    @JsonCreator
    public static SessionStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("SessionStatus value must not be null");
        }
        return SessionStatus.valueOf(value);
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
