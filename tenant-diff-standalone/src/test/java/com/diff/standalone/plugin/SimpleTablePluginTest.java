package com.diff.standalone.plugin;

import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.model.RecordData;
import com.diff.core.domain.schema.BusinessSchema;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.scope.ScopeFilter;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimpleTablePluginTest {

    private TestProductPlugin plugin;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript("simple-table-plugin-test.sql")
            .build();

        DiffDataSourceRegistry registry = new DiffDataSourceRegistry(ds);
        plugin = new TestProductPlugin(new ObjectMapper(), registry);
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void schema_singleTableWithCorrectStructure() {
        BusinessSchema schema = plugin.schema();

        assertEquals(1, schema.getTables().size());
        assertEquals(0, schema.getTables().get("test_product"));
        assertTrue(schema.getRelations().isEmpty());
    }

    @Test
    void schema_includesAdditionalIgnoreFields() {
        BusinessSchema schema = plugin.schema();

        Set<String> ignored = schema.getIgnoreFieldsByTable().get("test_product");
        assertNotNull(ignored);
        assertTrue(ignored.contains("internal_remark"));
    }

    @Test
    void listBusinessKeys_returnsAllKeysForTenant() {
        List<String> keys = plugin.listBusinessKeys(1L, null);

        assertEquals(3, keys.size());
        assertTrue(keys.containsAll(List.of("P-001", "P-002", "P-003")));
    }

    @Test
    void listBusinessKeys_respectsScopeFilter() {
        ScopeFilter filter = ScopeFilter.builder()
            .businessKeys(List.of("P-001"))
            .build();

        List<String> keys = plugin.listBusinessKeys(1L, filter);

        assertEquals(1, keys.size());
        assertEquals("P-001", keys.get(0));
    }

    @Test
    void listBusinessKeys_respectsLoadOptions() {
        List<String> keys = plugin.listBusinessKeys(1L, null,
            LoadOptions.builder().build());

        assertEquals(3, keys.size());
    }

    @Test
    void loadBusiness_returnsCorrectBusinessData() {
        BusinessData data = plugin.loadBusiness(1L, "P-001", null);

        assertNotNull(data);
        assertEquals("TEST_PRODUCT", data.getBusinessType());
        assertEquals("test_product", data.getBusinessTable());
        assertEquals("P-001", data.getBusinessKey());
        assertEquals(1L, data.getTenantId());
        assertEquals(1, data.getTables().size());
        assertEquals("test_product", data.getTables().get(0).getTableName());
        assertEquals(0, data.getTables().get(0).getDependencyLevel());
    }

    @Test
    void loadBusiness_recordHasCorrectFields() {
        BusinessData data = plugin.loadBusiness(1L, "P-001", null);

        List<RecordData> records = data.getTables().get(0).getRecords();
        assertEquals(1, records.size());

        RecordData record = records.get(0);
        assertEquals("P-001", record.getBusinessKey());
        assertNotNull(record.getFields());
        assertEquals("P-001", record.getFields().get("product_code"));
    }

    @Test
    void loadBusiness_setsBusinessNameFromColumn() {
        BusinessData data = plugin.loadBusiness(1L, "P-001", null);

        assertEquals("Standard A", data.getBusinessName());
    }

    @Test
    void loadBusiness_nonExistentKeyReturnsEmptyRecords() {
        BusinessData data = plugin.loadBusiness(1L, "NONEXISTENT", null);

        assertNotNull(data);
        assertEquals("NONEXISTENT", data.getBusinessKey());
        assertTrue(data.getTables().get(0).getRecords().isEmpty());
    }

    @Test
    void loadBusiness_respectsTenantIsolation() {
        List<String> tenant1Keys = plugin.listBusinessKeys(1L, null);
        List<String> tenant2Keys = plugin.listBusinessKeys(2L, null);

        assertEquals(3, tenant1Keys.size());
        assertEquals(2, tenant2Keys.size());
    }

    @Test
    void buildRecordBusinessKey_extractsCorrectly() {
        String key = plugin.buildRecordBusinessKey("test_product",
            Map.of("product_code", "P-001", "product_name", "Test"));

        assertEquals("P-001", key);
    }

    @Test
    void buildRecordBusinessKey_returnsNullForNullInput() {
        assertNull(plugin.buildRecordBusinessKey("test_product", null));
    }

    // ======== Test Plugin Implementation ========

    static class TestProductPlugin extends SimpleTablePlugin {
        TestProductPlugin(ObjectMapper om, DiffDataSourceRegistry ds) {
            super(om, ds);
        }

        @Override
        public String businessType() { return "TEST_PRODUCT"; }

        @Override
        protected String tableName() { return "test_product"; }

        @Override
        protected String businessKeyColumn() { return "product_code"; }

        @Override
        protected String businessNameColumn() { return "product_name"; }

        @Override
        protected Set<String> additionalIgnoreFields() {
            return Set.of("internal_remark");
        }
    }
}
