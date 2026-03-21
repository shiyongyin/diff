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

    private static final String SCRIPT_LOCATION_PATTERN = "META-INF/tenant-diff/schema-%s.sql";
    private static final Set<String> EMBEDDED_DATABASES = Set.of("h2", "hsql", "hsqldb", "derby");

    private final DataSource dataSource;
    private final String tablePrefix;
    private final boolean embeddedOnly;

    public TenantDiffSchemaInitializer(DataSource dataSource,
                                       TenantDiffProperties properties,
                                       boolean embeddedOnly) {
        this.dataSource = dataSource;
        this.tablePrefix = TenantDiffTableNames.normalizePrefix(properties.getSchema().getTablePrefix());
        this.embeddedOnly = embeddedOnly;
    }

    @Override
    public void afterPropertiesSet() {
        String productName = detectDatabaseProduct();
        if (embeddedOnly && !isEmbeddedDatabase(productName)) {
            log.info("Schema init-mode=embedded-only: 跳过非嵌入式数据库 '{}'", productName);
            return;
        }

        String dialect = resolveDialect(productName);
        String scriptPath = String.format(SCRIPT_LOCATION_PATTERN, dialect);
        ClassPathResource resource = new ClassPathResource(scriptPath);

        if (!resource.exists()) {
            log.warn("未找到建表脚本: {}，跳过 Schema 初始化", scriptPath);
            return;
        }

        log.info("执行 tenant-diff 建表脚本: {} (database={}, tablePrefix={})",
            scriptPath, productName, tablePrefix);

        boolean needPrefixReplace = !TenantDiffTableNames.BUILT_IN_PREFIX.equals(tablePrefix);

        try (Connection conn = dataSource.getConnection()) {
            if (needPrefixReplace) {
                String script = loadScriptText(resource);
                script = script.replace(TenantDiffTableNames.BUILT_IN_PREFIX, tablePrefix);
                ScriptUtils.executeSqlScript(conn, new org.springframework.core.io.ByteArrayResource(
                    script.getBytes(StandardCharsets.UTF_8)));
            } else {
                ScriptUtils.executeSqlScript(conn, resource);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("tenant-diff 建表脚本执行失败", e);
        }

        log.info("tenant-diff 建表脚本执行完成");
    }

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

    private String detectDatabaseProduct() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            return meta.getDatabaseProductName();
        } catch (SQLException e) {
            log.warn("无法检测数据库类型，回退到 mysql: {}", e.getMessage());
            return "MySQL";
        }
    }

    private static boolean isEmbeddedDatabase(String productName) {
        if (productName == null) {
            return false;
        }
        String lower = productName.toLowerCase(Locale.ROOT);
        return EMBEDDED_DATABASES.stream().anyMatch(lower::contains);
    }

    /**
     * 将数据库产品名映射为 DDL 脚本方言后缀。
     */
    private static String resolveDialect(String productName) {
        if (productName == null) {
            return "mysql";
        }
        String lower = productName.toLowerCase(Locale.ROOT);
        if (lower.contains("h2") || lower.contains("hsql") || lower.contains("derby")) {
            return "h2";
        }
        if (lower.contains("postgre")) {
            return "postgresql";
        }
        return "mysql";
    }
}
