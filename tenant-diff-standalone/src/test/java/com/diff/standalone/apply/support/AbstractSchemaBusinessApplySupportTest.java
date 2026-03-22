package com.diff.standalone.apply.support;

import com.diff.core.apply.IdMapping;
import com.diff.core.domain.model.DerivedFieldNames;
import com.diff.core.domain.schema.BusinessSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AbstractSchemaBusinessApplySupportTest {

    @Test
    void transformForInsert_fallsBackToLocateTargetId_whenParentNotInsertedInThisApply() {
        TestApplySupport support = new TestApplySupport();

        Map<String, Object> fields = new HashMap<>();
        fields.put("name", "child");
        fields.put("parent_id", 100L);
        fields.put(DerivedFieldNames.PARENT_BUSINESS_KEY, "P-001");

        Map<String, Object> transformed = support.transformForInsert(
            "child_table",
            "C-001",
            fields,
            2L,
            new IdMapping()
        );

        assertEquals(2L, transformed.get("tenantsid"));
        assertEquals(9001L, transformed.get("parent_id"));
        assertFalse(transformed.containsKey(DerivedFieldNames.PARENT_BUSINESS_KEY));
    }

    @Test
    void transformForInsert_prefersIdMapping_beforeLocateTargetId() {
        TestApplySupport support = new TestApplySupport();
        IdMapping idMapping = new IdMapping();
        idMapping.put("parent_table", "P-001", 7001L);

        Map<String, Object> fields = new HashMap<>();
        fields.put("parent_id", 100L);
        fields.put(DerivedFieldNames.PARENT_BUSINESS_KEY, "P-001");

        Map<String, Object> transformed = support.transformForInsert(
            "child_table",
            "C-001",
            fields,
            2L,
            idMapping
        );

        assertEquals(7001L, transformed.get("parent_id"));
    }

    private static final class TestApplySupport extends AbstractSchemaBusinessApplySupport {
        private TestApplySupport() {
            super(new ObjectMapper(), BusinessSchema.builder()
                .relations(java.util.List.of(
                    BusinessSchema.TableRelation.builder()
                        .childTable("child_table")
                        .parentTable("parent_table")
                        .fkColumn("parent_id")
                        .build()
                ))
                .build());
        }

        @Override
        public String businessType() {
            return "TEST";
        }

        @Override
        public Long locateTargetId(String tableName, String recordBusinessKey, Long targetTenantId) {
            if ("parent_table".equals(tableName) && "P-001".equals(recordBusinessKey) && Long.valueOf(2L).equals(targetTenantId)) {
                return 9001L;
            }
            return null;
        }
    }
}
