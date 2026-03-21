package com.diff.standalone.plugin;

import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.model.RecordData;
import com.diff.core.domain.model.TableData;
import com.diff.core.domain.schema.BusinessSchema;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.scope.ScopeFilter;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单表业务的声明式 Plugin 基类——200 行样板代码降至 15 行。
 *
 * <p>
 * <b>设计动机：</b>大量业务类型仅涉及一张表，但当前接入流程要求实现完整的
 * {@link StandaloneBusinessTypePlugin} 接口，编写 SQL 查询、字段映射、RecordData 构建等
 * 大量重复代码。本基类将通用的单表 CRUD 逻辑封装为模板方法，子类只需声明表名和
 * businessKey 字段即可快速获得 Compare 能力；若要启用 Apply，仍需额外注册配套的
 * {@link com.diff.core.spi.apply.BusinessApplySupport}。
 * </p>
 *
 * <p>
 * 子类示例：
 * <pre>{@code
 * @Component
 * public class ContractPlugin extends SimpleTablePlugin {
 *     public ContractPlugin(ObjectMapper om, DiffDataSourceRegistry ds) { super(om, ds); }
 *     public String businessType()         { return "CONTRACT"; }
 *     protected String tableName()         { return "biz_contract"; }
 *     protected String businessKeyColumn() { return "contract_code"; }
 * }
 * }</pre>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-03-08
 * @see AbstractStandaloneBusinessPlugin
 */
public abstract class SimpleTablePlugin extends AbstractStandaloneBusinessPlugin {

    /**
     * @param objectMapper Jackson 序列化/反序列化工具
     * @param dataSourceRegistry Diff 数据源注册表，用于按 LoadOptions 解析 JdbcTemplate
     */
    protected SimpleTablePlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
        super(objectMapper, dataSourceRegistry);
    }

    // ==================== 子类必须实现 ====================

    /**
     * 业务类型标识（如 "CONTRACT"、"PRODUCT"）。
     *
     * @return 非空业务类型标识
     */
    @Override
    public abstract String businessType();

    /**
     * 主表名。
     *
     * @return 数据库表名（如 "biz_contract"）
     */
    protected abstract String tableName();

    /**
     * businessKey 对应的数据库字段名。
     *
     * @return 列名（如 "contract_code"）
     */
    protected abstract String businessKeyColumn();

    // ==================== 可选覆盖 ====================

    /**
     * 业务名称对应的数据库字段名（用于 {@link BusinessData#getBusinessName()} 展示）。
     *
     * <p>默认返回 {@code null}，此时以 businessKey 作为业务名称。</p>
     *
     * @return 列名，或 {@code null}
     */
    protected String businessNameColumn() {
        return null;
    }

    /**
     * 租户 ID 字段名，默认 {@code "tenantsid"}。
     *
     * @return 列名
     */
    protected String tenantIdColumn() {
        return "tenantsid";
    }

    /**
     * 需要额外忽略的字段（除全局默认之外的）。
     *
     * @return 字段名集合，默认为空
     */
    protected Set<String> additionalIgnoreFields() {
        return Collections.emptySet();
    }

    /**
     * 字段类型映射（需要特殊处理的字段，如 JSON/decimal/datetime）。
     *
     * @return fieldName → typeName 映射，默认为空
     */
    protected Map<String, String> fieldTypes() {
        return Collections.emptyMap();
    }

    // ==================== 自动实现（子类无需覆盖） ====================

    @Override
    public final BusinessSchema schema() {
        Map<String, Integer> tables = Map.of(tableName(), 0);

        Map<String, Set<String>> ignoreFields;
        Set<String> additional = additionalIgnoreFields();
        if (additional != null && !additional.isEmpty()) {
            ignoreFields = Map.of(tableName(), additional);
        } else {
            ignoreFields = Collections.emptyMap();
        }

        Map<String, Map<String, String>> types;
        Map<String, String> ft = fieldTypes();
        if (ft != null && !ft.isEmpty()) {
            types = Map.of(tableName(), ft);
        } else {
            types = Collections.emptyMap();
        }

        return BusinessSchema.builder()
            .tables(tables)
            .relations(Collections.emptyList())
            .ignoreFieldsByTable(ignoreFields)
            .fieldTypesByTable(types)
            .build();
    }

    @Override
    public final List<String> listBusinessKeys(Long tenantId, ScopeFilter filter) {
        return listBusinessKeys(tenantId, filter, null);
    }

    @Override
    public final List<String> listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options) {
        if (filter != null && filter.getBusinessKeys() != null && !filter.getBusinessKeys().isEmpty()) {
            return filter.getBusinessKeys();
        }
        JdbcTemplate jdbc = resolveJdbcTemplate(options);
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?",
            businessKeyColumn(), tableName(), tenantIdColumn());
        return jdbc.queryForList(sql, String.class, tenantId);
    }

    @Override
    public final BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options) {
        JdbcTemplate jdbc = resolveJdbcTemplate(options);
        String sql = String.format("SELECT * FROM %s WHERE %s = ? AND %s = ?",
            tableName(), tenantIdColumn(), businessKeyColumn());
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId, businessKey);

        List<RecordData> records = new ArrayList<>();
        String businessName = businessKey;

        for (Map<String, Object> row : rows) {
            Map<String, Object> fields = normalizeRecordFields(row);
            attachMainBusinessKey(fields, businessKey);

            String recordKey = asString(fields.get(businessKeyColumn()));
            String nameCol = businessNameColumn();
            String name = nameCol != null ? asString(fields.get(nameCol)) : null;
            if (name != null) {
                businessName = name;
            }

            records.add(buildRecordData(recordKey, fields, true, name));
        }

        TableData tableData = TableData.builder()
            .tableName(tableName())
            .dependencyLevel(0)
            .records(records)
            .build();

        return BusinessData.builder()
            .businessType(businessType())
            .businessTable(tableName())
            .businessKey(businessKey)
            .businessName(businessName)
            .tenantId(tenantId)
            .tables(List.of(tableData))
            .build();
    }

    @Override
    public final String buildRecordBusinessKey(String tableName, Map<String, Object> recordData) {
        if (recordData == null) {
            return null;
        }
        return asString(recordData.get(businessKeyColumn()));
    }
}
