package com.diff.demo.plugin;

import com.diff.core.domain.schema.BusinessSchema;
import com.diff.standalone.apply.support.AbstractSchemaBusinessApplySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 示例订单的 Apply 支持 — 演示多表 + 外键的 Apply 写入。
 *
 * <p>
 * 核心行为由基类 {@link AbstractSchemaBusinessApplySupport} 提供：
 * <ul>
 *     <li>子表 {@code example_order_item} 的 {@code order_id} 外键通过 IdMapping 自动替换</li>
 *     <li>派生字段（main_business_key/parent_business_key）自动清理</li>
 *     <li>类型归一化（decimal/int 等）</li>
 * </ul>
 * </p>
 */
public class ExampleOrderApplySupport extends AbstractSchemaBusinessApplySupport {

    private static final String BUSINESS_TYPE = "EXAMPLE_ORDER";
    private final JdbcTemplate jdbcTemplate;

    public ExampleOrderApplySupport(ObjectMapper objectMapper, BusinessSchema schema, JdbcTemplate jdbcTemplate) {
        super(objectMapper, schema);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public Long locateTargetId(String tableName, String recordBusinessKey, Long targetTenantId) {
        if (jdbcTemplate == null || tableName == null || recordBusinessKey == null || recordBusinessKey.isBlank()
            || targetTenantId == null) {
            return null;
        }
        try {
            if ("example_order".equals(tableName)) {
                return jdbcTemplate.queryForObject(
                    "SELECT id FROM example_order WHERE tenantsid = ? AND order_code = ?",
                    Long.class, targetTenantId, recordBusinessKey
                );
            }
            if ("example_order_item".equals(tableName)) {
                return jdbcTemplate.queryForObject(
                    "SELECT id FROM example_order_item WHERE tenantsid = ? AND item_code = ?",
                    Long.class, targetTenantId, recordBusinessKey
                );
            }
            if ("example_order_item_detail".equals(tableName)) {
                return jdbcTemplate.queryForObject(
                    "SELECT id FROM example_order_item_detail WHERE tenantsid = ? AND detail_code = ?",
                    Long.class, targetTenantId, recordBusinessKey
                );
            }
            return null;
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }
}
