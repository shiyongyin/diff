package com.diff.standalone.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Diff 多数据源自动配置。
 *
 * <p>
 * <b>设计动机：</b>启动时对每个外部数据源执行连接验证（fail-fast）——若 URL/账号/网络不可达，
 * 立即阻止应用启动并抛出异常，避免运行时首次访问时才暴露问题，便于运维及早发现配置错误。
 * </p>
 *
 * <p>
 * 读取 {@code tenant-diff.datasources.*} 配置，创建额外数据源并注册到 {@link DiffDataSourceRegistry}。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DiffDataSourceProperties.class)
public class DiffDataSourceAutoConfiguration {

    /**
     * 创建 {@link DiffDataSourceRegistry} 并注册配置中的外部数据源。
     *
     * <p>主数据源由 Spring 容器管理，其余命名数据源用 {@link HikariDataSource} 包装并注册，
     * 启动期间即验证连接，异常会阻止应用启动。</p>
     *
     * @param primaryDataSource 主数据源
     * @param properties 外部数据源配置
     * @return 已初始化的 {@link DiffDataSourceRegistry}
     */
    @Bean
    @ConditionalOnMissingBean
    public DiffDataSourceRegistry diffDataSourceRegistry(
            DataSource primaryDataSource,
            DiffDataSourceProperties properties) {
        DiffDataSourceRegistry registry = new DiffDataSourceRegistry(primaryDataSource);

        if (properties.getDatasources() != null) {
            properties.getDatasources().forEach((key, config) -> {
                if (DiffDataSourceRegistry.PRIMARY_KEY.equals(key)) {
                    log.warn("跳过 '{}' 数据源配置：primary 为保留 key", key);
                    return;
                }
                HikariDataSource ds = createDataSource(key, config);
                registry.register(key, ds);
                log.info("注册 Diff 数据源: key={}, url={}", key, config.getUrl());
            });
        }

        log.info("DiffDataSourceRegistry 初始化完成, 已注册数据源: {}", registry.registeredKeys());
        return registry;
    }

    /**
     * 基于配置构造 Hikari 数据源并强制采用 fail-fast 策略。
     *
     * @param key 数据源 key，主要用于日志
     * @param config 数据源配置
     * @return 初始化完成的 {@link HikariDataSource}
     */
    private static HikariDataSource createDataSource(
            String key, DiffDataSourceProperties.DiffDataSourceConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("diff-ds-" + key);
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());
        hikari.setDriverClassName(config.getDriverClassName());
        hikari.setMaximumPoolSize(config.getMaximumPoolSize());
        hikari.setMinimumIdle(config.getMinimumIdle());
        hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikari.setMaxLifetime(config.getMaxLifetimeMs());
        hikari.setIdleTimeout(config.getIdleTimeoutMs());
        hikari.setReadOnly(config.isReadOnly());
        // fail-fast：强制启动时验证连接，失败则阻止应用启动
        hikari.setInitializationFailTimeout(1);
        return new HikariDataSource(hikari);
    }
}
