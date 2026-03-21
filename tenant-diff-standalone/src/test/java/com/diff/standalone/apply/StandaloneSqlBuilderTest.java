package com.diff.standalone.apply;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneSqlBuilderTest {

    @Test
    void insertSql_correctColumnsAndParams() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", "ProductA");
        fields.put("price", 100);
        fields.put("id", 999L);         // should be filtered
        fields.put("tenantsid", 1L);     // should be filtered

        StandaloneSqlBuilder.SqlAndArgs result = StandaloneSqlBuilder.buildInsert("example_product", 42L, fields);

        assertNotNull(result);
        assertTrue(result.sql().startsWith("INSERT INTO example_product"));
        assertTrue(result.sql().contains("tenantsid"));
        // id and tenantsid from fields should be excluded; tenantsid is added explicitly
        assertFalse(result.sql().contains(", id,"));
        // first arg is tenantsid
        assertEquals(42L, result.args()[0]);
        // columns sorted alphabetically: name, price
        assertTrue(result.sql().contains("name, price"));
    }

    @Test
    void updateSql_excludesSystemFields_whereDoubleCondition() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", "Updated");
        fields.put("id", 1L);
        fields.put("tenantsid", 99L);

        StandaloneSqlBuilder.SqlAndArgs result = StandaloneSqlBuilder.buildUpdateById("example_product", 42L, 10L, fields);

        assertNotNull(result);
        assertTrue(result.sql().startsWith("UPDATE example_product SET"));
        assertTrue(result.sql().contains("name = ?"));
        assertTrue(result.sql().contains("WHERE tenantsid = ? AND id = ?"));
        // args: [Updated, 42, 10]
        assertEquals("Updated", result.args()[0]);
        assertEquals(42L, result.args()[1]);
        assertEquals(10L, result.args()[2]);
    }

    @Test
    void deleteSql_whereDoubleCondition() {
        StandaloneSqlBuilder.SqlAndArgs result = StandaloneSqlBuilder.buildDeleteById("example_product", 42L, 10L);

        assertNotNull(result);
        assertEquals("DELETE FROM example_product WHERE tenantsid = ? AND id = ?", result.sql());
        assertEquals(42L, result.args()[0]);
        assertEquals(10L, result.args()[1]);
    }

    @Test
    void updateWithNoMutableFields_returnsNull() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", 1L);
        fields.put("tenantsid", 99L);

        StandaloneSqlBuilder.SqlAndArgs result = StandaloneSqlBuilder.buildUpdateById("t", 42L, 10L, fields);

        assertNull(result);
    }

    @Test
    void insertColumns_sortedAlphabetically() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("zebra", "z");
        fields.put("apple", "a");
        fields.put("mango", "m");

        StandaloneSqlBuilder.SqlAndArgs result = StandaloneSqlBuilder.buildInsert("t", 1L, fields);

        String sql = result.sql();
        int appleIdx = sql.indexOf("apple");
        int mangoIdx = sql.indexOf("mango");
        int zebraIdx = sql.indexOf("zebra");
        assertTrue(appleIdx < mangoIdx);
        assertTrue(mangoIdx < zebraIdx);
    }
}
