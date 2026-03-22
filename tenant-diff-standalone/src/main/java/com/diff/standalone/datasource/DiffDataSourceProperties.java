package com.diff.standalone.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diff 多数据源配置属性。
 *
 * <p>
 * 配置示例：
 * <pre>
 * tenant-diff:
 *   datasources:
 *     erp-prod:
 *       url: jdbc:mysql://...
 *       username: xxx
 *       password: xxx
 *       read-only: true
 * </pre>
 * </p>
 *
 * <p>{@code "primary"} 为保留 key，自动绑定 Spring 主 DataSource，无需手动配置。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@ConfigurationProperties(prefix = "tenant-diff")
public class DiffDataSourceProperties {

    /** 外部命名 Diff 数据源配置；key 与 {@code DiffDataSourceRegistry} 注册时保持一致。 */
    private Map<String, DiffDataSourceConfig> datasources = new LinkedHashMap<>();

    /**
     * 单个命名 Diff 数据源的配置项。
     *
     * <p>由 {@link DiffDataSourceAutoConfiguration} 读取，用于初始化额外的 Hikari 连接池并加入
     * {@link DiffDataSourceRegistry}，确保每个额外数据源都具备连接地址/凭据和 fail-fast 参数。</p>
     *
     * @since 2026-01-20
     */
    @Data
    public static class DiffDataSourceConfig {
        /** 目标数据源的 JDBC URL，用于 Hikari 初始化。 */
        private String url;
        /** 连接用户名（敏感信息），用于建立连接。 */
        private String username;
        /** 连接密码，用于建立连接。 */
        private String password;
        /** 驱动类名，默认使用 MySQL 官方驱动。 */
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        /** 最大连接数。Diff 对比为低频操作，默认 3 足够。 */
        private int maximumPoolSize = 3;
        /** 最小空闲连接数。低频使用建议小值，减少资源浪费。 */
        private int minimumIdle = 1;
        /** 连接超时（ms）。 */
        private long connectionTimeoutMs = 30_000;
        /** 连接最大存活时间（ms）。跨网段时需低于防火墙/LB 的连接存活限制。 */
        private long maxLifetimeMs = 1_800_000;
        /** 空闲连接超时（ms）。 */
        private long idleTimeoutMs = 600_000;
        /**
         * 是否只读。source 端数据源建议设为 true，
         * 防止因 bug 导致意外写入外部数据库。
         */
        private boolean readOnly = false;
    }
}
