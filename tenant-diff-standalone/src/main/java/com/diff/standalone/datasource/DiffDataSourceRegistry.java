package com.diff.standalone.datasource;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diff 组件多数据源注册表。
 *
 * <p>
 * <b>设计动机：</b>
 * <ul>
 *     <li><b>null/"primary" 映射主数据源：</b>采用 convention over configuration，调用方不传 key 或传 "primary"
 *     时默认使用 Spring 主 DataSource，减少配置样板。</li>
 *     <li><b>实现 DisposableBean：</b>应用关闭时自动释放由本模块创建的外部数据源连接池（HikariCP），
 *     避免连接泄漏；主数据源由 Spring 容器管理，不在此关闭。</li>
 * </ul>
 * </p>
 *
 * <p>持有命名数据源并按 key 解析 {@link JdbcTemplate}。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class DiffDataSourceRegistry implements DisposableBean {

    public static final String PRIMARY_KEY = "primary";

    private final DataSource primaryDataSource;
    private final Map<String, DataSource> registry = new ConcurrentHashMap<>();
    private final Map<String, JdbcTemplate> jdbcTemplateCache = new ConcurrentHashMap<>();

    public DiffDataSourceRegistry(DataSource primaryDataSource) {
        if (primaryDataSource == null) {
            throw new IllegalArgumentException("primaryDataSource must not be null");
        }
        this.primaryDataSource = primaryDataSource;
    }

    /**
     * 应用关闭时释放所有外部数据源的连接池。
     * 主数据源由 Spring 容器管理，不在此关闭。
     */
    @Override
    public void destroy() {
        registry.values().forEach(ds -> {
            if (ds instanceof HikariDataSource hikari) {
                log.info("关闭 Diff 数据源连接池: {}", hikari.getPoolName());
                hikari.close();
            }
        });
        registry.clear();
        jdbcTemplateCache.clear();
    }

    /**
     * 注册命名数据源。{@code "primary"} 为保留 key，不可注册。
     */
    public void register(String key, DataSource dataSource) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("dataSource key must not be blank");
        }
        String normalizedKey = key.trim();
        if (PRIMARY_KEY.equals(normalizedKey)) {
            throw new IllegalArgumentException("'" + PRIMARY_KEY + "' is reserved and cannot be registered");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (registry.containsKey(normalizedKey)) {
            throw new IllegalStateException("dataSourceKey '" + normalizedKey + "' already registered, duplicate registration is not allowed");
        }
        registry.put(normalizedKey, dataSource);
    }

    /**
     * 按 key 解析 JdbcTemplate。
     *
     * @param dataSourceKey 数据源 key；null/"primary" 返回主数据源
     * @return 对应的 JdbcTemplate（懒创建并缓存）
     * @throws IllegalArgumentException key 未注册
     */
    public JdbcTemplate resolve(String dataSourceKey) {
        String effectiveKey = normalizeKey(dataSourceKey);
        return jdbcTemplateCache.computeIfAbsent(effectiveKey, k -> {
            DataSource ds = PRIMARY_KEY.equals(k) ? primaryDataSource : registry.get(k);
            if (ds == null) {
                throw new IllegalArgumentException("dataSourceKey '" + k + "' not registered");
            }
            return new JdbcTemplate(ds);
        });
    }

    /**
     * 检查指定 key 是否已注册（含 primary）。
     */
    public boolean contains(String dataSourceKey) {
        String effectiveKey = normalizeKey(dataSourceKey);
        return PRIMARY_KEY.equals(effectiveKey) || registry.containsKey(effectiveKey);
    }

    /**
     * 返回所有已注册的 key（含 primary）。
     */
    public Set<String> registeredKeys() {
        Set<String> keys = new HashSet<>(registry.keySet());
        keys.add(PRIMARY_KEY);
        return Collections.unmodifiableSet(keys);
    }

    /**
     * 获取指定 key 的原始 DataSource。用于手动事务管理等场景。
     *
     * @param dataSourceKey 数据源 key；null/"primary" 返回主数据源
     * @return 对应的 DataSource
     * @throws IllegalArgumentException key 未注册
     */
    public DataSource getDataSource(String dataSourceKey) {
        String effectiveKey = normalizeKey(dataSourceKey);
        if (PRIMARY_KEY.equals(effectiveKey)) {
            return primaryDataSource;
        }
        DataSource ds = registry.get(effectiveKey);
        if (ds == null) {
            throw new IllegalArgumentException("dataSourceKey '" + effectiveKey + "' not registered");
        }
        return ds;
    }

    private static String normalizeKey(String key) {
        return (key == null || key.isBlank()) ? PRIMARY_KEY : key.trim();
    }
}
