package com.diff.core.domain.apply;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Apply 审计记录状态——追踪一次 Apply 的生命周期。
 *
 * <p>
 * 状态流转：{@code RUNNING → SUCCESS/FAILED}，回滚时：{@code SUCCESS → ROLLING_BACK → ROLLED_BACK}。
 * CAS 乐观锁保证并发安全，避免同一 Apply 被重复回滚。
 * </p>
 *
 * <p>
 * <b>契约警告</b>：枚举名会序列化到 JSON / 落库，<b>不要随意重命名</b>。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum ApplyRecordStatus {
    /** Apply 执行中。 */
    RUNNING,
    /** Apply 执行成功。 */
    SUCCESS,
    /** Apply 执行失败。 */
    FAILED,
    /** 已回滚。 */
    ROLLED_BACK,
    /** 回滚执行中。 */
    ROLLING_BACK;

    /**
     * Jackson 反序列化入口。
     *
     * @param value 枚举名称字符串，不允许 {@code null}
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当 value 为 {@code null} 或无法匹配时
     */
    @JsonCreator
    public static ApplyRecordStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ApplyRecordStatus value must not be null");
        }
        return ApplyRecordStatus.valueOf(value);
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
