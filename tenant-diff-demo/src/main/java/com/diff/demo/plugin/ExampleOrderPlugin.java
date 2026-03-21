package com.diff.demo.plugin;

import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.model.RecordData;
import com.diff.core.domain.model.TableData;
import com.diff.core.domain.schema.BusinessSchema;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.scope.ScopeFilter;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.plugin.AbstractStandaloneBusinessPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 示例订单插件 — 演示多表 + 外键依赖的 Plugin 实现。
 *
 * <p>
 * 业务结构：
 * <ul>
 *     <li>{@code example_order}（主表，dependency_level=0）— 订单主信息</li>
 *     <li>{@code example_order_item}（子表，dependency_level=1）— 订单明细，通过 {@code order_id} 引用主表</li>
 * </ul>
 * </p>
 *
 * <p>
 * Apply 时子表的 {@code order_id} 会通过 IdMapping 自动替换为新生成的主表 ID。
 * </p>
 */
public class ExampleOrderPlugin extends AbstractStandaloneBusinessPlugin {

    private static final String BUSINESS_TYPE = "EXAMPLE_ORDER";
    private static final String TABLE_ORDER = "example_order";
    private static final String TABLE_ORDER_ITEM = "example_order_item";
    private static final String TABLE_ORDER_ITEM_DETAIL = "example_order_item_detail";
    private static final String ORDER_CODE = "order_code";
    private static final String ITEM_CODE = "item_code";
    private static final String DETAIL_CODE = "detail_code";
    private static final String FK_ORDER_ID = "order_id";
    private static final String FK_ORDER_ITEM_ID = "order_item_id";

    public ExampleOrderPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
        super(objectMapper, dataSourceRegistry);
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public BusinessSchema schema() {
        return BusinessSchema.builder()
            .tables(Map.of(
                TABLE_ORDER, 0,
                TABLE_ORDER_ITEM, 1,
                TABLE_ORDER_ITEM_DETAIL, 2
            ))
            .relations(List.of(
                BusinessSchema.TableRelation.builder()
                    .childTable(TABLE_ORDER_ITEM)
                    .fkColumn(FK_ORDER_ID)
                    .parentTable(TABLE_ORDER)
                    .build(),
                BusinessSchema.TableRelation.builder()
                    .childTable(TABLE_ORDER_ITEM_DETAIL)
                    .fkColumn(FK_ORDER_ITEM_ID)
                    .parentTable(TABLE_ORDER_ITEM)
                    .build()
            ))
            .ignoreFieldsByTable(Map.of(
                TABLE_ORDER, Set.of("id", "tenantsid", "version", "data_modify_time"),
                TABLE_ORDER_ITEM, Set.of("id", "tenantsid", "version", "data_modify_time", FK_ORDER_ID),
                TABLE_ORDER_ITEM_DETAIL, Set.of("id", "tenantsid", "version", "data_modify_time", FK_ORDER_ITEM_ID)
            ))
            .showFieldsByTable(Map.of(
                TABLE_ORDER, List.of("order_code", "order_name", "status", "total_amount"),
                TABLE_ORDER_ITEM, List.of("item_code", "product_name", "quantity", "unit_price"),
                TABLE_ORDER_ITEM_DETAIL, List.of("detail_code", "detail_name", "detail_value")
            ))
            .build();
    }

    @Override
    public List<String> listBusinessKeys(Long tenantId, ScopeFilter filter) {
        if (filter != null && filter.getBusinessKeys() != null && !filter.getBusinessKeys().isEmpty()) {
            return filter.getBusinessKeys();
        }
        JdbcTemplate jdbc = resolveJdbcTemplate(null);
        return jdbc.queryForList(
            "SELECT order_code FROM example_order WHERE tenantsid = ?",
            String.class, tenantId
        );
    }

