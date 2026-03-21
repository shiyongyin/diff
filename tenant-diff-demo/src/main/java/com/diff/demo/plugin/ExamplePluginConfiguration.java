package com.diff.demo.plugin;

import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.core.spi.apply.BusinessApplySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 示例插件注册配置。
 */
@Configuration
public class ExamplePluginConfiguration {

    @Bean
    public ExampleProductPlugin exampleProductPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
        return new ExampleProductPlugin(objectMapper, dataSourceRegistry);
    }

    @Bean
    public BusinessApplySupport exampleProductApplySupport(ObjectMapper objectMapper, ExampleProductPlugin plugin) {
        return new ExampleProductApplySupport(objectMapper, plugin.schema());
    }

    @Bean
    public ExampleOrderPlugin exampleOrderPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
        return new ExampleOrderPlugin(objectMapper, dataSourceRegistry);
    }

    @Bean
    public BusinessApplySupport exampleOrderApplySupport(ObjectMapper objectMapper, ExampleOrderPlugin plugin) {
        return new ExampleOrderApplySupport(objectMapper, plugin.schema());
    }
}
