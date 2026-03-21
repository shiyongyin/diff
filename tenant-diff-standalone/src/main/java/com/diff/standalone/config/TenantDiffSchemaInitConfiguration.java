package com.diff.standalone.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 框架表 Schema 初始化的条件装配配置。
 *
 * <p>
 * 根据 {@code tenant-diff.standalone.schema.init-mode} 配置，选择性创建
 * {@link TenantDiffSchemaInitializer}：
 * <ul>
 *     <li>{@code none}（默认）：不创建任何初始化器 Bean</li>
 *     <li>{@code always}：每次启动都执行建表脚本</li>
 *     <li>{@code embedded-only}：仅对嵌入式数据库（H2/HSQL/Derby）执行</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 0.0.1
 * @see TenantDiffSchemaInitializer
 * @see TenantDiffProperties.SchemaProperties
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenantDiffProperties.class)
public class TenantDiffSchemaInitConfiguration {

    @Bean
    @ConditionalOnProperty(name = "tenant-diff.standalone.schema.init-mode",
                           havingValue = "always")
    public TenantDiffSchemaInitializer alwaysSchemaInitializer(DataSource dataSource,
                                                               TenantDiffProperties properties) {
        return new TenantDiffSchemaInitializer(dataSource, properties, false);
    }

    @Bean
    @ConditionalOnProperty(name = "tenant-diff.standalone.schema.init-mode",
                           havingValue = "embedded-only")
    public TenantDiffSchemaInitializer embeddedOnlySchemaInitializer(DataSource dataSource,
                                                                     TenantDiffProperties properties) {
        return new TenantDiffSchemaInitializer(dataSource, properties, true);
    }
}
