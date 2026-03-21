package com.diff.demo.plugin;

import com.diff.core.domain.schema.BusinessSchema;
import com.diff.standalone.apply.support.AbstractSchemaBusinessApplySupport;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    public ExampleOrderApplySupport(ObjectMapper objectMapper, BusinessSchema schema) {
        super(objectMapper, schema);
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }
}
