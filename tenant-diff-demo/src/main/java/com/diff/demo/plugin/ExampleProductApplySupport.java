package com.diff.demo.plugin;

import com.diff.core.domain.schema.BusinessSchema;
import com.diff.standalone.apply.support.SimpleTableApplySupport;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 示例产品的 Apply 支持 — 使用 {@link SimpleTableApplySupport} 的零代码实现。
 */
public class ExampleProductApplySupport extends SimpleTableApplySupport {

    public ExampleProductApplySupport(ObjectMapper objectMapper, BusinessSchema schema) {
        super("EXAMPLE_PRODUCT", objectMapper, schema);
    }
}
