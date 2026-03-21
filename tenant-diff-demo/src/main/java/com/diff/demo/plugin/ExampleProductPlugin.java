package com.diff.demo.plugin;

import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.plugin.SimpleTablePlugin;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 示例产品插件 — 演示 {@link SimpleTablePlugin} 的声明式单表接入。
 *
 * <p>只需声明 businessType、tableName、businessKeyColumn，
 * 框架自动处理 schema 定义、数据加载、业务键提取等逻辑。</p>
 */
public class ExampleProductPlugin extends SimpleTablePlugin {

    public ExampleProductPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
        super(objectMapper, dataSourceRegistry);
    }

    @Override
    public String businessType() {
        return "EXAMPLE_PRODUCT";
    }

    @Override
    protected String tableName() {
        return "example_product";
    }

    @Override
    protected String businessKeyColumn() {
        return "product_code";
    }

    @Override
    protected String businessNameColumn() {
        return "product_name";
    }
}
