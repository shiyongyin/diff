package com.diff.core.spi.apply;


import com.diff.core.apply.IdMapping;

import java.util.Map;

/**
 * 业务类型维度的 Apply 扩展点——解决"对比模型字段"到"可写入目标库字段"的转换问题。
 *
 * <p>
 * 对比阶段使用的 {@link com.diff.core.domain.model.RecordData#getFields()} 包含派生字段
 * （如 main_business_key）、源租户外键 id 等，这些值不能直接写入目标租户。
 * BusinessApplySupport 提供按业务类型定制的字段变换逻辑：
 * </p>
 * <ul>
 *   <li>移除派生字段（main_business_key / parent_business_key）</li>
 *   <li>根据 {@link IdMapping} 将外键替换为目标租户中新插入记录的物理 id</li>
 *   <li>按 Schema 做类型归一化（JSON/datetime/bigint 等）</li>
 * </ul>
 *
 * <h3>为什么 UPDATE 默认复用 INSERT 的变换逻辑</h3>
 * <p>
 * 多数情况下 UPDATE 与 INSERT 需要清洗的字段规则一致（都要移除派生字段、替换外键），
 * 默认复用可减少重复代码。个别业务类型如需差异化处理，可覆写
 * {@link #transformForUpdate}。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see com.diff.core.domain.schema.BusinessSchema
 * @see IdMapping
 */
public interface BusinessApplySupport {

    /**
     * 返回本扩展支持的业务类型标识。
     *
     * @return 业务类型（如 INSTRUCTION、OCR_TEMPLATE），用于路由匹配
     */
    String businessType();

    /**
     * INSERT 前的字段变换——移除派生字段、替换外键、类型归一化。
     *
     * @param tableName         目标表名
     * @param recordBusinessKey 记录的业务键
     * @param fields            原始字段（来自 diff 结果的 sourceFields）
     * @param targetTenantId    目标租户 id
     * @param idMapping         已插入记录的 businessKey → 新 id 映射
     * @return 清洗后的字段 map，可直接用于 SQL INSERT
     */
    Map<String, Object> transformForInsert(
        String tableName,
        String recordBusinessKey,
        Map<String, Object> fields,
        Long targetTenantId,
        IdMapping idMapping
    );

    /**
     * UPDATE 前的字段变换（默认复用 {@link #transformForInsert} 的逻辑）。
     *
     * @param tableName         目标表名
     * @param recordBusinessKey 记录的业务键
     * @param fields            原始字段
     * @param targetTenantId    目标租户 id
     * @param idMapping         已插入记录的映射
     * @return 清洗后的字段 map
     */
    default Map<String, Object> transformForUpdate(
        String tableName,
        String recordBusinessKey,
        Map<String, Object> fields,
        Long targetTenantId,
        IdMapping idMapping
    ) {
        return transformForInsert(tableName, recordBusinessKey, fields, targetTenantId, idMapping);
    }

    /**
     * DELETE 前的可选字段变换钩子。
     *
     * <p>
     * v1 执行器使用 {@code tenantsid + id} 定位记录，通常不需要字段变换。
     * 若业务需要在删除前做字段调整或定位辅助，可覆写此方法。
     * </p>
     *
     * @param tableName         目标表名
     * @param recordBusinessKey 记录的业务键
     * @param fields            原始字段
     * @param targetTenantId    目标租户 id
     * @param idMapping         已插入记录的映射
     * @return 处理后的字段 map（默认原样返回）
     */
    default Map<String, Object> transformForDelete(
        String tableName,
        String recordBusinessKey,
        Map<String, Object> fields,
        Long targetTenantId,
        IdMapping idMapping
    ) {
        return fields;
    }

    /**
     * 可选扩展：当 diff 结果中缺少 target.id 时，按 businessKey 定位目标物理 id。
     *
     * <p>v1 核心执行器默认不使用该能力，仅作为后续演进的扩展点保留。</p>
     *
     * @param tableName         目标表名
     * @param recordBusinessKey 记录的业务键
     * @param targetTenantId    目标租户 id
     * @return 目标物理 id，默认返回 {@code null}
     */
    default Long locateTargetId(String tableName, String recordBusinessKey, Long targetTenantId) {
        return null;
    }
}
