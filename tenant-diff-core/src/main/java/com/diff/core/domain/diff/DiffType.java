package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 差异类型枚举（覆盖 business / table / record 三个层级）。
 *
 * <p>
 * 对比引擎产出的每一层 diff 都携带一个 DiffType 来表达差异性质。
 * 为什么分层级定义而非只有 INSERT/UPDATE/DELETE：因为"业务整体新增"
 * 和"单条记录新增"在 Apply 阶段需要不同的处理逻辑——前者可能需要
 * 初始化整张表，后者只需插入一行。
 * </p>
 *
 * <h3>业务级 diffType 推导规则（SSOT）</h3>
 * <ul>
 *   <li>存在性优先：source 有且 target 无 → {@link #BUSINESS_INSERT}；反之 → {@link #BUSINESS_DELETE}</li>
 *   <li>两侧都存在时，根据记录级统计推导：
 *     <ul>
 *       <li>{@code updateCount > 0} 或 {@code (insertCount > 0 && deleteCount > 0)} → {@link #UPDATE}</li>
 *       <li>仅 {@code insertCount > 0} → {@link #INSERT}</li>
 *       <li>仅 {@code deleteCount > 0} → {@link #DELETE}</li>
 *       <li>其余 → {@link #NOOP}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>
 * <b>契约警告</b>：枚举名会序列化到 JSON / 落库，属于对外契约的一部分，
 * 重命名将导致反序列化失败或数据不一致，<b>不要随意重命名</b>。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum DiffType {
    /** 业务对象整体新增（source 有，target 无）。 */
    BUSINESS_INSERT,
    /** 业务对象整体删除（source 无，target 有）。 */
    BUSINESS_DELETE,
    /** 表级新增（source 有该表，target 无）。 */
    TABLE_INSERT,
    /** 表级删除（source 无该表，target 有）。 */
    TABLE_DELETE,
    /** 记录级新增。 */
    INSERT,
    /** 记录级更新（至少一个业务字段有差异）。 */
    UPDATE,
    /** 记录级删除。 */
    DELETE,
    /** 无差异（内容一致，不产生 Apply 动作）。 */
    NOOP;

    /**
     * Jackson 反序列化入口。
     *
     * @param value 枚举名称字符串，不允许 {@code null}
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当 value 为 {@code null} 或无法匹配时
     */
    @JsonCreator
    public static DiffType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DiffType value must not be null");
        }
        return DiffType.valueOf(value);
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
