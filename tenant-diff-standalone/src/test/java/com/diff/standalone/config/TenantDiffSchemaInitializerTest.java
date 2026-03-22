package com.diff.standalone.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class TenantDiffSchemaInitializerTest {

    @Test
    void alwaysMode_createsAllTables() throws Exception {
        DataSource ds = newH2DataSource();
        TenantDiffProperties props = defaultProperties();

        TenantDiffSchemaInitializer initializer =
            new TenantDiffSchemaInitializer(ds, props, false);
        initializer.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertTableExists(jdbc, "xai_tenant_diff_session");
        assertTableExists(jdbc, "xai_tenant_diff_result");
        assertTableExists(jdbc, "xai_tenant_diff_apply_record");
        assertTableExists(jdbc, "xai_tenant_diff_apply_lease");
        assertTableExists(jdbc, "xai_tenant_diff_snapshot");
        assertTableExists(jdbc, "xai_tenant_diff_decision_record");
    }

    @Test
    void embeddedOnlyMode_createsTablesOnH2() throws Exception {
        DataSource ds = newH2DataSource();
        TenantDiffProperties props = defaultProperties();

        TenantDiffSchemaInitializer initializer =
            new TenantDiffSchemaInitializer(ds, props, true);
        initializer.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertTableExists(jdbc, "xai_tenant_diff_session");
    }

    @Test
    void customPrefix_createsPrefixedTables() throws Exception {
        DataSource ds = newH2DataSource();
        TenantDiffProperties props = defaultProperties();
        props.getSchema().setTablePrefix("custom_diff_");

        TenantDiffSchemaInitializer initializer =
            new TenantDiffSchemaInitializer(ds, props, false);
        initializer.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertTableExists(jdbc, "custom_diff_session");
        assertTableExists(jdbc, "custom_diff_result");
        assertTableExists(jdbc, "custom_diff_apply_lease");
        assertTableNotExists(jdbc, "xai_tenant_diff_session");
    }

    @Test
    void alwaysMode_idempotent_runsTwiceWithoutError() throws Exception {
        DataSource ds = newH2DataSource();
        TenantDiffProperties props = defaultProperties();

        TenantDiffSchemaInitializer initializer =
            new TenantDiffSchemaInitializer(ds, props, false);
        initializer.afterPropertiesSet();
        assertDoesNotThrow(initializer::afterPropertiesSet);
    }

    private static DataSource newH2DataSource() {
        return new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    }

    private static TenantDiffProperties defaultProperties() {
        TenantDiffProperties props = new TenantDiffProperties();
        props.setEnabled(true);
        return props;
    }

    private static void assertTableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = ?",
            Integer.class, tableName.toUpperCase());
        assertNotNull(count);
        assertTrue(count > 0, "Table " + tableName + " should exist");
    }

    private static void assertTableNotExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = ?",
            Integer.class, tableName.toUpperCase());
        assertNotNull(count);
        assertEquals(0, count, "Table " + tableName + " should not exist");
    }
}
