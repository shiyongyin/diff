package com.diff.standalone.apply.support;

import com.diff.core.apply.IdMapping;
import com.diff.core.spi.apply.BusinessApplySupport;
import com.diff.core.domain.model.DerivedFieldNames;
import com.diff.core.domain.schema.BusinessSchema;
import com.diff.core.util.TypeConversionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 {@link BusinessSchema} 的通用 Apply 支持基类。
 *
 * <p>
 * <b>模板方法模式（WHY）</b>：Apply 的字段转换流程（移除派生字段、外键替换、类型归一化）对多数业务通用，
 * 但部分业务需额外处理（如特殊编码转换、默认值注入）。本类定义固定流程骨架，子类通过
 * {@link #customizeFields} 钩子注入业务特定逻辑，避免重复实现通用步骤。
 * </p>
 *
 * <p>
 * <b>Schema 驱动（WHY）</b>：表关系、外键列、字段类型等元数据由 {@link BusinessSchema} 声明，
 * 无需在代码中硬编码。新增业务类型时只需扩展 Schema 配置，Apply 支持可自动适配，
 * 降低维护成本并保证元数据单一来源。
 * </p>
 *
 * <p>
 * 核心职责：
 * <ul>
 *     <li>移除派生字段（main_business_key/parent_business_key/base* 等）</li>
 *     <li>根据表关系和 {@link IdMapping} 自动替换外键字段</li>
 *     <li>根据 Schema 定义的字段类型提示进行类型归一化</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public abstract class AbstractSchemaBusinessApplySupport implements BusinessApplySupport {
    /** 租户 ID 字段名。 */
    protected static final String COL_TENANTSID = "tenantsid";
    /** 主业务键字段名。 用于跨租户对齐时标识主记录归属 */
    protected static final String COL_MAIN_BUSINESS_KEY = DerivedFieldNames.MAIN_BUSINESS_KEY;
    /** 父业务键字段名。 用于子表记录关联父记录的业务键 */
    protected static final String COL_PARENT_BUSINESS_KEY = DerivedFieldNames.PARENT_BUSINESS_KEY;

    /** 业务 Schema。 用于存储业务 Schema */
    private final BusinessSchema schema;
    /** 子表关系映射。 用于存储子表关系映射 */
    private final Map<String, BusinessSchema.TableRelation> relationByChildTable;
    /** 对象映射器。 用于将对象转换为 JSON 字符串 */
    private final ObjectMapper objectMapper;

    protected AbstractSchemaBusinessApplySupport(ObjectMapper objectMapper, BusinessSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("schema is null");
        }
        this.schema = schema;
        this.objectMapper = objectMapper;
        // 初始化子表关系映射
        Map<String, BusinessSchema.TableRelation> map = new HashMap<>();
        if (schema.getRelations() != null) {
            for (BusinessSchema.TableRelation relation : schema.getRelations()) {
                if (relation == null || relation.getChildTable() == null || relation.getChildTable().isBlank()) {
                    continue;
                }
                // 将子表关系映射到子表表名 
                map.putIfAbsent(relation.getChildTable(), relation);
            }
        }
        this.relationByChildTable = Map.copyOf(map);
    }

    /**
     * 获取当前业务类型的 Schema 配置。
     *
     * @return 不可变的 BusinessSchema 实例
     */
    protected BusinessSchema getSchema() {
        return schema;
    }

    /**
     * 转换字段用于 INSERT 操作。
     *
     * <p>处理流程：
     * <ol>
     *     <li>复制原始字段到结果 Map</li>
     *     <li>设置 tenantsid 为目标租户 ID</li>
     *     <li>根据表关系替换外键字段（从 IdMapping 查找父记录的新 ID）</li>
     *     <li>移除派生字段（main_business_key/parent_business_key/base* 等）</li>
     *     <li>调用子类钩子进行业务特定处理</li>
     *     <li>根据 Schema 类型提示进行类型归一化</li>
     * </ol>
     * </p>
     */
    @Override
    public Map<String, Object> transformForInsert(
        String tableName,
        String recordBusinessKey,
        Map<String, Object> fields,
        Long targetTenantId,
        IdMapping idMapping
    ) {
        Map<String, Object> result = new HashMap<>();
        if (fields != null) {
            result.putAll(fields);
        }

        // 强制设置目标租户 ID
        if (targetTenantId != null) {
            result.put(COL_TENANTSID, targetTenantId);
        }

        // 外键替换：根据表关系定义，从 IdMapping 中查找父记录的新 ID
        BusinessSchema.TableRelation relation = relationByChildTable.get(tableName);
        if (relation != null && idMapping != null) {
            String parentBusinessKey = resolveParentBusinessKey(result);
            if (parentBusinessKey != null && !parentBusinessKey.isBlank()) {
                // 使用父记录的业务键从 IdMapping 获取新插入的物理 ID
                Long newParentId = idMapping.get(relation.getParentTable(), parentBusinessKey);
                if (newParentId == null) {
                    newParentId = locateTargetId(relation.getParentTable(), parentBusinessKey, targetTenantId);
                }
                if (newParentId != null) {
                    result.put(relation.getFkColumn(), newParentId);
                }
            }
        }

        // 移除仅用于对比阶段的派生字段
        removeDerivedFields(result);
        // 子类钩子：业务特定字段处理
        customizeFields(tableName, recordBusinessKey, result);
        // 类型归一化：确保字段类型与数据库列类型匹配
        normalizeFieldTypes(tableName, result);
        return result;
    }

    /**
     * 从字段 Map 中解析父记录的 businessKey。
     *
     * <p>优先使用 parent_business_key，若为空则回退到 main_business_key。</p>
     *
     * @param fields 字段 Map
     * @return 父记录的 businessKey，可能为 null 或空串
     */
    protected String resolveParentBusinessKey(Map<String, Object> fields) {
        // 优先使用 parent_business_key
        String parentBusinessKey = toStringOrNull(fields.get(COL_PARENT_BUSINESS_KEY));
        // 如果 parent_business_key 为空，则回退到 main_business_key
        if (parentBusinessKey == null || parentBusinessKey.isBlank()) {
            parentBusinessKey = toStringOrNull(fields.get(COL_MAIN_BUSINESS_KEY));
        }
        return parentBusinessKey;
    }

    /**
     * 移除派生字段和系统管理字段。
     *
     * <p>被移除的字段类型：
     * <ul>
     *     <li>main_business_key/parent_business_key：对比阶段的临时标识</li>
     *     <li>base* 开头的字段：继承跟踪字段（如 baseinstructionid）</li>
     *     <li>version/data_modify_time：乐观锁和时间戳由数据库管理</li>
     * </ul>
     * </p>
     */
    protected void removeDerivedFields(Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        fields.remove(COL_MAIN_BUSINESS_KEY);
        fields.remove(COL_PARENT_BUSINESS_KEY);
        // 移除所有 base* 开头的继承跟踪字段
        fields.keySet().removeIf(k -> k != null && k.startsWith("base"));
        fields.remove("version");
        fields.remove("data_modify_time");
    }

    /**
     * 子类钩子：对字段进行业务特定的定制处理。
     *
     * <p>默认空实现。子类可在此处添加编码转换、默认值注入、条件过滤等逻辑。</p>
     *
     * @param tableName 表名
     * @param recordBusinessKey 记录的 businessKey
     * @param fields 待写入的字段 Map，可原地修改
     */
    protected void customizeFields(String tableName, String recordBusinessKey, Map<String, Object> fields) {
    }

    /**
     * 根据 Schema 中的字段类型提示，将 fields 中的值归一化为数据库兼容类型。
     *
     * <p>支持 bigint/int/decimal/datetime/json 等类型，避免类型不匹配导致的写入失败。</p>
     *
     * @param tableName 表名
     * @param fields 待归一化的字段 Map，可原地修改
     */
    protected void normalizeFieldTypes(String tableName, Map<String, Object> fields) {
        if (tableName == null || fields == null) {
            return;
        }
        Map<String, String> typeHints = schema.getFieldTypesByTable() == null ? null : schema.getFieldTypesByTable().get(tableName);
        if (typeHints == null || typeHints.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : typeHints.entrySet()) {
            String col = entry.getKey();
            String type = entry.getValue();
            if (col == null || type == null) {
                continue;
            }
            if (!fields.containsKey(col)) {
                continue;
            }
            Object value = fields.get(col);
            fields.put(col, convertValue(value, type));
        }
    }

    /**
     * 将值转换为指定类型。
     *
     * @param value 值
     * @param type 类型
     * @return 转换后的值
     */
    private Object convertValue(Object value, String type) {
        if (value == null) {
            return null;
        }
        String t = type.toLowerCase();
        if ("bigint".equals(t)) {
            return toLong(value);
        }
        if ("int".equals(t) || "tinyint".equals(t)) {
            return toInteger(value);
        }
        if ("decimal".equals(t)) {
            return toBigDecimal(value);
        }
        if ("datetime".equals(t)) {
            return toLocalDateTime(value);
        }
        if ("json".equals(t)) {
            if (value instanceof String) {
                return value;
            }
            try {
                return objectMapper == null ? String.valueOf(value) : objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return value;
    }

    /**
     * 将值转换为字符串。
     *
     * @param value 值
     * @return 字符串，可能为 null
     */
    protected static String toStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 将值转换为 Long。
     *
     * @param value 值
     * @return 转换后的 Long，无法转换时返回 null
     */
    protected static Long toLong(Object value) {
        return TypeConversionUtil.toLong(value, true);
    }

    /**
     * 将值转换为 Integer。
     *
     * @param value 值
     * @return 转换后的 Integer，无法转换时返回 null
     */
    protected static Integer toInteger(Object value) {
        return TypeConversionUtil.toInteger(value, true, true);
    }

    /**
     * 将值转换为 BigDecimal。
     *
     * @param value 值
     * @return 转换后的 BigDecimal，无法转换时返回 null
     */
    protected static BigDecimal toBigDecimal(Object value) {
        return TypeConversionUtil.toBigDecimal(value);
    }

    /**
     * 将值转换为 LocalDateTime。
     *
     * @param value 值
     * @return 转换后的 LocalDateTime，无法转换时返回 null
     */
    protected static LocalDateTime toLocalDateTime(Object value) {
        return TypeConversionUtil.toLocalDateTime(value, true);
    }
}