    @Override
    public BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options) {
        JdbcTemplate jdbc = resolveJdbcTemplate(options);

        // 加载主表记录
        List<Map<String, Object>> orderRows = jdbc.queryForList(
            "SELECT * FROM example_order WHERE tenantsid = ? AND order_code = ?",
            tenantId, businessKey
        );

        if (orderRows.isEmpty()) {
            return BusinessData.builder()
                .businessType(BUSINESS_TYPE)
                .businessTable(TABLE_ORDER)
                .businessKey(businessKey)
                .businessName(businessKey)
                .tenantId(tenantId)
                .tables(List.of(
                    emptyTable(TABLE_ORDER, 0),
                    emptyTable(TABLE_ORDER_ITEM, 1),
                    emptyTable(TABLE_ORDER_ITEM_DETAIL, 2)
                ))
                .build();
        }

        // 构建主表 RecordData
        List<RecordData> orderRecords = new ArrayList<>();
        Map<Long, String> orderIdToKeyMap = new HashMap<>();
        String businessName = businessKey;

        for (Map<String, Object> row : orderRows) {
            Map<String, Object> fields = normalizeRecordFields(row);
            String recordKey = asString(fields.get(ORDER_CODE));
            String name = asString(fields.get("order_name"));
            if (name != null) {
                businessName = name;
            }

            Long orderId = parseLong(fields.get("id"));
            if (orderId != null && recordKey != null) {
                orderIdToKeyMap.put(orderId, recordKey);
            }

            // 为主表记录附加 main_business_key
            attachMainBusinessKey(List.of(fields), businessKey);

            orderRecords.add(buildRecordData(recordKey, fields, true, name));
        }

        // 加载子表记录：通过主表 ID 查询关联的子表行
        List<Long> orderIds = new ArrayList<>(orderIdToKeyMap.keySet());
        List<RecordData> itemRecords = new ArrayList<>();
        List<Map<String, Object>> normalizedItems = new ArrayList<>();

        if (!orderIds.isEmpty()) {
            String placeholders = buildInPlaceholders(orderIds.size());
            Object[] params = new Object[orderIds.size() + 1];
            params[0] = tenantId;
            for (int i = 0; i < orderIds.size(); i++) {
                params[i + 1] = orderIds.get(i);
            }

            List<Map<String, Object>> itemRows = jdbc.queryForList(
                "SELECT * FROM example_order_item WHERE tenantsid = ? AND order_id IN (" + placeholders + ")",
                params
            );

            // 规范化子表记录
            for (Map<String, Object> row : itemRows) {
                normalizedItems.add(normalizeRecordFields(row));
            }

            // 附加 main_business_key 和 parent_business_key
            attachMainBusinessKey(normalizedItems, businessKey);
            attachParentBusinessKey(normalizedItems, orderIdToKeyMap, FK_ORDER_ID);

            for (Map<String, Object> fields : normalizedItems) {
                String recordKey = asString(fields.get(ITEM_CODE));
                String name = asString(fields.get("product_name"));
                itemRecords.add(buildRecordData(recordKey, fields, true, name));
            }
        }

        // 加载第3层子表记录：通过 order_item ID 查询关联的明细行
        List<Long> itemIds = new ArrayList<>();
        Map<Long, String> itemIdToKeyMap = new HashMap<>();
        for (Map<String, Object> fields : normalizedItems) {
            Long itemId = parseLong(fields.get("id"));
            String itemKey = asString(fields.get(ITEM_CODE));
            if (itemId != null && itemKey != null) {
                itemIds.add(itemId);
                itemIdToKeyMap.put(itemId, itemKey);
            }
        }

        List<RecordData> detailRecords = new ArrayList<>();
        if (!itemIds.isEmpty()) {
            String detailPlaceholders = buildInPlaceholders(itemIds.size());
            Object[] detailParams = new Object[itemIds.size() + 1];
            detailParams[0] = tenantId;
            for (int i = 0; i < itemIds.size(); i++) {
                detailParams[i + 1] = itemIds.get(i);
            }

            List<Map<String, Object>> detailRows = jdbc.queryForList(
                "SELECT * FROM example_order_item_detail WHERE tenantsid = ? AND order_item_id IN (" + detailPlaceholders + ")",
                detailParams
            );

            List<Map<String, Object>> normalizedDetails = new ArrayList<>();
            for (Map<String, Object> row : detailRows) {
                normalizedDetails.add(normalizeRecordFields(row));
            }

            attachMainBusinessKey(normalizedDetails, businessKey);
            attachParentBusinessKey(normalizedDetails, itemIdToKeyMap, FK_ORDER_ITEM_ID);

            for (Map<String, Object> fields : normalizedDetails) {
                String recordKey = asString(fields.get(DETAIL_CODE));
                String name = asString(fields.get("detail_name"));
                detailRecords.add(buildRecordData(recordKey, fields, true, name));
            }
        }

        TableData orderTable = TableData.builder()
            .tableName(TABLE_ORDER)
            .dependencyLevel(0)
            .records(orderRecords)
            .build();

        TableData itemTable = TableData.builder()
            .tableName(TABLE_ORDER_ITEM)
            .dependencyLevel(1)
            .records(itemRecords)
            .build();

        TableData detailTable = TableData.builder()
            .tableName(TABLE_ORDER_ITEM_DETAIL)
            .dependencyLevel(2)
            .records(detailRecords)
            .build();

        return BusinessData.builder()
            .businessType(BUSINESS_TYPE)
            .businessTable(TABLE_ORDER)
            .businessKey(businessKey)
            .businessName(businessName)
            .tenantId(tenantId)
            .tables(List.of(orderTable, itemTable, detailTable))
            .build();
    }

    @Override
    public String buildRecordBusinessKey(String tableName, Map<String, Object> recordData) {
        if (recordData == null) {
            return null;
        }
        if (TABLE_ORDER.equals(tableName)) {
            return asString(recordData.get(ORDER_CODE));
        }
        if (TABLE_ORDER_ITEM.equals(tableName)) {
            return asString(recordData.get(ITEM_CODE));
        }
        if (TABLE_ORDER_ITEM_DETAIL.equals(tableName)) {
            return asString(recordData.get(DETAIL_CODE));
        }
        return null;
    }

}
