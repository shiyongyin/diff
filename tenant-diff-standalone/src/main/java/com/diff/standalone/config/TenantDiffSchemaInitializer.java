package com.diff.standalone.config;

import com.diff.standalone.persistence.TenantDiffTableNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * 框架表 DDL 初始化器——在应用启动时自动执行建表脚本。
 *
 * <p>
 * 根据数据库方言加载 {@code META-INF/tenant-diff/schema-{dialect}.sql}，
 * 并将表名前缀替换为用户配置的值后执行。
 * </p>
 *
 * <p>
 * 支持两种运行模式：
 * <ul>
 *     <li>{@code embeddedOnly=false}（对应 init-mode=always）：对所有数据库执行</li>
 *     <li>{@code embeddedOnly=true}（对应 init-mode=embedded-only）：仅对嵌入式数据库执行</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 0.0.1
 * @see TenantDiffSchemaInitConfiguration
 */
@Slf4j
public class TenantDiffSchemaInitializer implements InitializingBean {
    /** 脚本位置模式。 */
    private static final String SCRIPT_LOCATION_PATTERN = "META-INF/tenant-diff/schema-%s.sql";
    /** 嵌入式数据库集合。 */
    private static final Set<String> EMBEDDED_DATABASES = Set.of("h2", "hsql", "hsqldb", "derby");

    /** 数据源。 */
    private final DataSource dataSource;
    /** 表前缀。 */
    private final String tablePrefix;
    /** 是否仅对嵌入式数据库初始化。 */
    private final boolean embeddedOnly;

    public TenantDiffSchemaInitializer(DataSource dataSource,
                                       TenantDiffProperties properties,
                                       boolean embeddedOnly) {
        this.dataSource = dataSource;
        this.tablePrefix = TenantDiffTableNames.normalizePrefix(properties.getSchema().getTablePrefix());
        this.embeddedOnly = embeddedOnly;
    }

    /**
     * 初始化 Schema。
     */
    @Override
    public void afterPropertiesSet() {
        String productName = detectDatabaseProduct();
        if (embeddedOnly && !isEmbeddedDatabase(productName)) {
            log.info("Schema init-mode=embedded-only: 跳过非嵌入式数据库 '{}'", productName);
            return;
        }

        // 解析数据库方言
        String dialect = resolveDialect(productName);
        // 构建脚本路径
        String scriptPath = String.format(SCRIPT_LOCATION_PATTERN, dialect);
        // 加载脚本资源
        ClassPathResource resource = new ClassPathResource(scriptPath);
        // 如果脚本资源不存在 则跳过初始化
        if (!resource.exists()) {
            log.warn("未找到建表脚本: {}，跳过 Schema 初始化", scriptPath);
            return;
        }
        // 记录日志
        log.info("执行 tenant-diff 建表脚本: {} (database={}, tablePrefix={})",
            scriptPath, productName, tablePrefix);
        // 是否需要替换表前缀
        boolean needPrefixReplace = !TenantDiffTableNames.BUILT_IN_PREFIX.equals(tablePrefix);
        // 获取数据库连接
        try (Connection conn = dataSource.getConnection()) {
            // 如果需要替换表前缀 则加载脚本并替换表前缀
            if (needPrefixReplace) {
                // 加载脚本
                String script = loadScriptText(resource);
                // 替换表前缀
                script = script.replace(TenantDiffTableNames.BUILT_IN_PREFIX, tablePrefix);
                // 执行 SQL 脚本
                ScriptUtils.executeSqlScript(conn, new org.springframework.core.io.ByteArrayResource(
                    script.getBytes(StandardCharsets.UTF_8)));
            } else {
                // 执行 SQL 脚本
                ScriptUtils.executeSqlScript(conn, resource);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("tenant-diff 建表脚本执行失败", e);
        }

        log.info("tenant-diff 建表脚本执行完成");
    }

    /**
     * 加载脚本文本。
     *
     * @param resource 脚本资源
     * @return 脚本文本
     */
    private static String loadScriptText(ClassPathResource resource) {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取建表脚本: " + resource.getPath(), e);
        }
    }

    /**
     * 检测数据库产品。
     *
     * @return 数据库产品
     */
    private String detectDatabaseProduct() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            return meta.getDatabaseProductName();
        } catch (SQLException e) {
            log.warn("无法检测数据库类型，回退到 mysql: {}", e.getMessage());
            return "MySQL";
        }
    }

    /**
     * 判断是否为嵌入式数据库。
     *
     * @param productName 数据库产品名
     * @return 是否为嵌入式数据库
     */
    private static boolean isEmbeddedDatabase(String productName) {
        if (productName == null) {
            return false;
        }
        // 转换为小写
        String lower = productName.toLowerCase(Locale.ROOT);
        return EMBEDDED_DATABASES.stream().anyMatch(lower::contains);
    }

    /**
     * 将数据库产品名映射为 DDL 脚本方言后缀。
     */
    private static String resolveDialect(String productName) {
        // 如果产品名为空 则返回 mysql
        if (productName == null) {
            return "mysql";
        }
        String lower = productName.toLowerCase(Locale.ROOT);
        // 判断是否为嵌入式数据库
        if (lower.contains("h2") || lower.contains("hsql") || lower.contains("derby")) {
            return "h2";
        }
        // 判断是否为 PostgreSQL 数据库
        if (lower.contains("postgre")) {
            return "postgresql";
        }
        // 返回 MySQL 数据库
        return "mysql";
    }
}
