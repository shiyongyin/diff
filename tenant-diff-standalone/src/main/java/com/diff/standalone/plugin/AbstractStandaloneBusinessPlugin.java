package com.diff.standalone.plugin;

import com.diff.core.domain.model.DerivedFieldNames;
import com.diff.core.domain.model.RecordData;
import com.diff.core.domain.model.TableData;
import com.diff.core.util.TypeConversionUtil;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone 插件的通用解析基类。
 *
 * <p>
 * <b>设计动机：</b>将 JSON 解析、byte[] 转 UTF-8、派生字段附加、RecordData 构建等通用逻辑集中到基类，
 * 避免各业务插件重复实现，同时确保所有插件在 fingerprint 计算、外键替换等环节使用一致的规范化规则。
 * </p>
 *
 * <p>
 * 提供的通用能力：
 * <ul>
 *     <li>字段规范化：JSON 字段解析、byte[] 转 UTF-8 字符串</li>
 *     <li>派生字段附加：main_business_key/parent_business_key 的绑定</li>
 *     <li>RecordData 构建：从数据库行记录构建标准化模型</li>
 *     <li>IN 占位符生成：用于批量查询 SQL 构建</li>
 * </ul>
 * </p>
 *
 * <p>子类需实现 {@link #buildRecordBusinessKey} 以定义各表的业务键构建规则。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public abstract class AbstractStandaloneBusinessPlugin implements StandaloneBusinessTypePlugin {
    protected final ObjectMapper objectMapper;
    protected final DiffDataSourceRegistry dataSourceRegistry;

    protected AbstractStandaloneBusinessPlugin() {
        this.objectMapper = null;
        this.dataSourceRegistry = null;
    }

    protected AbstractStandaloneBusinessPlugin(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataSourceRegistry = null;
    }

    protected AbstractStandaloneBusinessPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
        this.objectMapper = objectMapper;
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 根据 LoadOptions 中的 dataSourceKey 解析 JdbcTemplate。
     *
     * @param options 加载选项；null 时使用主数据源
     * @return 对应的 JdbcTemplate
     * @throws IllegalStateException dataSourceRegistry 未配置时
     */
    protected JdbcTemplate resolveJdbcTemplate(LoadOptions options) {
        if (dataSourceRegistry == null) {
            throw new IllegalStateException("dataSourceRegistry is not configured");
        }
        String key = options == null ? null : options.getDataSourceKey();
        return dataSourceRegistry.resolve(key);
    }

    /**
     * 规范化记录字段。
     *
     * <p>处理内容：
     * <ul>
     *     <li>将指定的 JSON 字段格式化为标准 JSON 字符串</li>
     *     <li>将所有 byte[] 类型字段转换为 UTF-8 字符串</li>
     * </ul>
     * </p>
     *
     * @param recordData 原始数据库行记录
     * @param jsonFieldNames 需要 JSON 格式化的字段名列表
     * @return 规范化后的字段 Map
     */
    protected Map<String, Object> normalizeRecordFields(Map<String, Object> recordData, String... jsonFieldNames) {
        Map<String, Object> processed = new HashMap<>();
        if (recordData != null) {
            for (Map.Entry<String, Object> entry : recordData.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                processed.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue());
            }
        }

        // 处理指定的 JSON 字段：格式化为标准 JSON 字符串
        if (jsonFieldNames != null) {
            for (String fieldName : jsonFieldNames) {
                if (fieldName == null || !processed.containsKey(fieldName)) {
                    continue;
                }
                Object fieldValue = processed.get(fieldName);
                if (fieldValue != null) {
                    processed.put(fieldName, convertJsonFieldToString(fieldValue));
                }
            }
        }

        // 尽力处理：将剩余的 byte[] 字段转换为 UTF-8 字符串
        for (Map.Entry<String, Object> entry : new ArrayList<>(processed.entrySet())) {
            Object v = entry.getValue();
            if (v instanceof byte[] bytes) {
                processed.put(entry.getKey(), new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return processed;
    }

    /**
     * 按表名构建 RecordData，业务键由子类 {@link #buildRecordBusinessKey} 提供。
     *
     * @param tableName   表名
     * @param processed   已规范化的字段 Map
     * @param publicFlag  是否公开
     * @param businessNote 业务备注
     * @return RecordData 实例
     */
    protected RecordData buildRecordDataByTable(String tableName, Map<String, Object> processed, boolean publicFlag, String businessNote) {
        return buildRecordData(buildRecordBusinessKey(tableName, processed), processed, publicFlag, businessNote);
    }

    /**
     * 从已规范化的字段构建 RecordData。
     *
     * @param recordBusinessKey 记录级 businessKey
     * @param processed        已规范化的字段 Map
     * @param publicFlag       是否公开
     * @param businessNote     业务备注
     * @return RecordData 实例
     */
    protected RecordData buildRecordData(String recordBusinessKey, Map<String, Object> processed, boolean publicFlag, String businessNote) {
        return RecordData.builder()
            .id(parseLong(processed == null ? null : processed.get("id")))
            .businessKey(recordBusinessKey)
            .businessNote(businessNote)
            .publicFlag(publicFlag)
            .fields(processed)
            .fingerprint(null)
            .modifyTime(parseLocalDateTime(processed == null ? null : processed.get("data_modify_time")))
            .build();
    }

    /**
     * 为记录列表附加主业务键（main_business_key）。
     *
     * @param records        记录列表（可为 null 或空）
     * @param mainBusinessKey 主表 businessKey
     */
    protected void attachMainBusinessKey(List<Map<String, Object>> records, String mainBusinessKey) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (Map<String, Object> record : records) {
            if (record == null) {
                continue;
            }
            record.put(DerivedFieldNames.MAIN_BUSINESS_KEY, mainBusinessKey);
        }
    }

    /**
     * 为子表记录附加父记录的业务键。
     *
     * <p>用于建立子表记录与父表记录的业务键关联，供 Apply 阶段进行外键替换时使用。</p>
     *
     * @param childRecords 子表记录列表
     * @param parentIdToKeyMap 父表 id 到业务键的映射
     * @param parentIdField 子表中引用父表 id 的字段名（外键字段）
     */
    protected void attachParentBusinessKey(List<Map<String, Object>> childRecords, Map<Long, String> parentIdToKeyMap, String parentIdField) {
        if (childRecords == null || childRecords.isEmpty()) {
            return;
        }
        for (Map<String, Object> record : childRecords) {
            if (record == null) {
                continue;
            }
            // 通过外键字段值查找父记录的业务键
            Long parentId = parseLong(record.get(parentIdField));
            String parentKey = parentId == null ? null : parentIdToKeyMap.get(parentId);
            record.put(DerivedFieldNames.PARENT_BUSINESS_KEY, parentKey);
        }
    }

    /**
     * 构建空表 TableData。
     *
     * @param tableName       表名
     * @param dependencyLevel 依赖层级
     * @return 无记录的 TableData
     */
    protected TableData emptyTable(String tableName, int dependencyLevel) {
        return TableData.builder()
            .tableName(tableName)
            .dependencyLevel(dependencyLevel)
            .records(List.of())
            .build();
    }

    /**
     * 为单条记录附加主业务键（main_business_key）。
     *
     * <p>重载便捷方法，避免调用方为单条记录构造 List。</p>
     *
     * @param fields         单条记录字段（可为 null）
     * @param mainBusinessKey 主表 businessKey
     */
    protected void attachMainBusinessKey(Map<String, Object> fields, String mainBusinessKey) {
        if (fields != null) {
            fields.put(DerivedFieldNames.MAIN_BUSINESS_KEY, mainBusinessKey);
        }
    }

    /**
     * 将对象安全转为 String。
     *
     * @param value 原始值
     * @return 字符串表示，null 输入返回 null
     */
    protected static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 根据表名和记录数据构建记录级 businessKey。子类必须实现。
     *
     * @param tableName  表名
     * @param recordData 原始记录字段
     * @return 记录级 businessKey
     */
    public abstract String buildRecordBusinessKey(String tableName, Map<String, Object> recordData);

    /**
     * 将 JSON 字段值转为标准 JSON 字符串（pretty-print）。
     *
     * @param fieldValue 原始字段值（可为 byte[] 或 String）
     * @return 格式化后的 JSON 字符串，解析失败时回退为原始字符串
     */
    protected String convertJsonFieldToString(Object fieldValue) {
        if (fieldValue == null) {
            return null;
        }
        try {
            String jsonStr = fieldValue instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(fieldValue);
            if (objectMapper == null) {
                return jsonStr;
            }
            Object jsonObj = objectMapper.readValue(jsonStr, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObj);
        } catch (Exception e) {
            if (fieldValue instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return String.valueOf(fieldValue);
        }
    }

    /**
     * 将对象转为 Long。
     *
     * @param value 原始值
     * @return 转换后的 Long，无法转换时返回 null
     */
    protected Long parseLong(Object value) {
        return TypeConversionUtil.toLong(value);
    }

    /**
     * 将对象转为 LocalDateTime。
     *
     * @param value 原始值
     * @return 转换后的 LocalDateTime，无法转换时返回 null
     */
    protected LocalDateTime parseLocalDateTime(Object value) {
        return TypeConversionUtil.toLocalDateTime(value);
    }

    /**
     * 生成 IN 子句占位符（如 "?, ?, ?"）。
     *
     * @param size 占位符数量（必须为正数）
     * @return 逗号分隔的占位符字符串
     * @throws IllegalArgumentException size 非正
     */
    protected String buildInPlaceholders(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("IN clause size must be positive");
        }
        return String.join(", ", Collections.nCopies(size, "?"));
    }
}
