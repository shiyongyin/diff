package com.diff.core.apply;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Apply 阶段的"业务键 → 新物理 id"内存映射表。
 *
 * <p>
 * 跨租户同步中，INSERT 主表记录后数据库会生成新的自增 id，
 * 但子表的外键字段仍引用的是源租户的旧 id。为了保证子表插入时外键指向
 * 目标租户中正确的主表记录，需要在内存中维护 businessKey → newId 的映射，
 * 由 {@link com.diff.core.spi.apply.BusinessApplySupport} 在字段变换时查询并替换。
 * </p>
 *
 * <h3>Key 格式</h3>
 * <p>{@code tableName::recordBusinessKey}，通过双冒号分隔以避免与业务键本身的分隔符冲突。</p>
 *
 * <h3>线程安全</h3>
 * <p>非线程安全；设计上每个 Apply 执行流程独占一个实例。</p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public class IdMapping {
    private final Map<String, Long> map = new HashMap<>();

    /**
     * 记录一条 INSERT 成功后的 businessKey → 新 id 映射。
     *
     * <p>任一参数为 {@code null} 或空白时静默忽略，因为并非所有表都有外键关系。</p>
     *
     * @param tableName         表名
     * @param recordBusinessKey 记录的业务键
     * @param newId             数据库生成的新物理 id
     */
    public void put(String tableName, String recordBusinessKey, Long newId) {
        if (tableName == null || tableName.isBlank() || recordBusinessKey == null || recordBusinessKey.isBlank() || newId == null) {
            return;
        }
        map.put(compositeKey(tableName, recordBusinessKey), newId);
    }

    /**
     * 查询指定记录在目标租户中的新物理 id。
     *
     * <p>典型调用场景：子表 INSERT 前，需要将外键字段替换为父表记录的新 id。</p>
     *
     * @param tableName         表名
     * @param recordBusinessKey 记录的业务键
     * @return 新物理 id，未找到时返回 {@code null}
     */
    public Long get(String tableName, String recordBusinessKey) {
        if (tableName == null || tableName.isBlank() || recordBusinessKey == null || recordBusinessKey.isBlank()) {
            return null;
        }
        return map.get(compositeKey(tableName, recordBusinessKey));
    }

    /**
     * 返回当前映射快照（只读）。
     *
     * <p>用于 JSON 序列化到审计记录与日志输出，调用方不应修改返回值。</p>
     *
     * @return 只读映射快照，永不为 {@code null}
     */
    @JsonValue
    public Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    private static String compositeKey(String tableName, String recordBusinessKey) {
        return Objects.toString(tableName, "") + "::" + Objects.toString(recordBusinessKey, "");
    }
}
