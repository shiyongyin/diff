package com.diff.demo;

import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发布门禁端到端测试：在真实 MySQL 上验证租户差异比对→预览→决策→同步→回滚的完整生命周期。
 *
 * <h3>测试目标</h3>
 * <p>本测试类覆盖"发布门禁"场景下最核心的功能路径，确保每个环节在真实数据库环境下行为正确。
 * 测试涵盖单表（EXAMPLE_PRODUCT）和多表父子孙（EXAMPLE_ORDER → ORDER_ITEM → ORDER_ITEM_DETAIL）
 * 两种业务模型。</p>
 *
 * <h3>覆盖场景</h3>
 * <ul>
 *   <li><b>预览只读性</b> — 预览 API 不应产生任何数据库写入</li>
 *   <li><b>决策 SKIP</b> — 对指定记录标记跳过后，同步执行应忽略该记录</li>
 *   <li><b>比对查询链</b> — session/get → listBusiness → getBusinessDetail 完整查询链</li>
 *   <li><b>业务明细 404</b> — 查询不存在的 businessKey 返回 DIFF_E_1002</li>
 *   <li><b>视图模式</b> — FULL/FILTERED/COMPACT 三种视图对 NOOP 和字段裁剪的行为差异</li>
 *   <li><b>畸形 JSON</b> — 请求体 JSON 格式错误返回 DIFF_E_0002</li>
 *   <li><b>部分选择校验</b> — 篡改 token / 未知 actionId / 空 actionIds 三种拒绝场景</li>
 *   <li><b>子表 action 拒绝</b> — PARTIAL 模式下禁止直接选择子表级别的 action</li>
 *   <li><b>多表父子孙同步 + 全量回滚</b> — INSERT/UPDATE/DELETE 覆盖三层表结构，回滚验证数据完整还原</li>
 *   <li><b>部分选择执行 + 漂移感知回滚</b> — 仅同步部分 action → 手动篡改 → 回滚检测漂移 → acknowledgeDrift 强制回滚</li>
 *   <li><b>同步失败 → 事务回滚 + 审计记录</b> — 注入数据库 Trigger 制造失败，验证业务写入被回滚但审计记录保留 FAILED 状态</li>
 *   <li><b>外部目标数据源回滚拒绝</b> — 同步到外部数据源后，回滚应返回 DIFF_E_3001（不支持跨库回滚）</li>
 *   <li><b>快照缺失回滚拒绝</b> — 手动删除快照数据后，回滚应返回 DIFF_E_3003</li>
 *   <li><b>并发同步租约互斥</b> — 3 个线程同时对同一目标执行同步，仅 1 个成功、其余返回 DIFF_E_2008</li>
 * </ul>
 *
 * <h3>运行前提</h3>
 * <ul>
 *   <li>需要设置 {@code TENANT_DIFF_TEST_MYSQL_ENABLED=true}</li>
 *   <li>需要运行中的 MySQL 实例（由 {@code application-mysql-e2e.yml} 配置）</li>
 *   <li>初始化脚本 {@code schema-mysql-e2e.sql} 会自动建表并插入种子数据</li>
 *   <li>每个测试方法后通过 {@link DirtiesContext} 重建上下文，保证测试隔离</li>
 * </ul>
 *
 * @see MysqlAdversarialGuardrailE2ETest 对抗性护栏测试（时效性/行数限制/重复同步等）
 * @see MysqlRollbackConcurrencyE2ETest 回滚并发互斥测试
 * @see MysqlWarningDegradationE2ETest 告警降级测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("mysql-e2e")
@EnabledIfEnvironmentVariable(named = "TENANT_DIFF_TEST_MYSQL_ENABLED", matches = "(?i:true|1|yes)")
@ActiveProfiles({"test", "mysql-e2e"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MysqlReleaseGateE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DiffDataSourceRegistry dataSourceRegistry;

    private JdbcTemplate externalJdbc;

    /**
     * 初始化外部数据源的目标表及种子数据。
     *
     * <p>模拟"目标租户使用外部数据库"的场景：
     * 在 {@code ext} 数据源上重建 {@code example_product} 表，
     * 并插入 2 条产品记录（PROD-001 / PROD-002），供外部目标数据源相关测试使用。</p>
     */
    @BeforeEach
    void initExternalDataSource() {
        externalJdbc = dataSourceRegistry.resolve("ext");
        externalJdbc.execute("DROP TABLE IF EXISTS example_product");
        externalJdbc.execute("""
            CREATE TABLE example_product (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenantsid BIGINT,
                product_code VARCHAR(64),
                product_name VARCHAR(255),
                price DECIMAL(10,2),
                status VARCHAR(32) DEFAULT 'ACTIVE',
                version INT DEFAULT 0,
                data_modify_time DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        externalJdbc.update(
            "INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES (?, ?, ?, ?, ?)",
            2L, "PROD-001", "标准套餐A", new BigDecimal("99.00"), "ACTIVE");
        externalJdbc.update(
            "INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES (?, ?, ?, ?, ?)",
            2L, "PROD-002", "高级套餐B-改", new BigDecimal("249.00"), "ACTIVE");
    }

    /**
     * 验证预览只读性 + 决策 SKIP 过滤机制。
     *
     * <p><b>测什么：</b>预览 API 不应产生任何数据库写入；对 PROD-003 标记 SKIP 决策后，
     * 执行同步时应跳过该记录，仅同步 PROD-002 的 UPDATE。</p>
     *
     * <p><b>为什么：</b>发布门禁要求用户可以逐条审核差异，对不确定的记录标记跳过。
     * 若预览产生了副作用或 SKIP 决策未生效，会导致非预期的数据变更。</p>
     *
     * <p><b>如何测：</b></p>
     * <ol>
     *   <li>创建比对 session → 验证 apply_record / snapshot / 目标表均无数据</li>
     *   <li>调用 preview → 验证返回 action 列表非空，但数据库仍无写入</li>
     *   <li>对 PROD-003 保存 SKIP 决策 → 查询验证决策已持久化</li>
     *   <li>执行同步 → 验证 affectedRows=1（仅 PROD-002 的 UPDATE 生效）</li>
     *   <li>数据库验证：PROD-003 未被插入，PROD-002 价格已更新</li>
     * </ol>
     */
    @Test
    void preview_and_decision_api_should_not_write_and_should_filter_skipped_record() throws Exception {
        long sessionId = createProductSession(false);

        assertEquals(0, countApplyRecords());
        assertEquals(0, countSnapshots());
        assertEquals(0, countTargetProduct("PROD-003"));

        PreviewData preview = preview(sessionId);
        assertFalse(preview.actions.isEmpty());
        assertEquals(0, countApplyRecords());
        assertEquals(0, countSnapshots());
        assertEquals(0, countTargetProduct("PROD-003"));

        mockMvc.perform(post("/api/tenant-diff/decision/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "businessType": "EXAMPLE_PRODUCT",
                      "businessKey": "PROD-003",
                      "decisions": [
                        {
                          "tableName": "example_product",
                          "recordBusinessKey": "PROD-003",
                          "decision": "SKIP",
                          "decisionReason": "release-gate mysql e2e"
                        }
                      ]
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(get("/api/tenant-diff/decision/list")
                .param("sessionId", String.valueOf(sessionId))
                .param("businessType", "EXAMPLE_PRODUCT")
                .param("businessKey", "PROD-003"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].decision").value("SKIP"))
            .andExpect(jsonPath("$.data[0].recordBusinessKey").value("PROD-003"));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "maxAffectedRows": 10,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(1));

        assertEquals(0, countTargetProduct("PROD-003"));
        BigDecimal updatedPrice = jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
            BigDecimal.class
        );
        assertEquals(0, new BigDecimal("199.00").compareTo(updatedPrice));
    }

    /**
     * 验证比对查询链在真实 MySQL 上的完整分析流程。
     *
     * <p><b>测什么：</b>session/get → listBusiness → getBusinessDetail 三个查询接口
     * 在真实 MySQL 上的正确性和数据一致性。</p>
     *
     * <p><b>为什么：</b>前端依赖这条查询链逐层展开差异详情；
     * 任何环节返回错误数据都会导致用户误判。</p>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>查询不存在的 sessionId=0 → 期望 404 + DIFF_E_1001</li>
     *   <li>创建 session → get 返回 SUCCESS 状态 + totalBusinesses=3</li>
     *   <li>listBusiness 分页查询 → 返回 3 条业务记录</li>
     *   <li>getBusinessDetail 查看 PROD-002 → diffType=UPDATE</li>
     * </ol>
     */
    @Test
    void compare_query_chain_should_cover_analysis_flow_on_real_mysql() throws Exception {
        mockMvc.perform(get("/api/tenantDiff/standalone/session/get").param("sessionId", "0"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DIFF_E_1001"));

        long sessionId = createProductSession(false);

        mockMvc.perform(get("/api/tenantDiff/standalone/session/get")
                .param("sessionId", String.valueOf(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.statistics.totalBusinesses").value(3));

        mockMvc.perform(get("/api/tenantDiff/standalone/session/listBusiness")
                .param("sessionId", String.valueOf(sessionId))
                .param("pageNo", "1")
                .param("pageSize", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(3));

        mockMvc.perform(get("/api/tenantDiff/standalone/session/getBusinessDetail")
                .param("sessionId", String.valueOf(sessionId))
                .param("businessType", "EXAMPLE_PRODUCT")
                .param("businessKey", "PROD-002"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.businessKey").value("PROD-002"))
            .andExpect(jsonPath("$.data.tableDiffs[0].recordDiffs[0].diffType").value("UPDATE"));
    }

    /**
     * 验证查询不存在的 businessKey 时返回 404。
     *
     * <p><b>测什么：</b>getBusinessDetail 接口在 businessKey 不存在时应返回 404 + DIFF_E_1002。</p>
     *
     * <p><b>为什么：</b>前端需要区分"无差异"与"不存在"两种情况，
     * 接口必须用正确的 HTTP 状态码和错误码明确告知。</p>
     */
    @Test
    void business_detail_should_return_not_found_on_real_mysql() throws Exception {
        long sessionId = createProductSession(false);

        mockMvc.perform(get("/api/tenantDiff/standalone/session/getBusinessDetail")
                .param("sessionId", String.valueOf(sessionId))
                .param("businessType", "EXAMPLE_PRODUCT")
                .param("businessKey", "NOT-EXISTS"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_1002"));
    }

    /**
     * 验证 FULL / FILTERED / COMPACT 三种视图模式对差异详情的裁剪行为。
     *
     * <p><b>测什么：</b></p>
     * <ul>
     *   <li>FULL — 保留所有 recordDiff（包括 NOOP），保留 sourceFields/targetFields</li>
     *   <li>FILTERED — 过滤 NOOP 类型的 recordDiff，清零 noopCount 统计</li>
     *   <li>COMPACT — 仅投影 showFields（变更字段子集），裁剪掉 sourceFields/targetFields 原始数据</li>
     * </ul>
     *
     * <p><b>为什么：</b>前端列表页只需摘要信息（COMPACT），
     * 详情页需要完整字段（FULL），审核页需要过滤无变更记录（FILTERED）。
     * 三种模式的裁剪逻辑必须在真实 MySQL 上验证正确性。</p>
     *
     * <p><b>如何验证：</b>使用 EXAMPLE_PRODUCT 验证 FULL vs FILTERED（NOOP 过滤），
     * 使用 EXAMPLE_ORDER 验证 FULL vs COMPACT（字段裁剪）。</p>
     */
    @Test
    void business_detail_view_modes_should_filter_noop_and_strip_raw_fields_on_real_mysql() throws Exception {
        long productSessionId = createProductSession(false);
        JsonNode fullNoop = getBusinessDetail(productSessionId, "EXAMPLE_PRODUCT", "PROD-001", "FULL");
        JsonNode filteredNoop = getBusinessDetail(productSessionId, "EXAMPLE_PRODUCT", "PROD-001", "FILTERED");

        JsonNode fullProductTable = findTableDiff(fullNoop, "example_product");
        JsonNode filteredProductTable = findTableDiff(filteredNoop, "example_product");
        assertTrue(containsDiffType(fullProductTable, "NOOP"), "FULL 应保留 NOOP record");
        assertFalse(containsDiffType(filteredProductTable, "NOOP"), "FILTERED 应过滤 NOOP record");
        assertTrue(fullProductTable.path("recordDiffs").size() > filteredProductTable.path("recordDiffs").size(),
            "FILTERED 应少于 FULL 的 record 数");
        assertEquals(1, fullNoop.path("statistics").path("noopCount").asInt(), "FULL 应保留 NOOP 统计");
        assertEquals(0, filteredNoop.path("statistics").path("noopCount").asInt(), "FILTERED 应清零 NOOP 统计");

        long orderSessionId = createOrderSession();
        JsonNode full = getBusinessDetail(orderSessionId, "EXAMPLE_ORDER", "ORD-001", "FULL");
        JsonNode compact = getBusinessDetail(orderSessionId, "EXAMPLE_ORDER", "ORD-001", "COMPACT");

        JsonNode compactOrderTable = findTableDiff(compact, "example_order");
        JsonNode fullOrderTable = findTableDiff(full, "example_order");

        JsonNode compactOrderRecord = compactOrderTable.path("recordDiffs").get(0);
        JsonNode fullOrderRecord = fullOrderTable.path("recordDiffs").get(0);
        assertTrue(compactOrderRecord.path("showFields").isObject(), "COMPACT 应投影 showFields");
        assertTrue(compactOrderRecord.path("showFields").has("order_name"));
        assertTrue(compactOrderRecord.path("showFields").has("status"));
        assertTrue(compactOrderRecord.path("sourceFields").isMissingNode()
                || compactOrderRecord.path("sourceFields").isNull(), "COMPACT 应裁剪 sourceFields");
        assertTrue(compactOrderRecord.path("targetFields").isMissingNode()
                || compactOrderRecord.path("targetFields").isNull(), "COMPACT 应裁剪 targetFields");
        assertTrue(fullOrderRecord.path("sourceFields").isObject(), "FULL 应保留 sourceFields");
        assertTrue(fullOrderRecord.path("targetFields").isObject(), "FULL 应保留 targetFields");
    }

    /**
     * 验证畸形 JSON 请求体的错误处理。
     *
     * <p><b>测什么：</b>发送截断的 JSON（缺少右花括号）时，应返回 400 + DIFF_E_0002。</p>
     *
     * <p><b>为什么：</b>全局异常处理器必须正确拦截 JSON 解析异常，
     * 返回标准化错误响应而非框架默认的错误页面或堆栈信息。</p>
     */
    @Test
    void malformed_json_should_return_bad_request_on_real_mysql() throws Exception {
        mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceTenantId\":1,"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_0002"));
    }

    /**
     * 验证部分选择模式（PARTIAL）的三种拒绝场景。
     *
     * <p><b>测什么：</b></p>
     * <ul>
     *   <li>篡改 previewToken → 422 + DIFF_E_2012（token 签名不匹配）</li>
     *   <li>传入未知 actionId（"v1:UNKNOWN"） → 422 + DIFF_E_2011（action 不存在）</li>
     *   <li>空 selectedActionIds → 422 + DIFF_E_2010（未选择任何 action）</li>
     * </ul>
     *
     * <p><b>为什么：</b>PARTIAL 模式允许用户精选要同步的变更项。
     * 必须防御前端篡改 token、传入非法 ID、或提交空选择，否则可能导致非预期写入或空操作。</p>
     *
     * <p><b>如何验证：</b>分别构造三种非法请求，断言 HTTP 状态码和错误码。</p>
     */
    @Test
    void partial_selection_validation_should_cover_tampered_unknown_and_empty_cases() throws Exception {
        long sessionId = createProductSession(false);
        PreviewData preview = preview(sessionId);
        ActionPreview insertAction = preview.findByRecordBusinessKey("PROD-003");

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "selectionMode": "PARTIAL",
                        "selectedActionIds": ["%s"],
                        "previewToken": "%s_tampered"
                      }
                    }
                    """.formatted(sessionId, insertAction.actionId, preview.previewToken)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DIFF_E_2012"));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "selectionMode": "PARTIAL",
                        "selectedActionIds": ["v1:UNKNOWN"],
                        "previewToken": "%s"
                      }
                    }
                    """.formatted(sessionId, preview.previewToken)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DIFF_E_2011"));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "selectionMode": "PARTIAL",
                        "selectedActionIds": [],
                        "previewToken": "%s"
                      }
                    }
                    """.formatted(sessionId, preview.previewToken)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DIFF_E_2010"));
    }

    /**
     * 验证 PARTIAL 模式下禁止直接选择子表级别的 action。
     *
     * <p><b>测什么：</b>选择 dependencyLevel=1（子表）的 action 执行 PARTIAL 同步时，
     * 应返回 400 + DIFF_E_0001。</p>
     *
     * <p><b>为什么：</b>子表数据的一致性依赖父表。如果允许单独同步子表 action，
     * 可能导致外键悬挂或业务数据不完整。父子孙结构下只允许选择主表 action，
     * 子表/孙表 action 会自动跟随。</p>
     */
    @Test
    void partial_selection_should_reject_sub_table_actions_on_real_mysql() throws Exception {
        long sessionId = createOrderSession();
        PreviewData preview = previewOrder(sessionId, true);
        ActionPreview childAction = preview.findFirstByDependencyLevel(1);
        assertNotNull(childAction);

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": true,
                        "selectionMode": "PARTIAL",
                        "selectedActionIds": ["%s"],
                        "previewToken": "%s"
                      }
                    }
                    """.formatted(sessionId, childAction.actionId, preview.previewToken)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DIFF_E_0001"));
    }

    /**
     * 验证多表父→子→孙三层结构的 INSERT/UPDATE/DELETE 同步及全量回滚。
     *
     * <p><b>测什么：</b>EXAMPLE_ORDER 业务模型包含三层表结构
     * （example_order → example_order_item → example_order_item_detail），
     * 验证所有 diffType（INSERT/UPDATE/DELETE）在三层表上的同步和回滚正确性。</p>
     *
     * <p><b>为什么：</b>这是最复杂的业务场景——多层表的同步需要严格的依赖顺序
     * （先父后子插入/更新，先子后父删除），回滚则需要反序还原。
     * 此测试验证框架的依赖排序和回滚逆序逻辑在真实 MySQL 上的正确性。</p>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>预览验证 action 列表覆盖 UPDATE/INSERT/DELETE 和 dependencyLevel=2</li>
     *   <li>执行同步 → 验证 ORD-001 更新、ORD-002 插入（含子项和明细）、ORD-DEL 删除（级联子表）</li>
     *   <li>执行回滚 → 验证所有数据精确还原到同步前状态</li>
     * </ol>
     */
    @Test
    void multi_table_parent_child_grandchild_should_cover_insert_update_delete_and_full_rollback() throws Exception {
        long sessionId = createOrderSession();

        PreviewData preview = previewOrder(sessionId, true);
        assertTrue(preview.actions.stream().anyMatch(action -> "UPDATE".equals(action.diffType)));
        assertTrue(preview.actions.stream().anyMatch(action -> "INSERT".equals(action.diffType)));
        assertTrue(preview.actions.stream().anyMatch(action -> "DELETE".equals(action.diffType)));
        assertTrue(preview.actions.stream().anyMatch(action -> action.dependencyLevel == 2));

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": true,
                        "maxAffectedRows": 50,
                        "businessTypes": ["EXAMPLE_ORDER"],
                        "diffTypes": ["INSERT", "UPDATE", "DELETE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        long applyId = objectMapper.readTree(applyResponse).path("data").path("applyId").asLong();

        assertEquals("测试订单A", orderName("ORD-001"));
        assertEquals(2, itemCountForOrder("ORD-001"));

        assertEquals(1, orderCount("ORD-002"));
        assertEquals(1, itemCountForOrder("ORD-002"));
        assertEquals(2, detailCountForOrder("ORD-002"));

        assertEquals(0, orderCount("ORD-DEL"));
        assertEquals(0, itemCountByCode("ITEM-DEL"));
        assertEquals(0, detailCountByCode("DTL-DEL"));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "applyId": %d
                    }
                    """.formatted(applyId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.verification.success").value(true));

        assertEquals("测试订单A-旧", orderName("ORD-001"));
        assertEquals(1, itemCountForOrder("ORD-001"));

        assertEquals(0, orderCount("ORD-002"));
        assertEquals(0, itemCountByCode("ITEM-003"));
        assertEquals(0, detailCountByCode("DTL-001"));

        assertEquals(1, orderCount("ORD-DEL"));
        assertEquals(1, itemCountByCode("ITEM-DEL"));
        assertEquals(1, detailCountByCode("DTL-DEL"));
    }

    /**
     * 验证部分选择执行 + 数据漂移感知回滚机制。
     *
     * <p><b>测什么：</b></p>
     * <ol>
     *   <li>PARTIAL 模式仅选择 PROD-003 的 INSERT action 执行 → affectedRows=1</li>
     *   <li>未选择的 PROD-002 UPDATE 不应被影响（价格保持 249.00）</li>
     *   <li>同步后手动篡改 PROD-003 的 status → 模拟"数据漂移"</li>
     *   <li>回滚时 acknowledgeDrift=false → 409 + DIFF_E_3004（检测到漂移，拒绝回滚）</li>
     *   <li>回滚时 acknowledgeDrift=true → 强制回滚成功，driftDetected=true</li>
     * </ol>
     *
     * <p><b>为什么：</b>生产环境中，同步后目标数据可能被其他流程修改。
     * 框架必须检测漂移并阻止盲目回滚（防止覆盖新变更），
     * 同时提供 acknowledgeDrift 选项让用户在知情的前提下强制回滚。</p>
     */
    @Test
    void partial_execute_then_acknowledged_rollback_should_work_on_real_mysql() throws Exception {
        long sessionId = createProductSession(false);
        PreviewData preview = preview(sessionId);
        ActionPreview insertAction = preview.findByRecordBusinessKey("PROD-003");

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "selectionMode": "PARTIAL",
                        "selectedActionIds": ["%s"],
                        "previewToken": "%s"
                      }
                    }
                    """.formatted(sessionId, insertAction.actionId, preview.previewToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        long applyId = objectMapper.readTree(applyResponse).path("data").path("applyId").asLong();
        assertEquals(1, countTargetProduct("PROD-003"));

        BigDecimal untouchedPrice = jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
            BigDecimal.class
        );
        assertEquals(0, new BigDecimal("249.00").compareTo(untouchedPrice));

        jdbcTemplate.update(
            "UPDATE example_product SET status = ? WHERE tenantsid = 2 AND product_code = 'PROD-003'",
            "ARCHIVED"
        );

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "applyId": %d,
                      "acknowledgeDrift": false
                    }
                    """.formatted(applyId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_3004"));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "applyId": %d,
                      "acknowledgeDrift": true
                    }
                    """.formatted(applyId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.driftDetected").value(true))
            .andExpect(jsonPath("$.data.verification.success").value(true));

        assertEquals(0, countTargetProduct("PROD-003"));
    }

    /**
     * 验证同步执行失败时的事务回滚和审计记录保留。
     *
     * <p><b>测什么：</b>通过 MySQL Trigger 注入故障（拦截 PROD-003 的 INSERT），
     * 验证同步失败后：业务写入被事务回滚（PROD-003 未插入、PROD-002 价格未变），
     * 但审计记录保留且状态为 FAILED，快照不应生成。</p>
     *
     * <p><b>为什么：</b>同步失败时必须保证原子性——要么全部成功，要么全部回滚。
     * 同时审计记录必须保留（不能被事务回滚吞掉），以便运维排查失败原因。
     * 快照仅在成功后才应生成，否则回滚时会找到无效快照。</p>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>创建 Trigger 拦截 PROD-003 的 INSERT → 执行同步 → 500 + DIFF_E_0003</li>
     *   <li>删除 Trigger → 查数据库验证业务数据未变更</li>
     *   <li>验证 apply_record 表有 1 条记录且 status=FAILED</li>
     *   <li>验证 snapshot 表为空（失败不应生成快照）</li>
     * </ol>
     */
    @Test
    void apply_failure_should_keep_failed_audit_and_rollback_business_writes() throws Exception {
        long sessionId = createProductSession(false);

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_e2e_block_prod003_insert");
        jdbcTemplate.execute("""
            CREATE TRIGGER trg_e2e_block_prod003_insert
            BEFORE INSERT ON example_product
            FOR EACH ROW
            BEGIN
              IF NEW.tenantsid = 2 AND NEW.product_code = 'PROD-003' THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'release gate injected failure';
              END IF;
            END
            """);

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "maxAffectedRows": 10,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("DIFF_E_0003"));

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_e2e_block_prod003_insert");

        assertEquals(0, countTargetProduct("PROD-003"));
        BigDecimal originalPrice = jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
            BigDecimal.class
        );
        assertEquals(0, new BigDecimal("249.00").compareTo(originalPrice));
        assertEquals(1, countApplyRecords());
        assertEquals("FAILED", latestApplyStatus());
        assertEquals(0, countSnapshots());
    }

    /**
     * 验证回滚外部目标数据源时返回拒绝错误。
     *
     * <p><b>测什么：</b>对使用外部数据源（dataSourceKey=ext）执行的同步发起回滚，
     * 应返回 422 + DIFF_E_3001。</p>
     *
     * <p><b>为什么：</b>外部数据源可能不在框架的事务管控范围内，
     * 不支持自动回滚。框架必须在回滚前校验目标数据源类型，
     * 对外部数据源明确拒绝并引导用户手动处理。</p>
     *
     * <p><b>如何验证：</b>创建 session 时指定 targetLoadOptions.dataSourceKey=ext，
     * 执行同步成功后发起回滚 → 断言 422 + DIFF_E_3001，
     * 且外部数据源中 PROD-003 仍然存在（同步未被撤销）。</p>
     */
    @Test
    void rollback_should_reject_external_target_on_real_mysql() throws Exception {
        long sessionId = createProductSession(true);

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "maxAffectedRows": 10,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        long applyId = objectMapper.readTree(applyResponse).path("data").path("applyId").asLong();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "applyId": %d
                    }
                    """.formatted(applyId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_3001"));

        assertEquals(1, externalCountTargetProduct("PROD-003"));
    }

    /**
     * 验证快照数据缺失时回滚被拒绝。
     *
     * <p><b>测什么：</b>同步成功后手动删除 snapshot 表记录，
     * 发起回滚时应返回 422 + DIFF_E_3003。</p>
     *
     * <p><b>为什么：</b>回滚依赖快照数据还原目标表。如果快照被人工删除或损坏，
     * 框架无法安全回滚，必须明确拒绝并提示原因。</p>
     */
    @Test
    void rollback_should_reject_missing_snapshot_on_real_mysql() throws Exception {
        long sessionId = createProductSession(false);

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        long applyId = objectMapper.readTree(applyResponse).path("data").path("applyId").asLong();
        jdbcTemplate.update("DELETE FROM xai_tenant_diff_snapshot WHERE apply_id = ?", applyId);

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "applyId": %d
                    }
                    """.formatted(applyId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_3003"));
    }

    /**
     * 验证并发同步对同一目标时的租约互斥机制。
     *
     * <p><b>测什么：</b>3 个线程同时对同一目标租户执行同步，
     * 期望恰好 1 个成功、其余 2 个返回 DIFF_E_2008（目标忙）。</p>
     *
     * <p><b>为什么：</b>多人同时操作同一租户的数据同步是高风险场景。
     * 框架必须通过分布式租约保证同一时间只有一个同步任务写入目标租户，
     * 否则会出现数据覆盖或快照冲突。</p>
     *
     * <p><b>如何验证：</b>使用 {@link CountDownLatch} 控制 3 个线程同时起跑，
     * 统计 success 和 DIFF_E_2008 的计数，断言 success=1 / busy=2。</p>
     */
    @Test
    void concurrent_apply_on_same_target_should_return_target_busy() throws Exception {
        long sessionId1 = createProductSession(false);
        long sessionId2 = createProductSession(false);
        long sessionId3 = createProductSession(false);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger busyCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runConcurrentExecute(sessionId1, ready, start, successCount, busyCount)));
        futures.add(executor.submit(() -> runConcurrentExecute(sessionId2, ready, start, successCount, busyCount)));
        futures.add(executor.submit(() -> runConcurrentExecute(sessionId3, ready, start, successCount, busyCount)));

        ready.await();
        start.countDown();

        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(2, busyCount.get());
    }

    /** 并发同步执行体：等待栅栏释放后发起同步，按结果分类计数。 */
    private void runConcurrentExecute(long sessionId,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      AtomicInteger successCount,
                                      AtomicInteger busyCount) {
        ready.countDown();
        try {
            start.await();
            String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "sessionId": %d,
                          "direction": "A_TO_B",
                          "options": {
                            "allowDelete": false,
                            "maxAffectedRows": 10,
                            "businessTypes": ["EXAMPLE_PRODUCT"],
                            "diffTypes": ["INSERT", "UPDATE"]
                          }
                        }
                        """.formatted(sessionId)))
                .andReturn()
                .getResponse()
                .getContentAsString();
            JsonNode root = objectMapper.readTree(response);
            if (root.path("success").asBoolean(false)) {
                successCount.incrementAndGet();
            } else if ("DIFF_E_2008".equals(root.path("code").asText())) {
                busyCount.incrementAndGet();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 创建 EXAMPLE_PRODUCT 比对 session；可选是否使用外部数据源作为同步目标。 */
    private long createProductSession(boolean useExternalTarget) throws Exception {
        String optionsJson = useExternalTarget
            ? """
                ,
                "options": {
                  "targetLoadOptions": {
                    "dataSourceKey": "ext"
                  }
                }
                """
            : "";
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceTenantId": 1,
                      "targetTenantId": 2,
                      "scope": {
                        "businessTypes": ["EXAMPLE_PRODUCT"]
                      }%s
                    }
                    """.formatted(optionsJson)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asLong();
    }

    /** 创建 EXAMPLE_ORDER 比对 session（包含三层父子孙表结构）。 */
    private long createOrderSession() throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceTenantId": 1,
                      "targetTenantId": 2,
                      "scope": {
                        "businessTypes": ["EXAMPLE_ORDER"]
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asLong();
    }

    /** 调用 getBusinessDetail 接口并返回 data 节点 JSON。 */
    private JsonNode getBusinessDetail(long sessionId, String businessType, String businessKey, String view) throws Exception {
        String response = mockMvc.perform(get("/api/tenantDiff/standalone/session/getBusinessDetail")
                .param("sessionId", String.valueOf(sessionId))
                .param("businessType", businessType)
                .param("businessKey", businessKey)
                .param("view", view))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    /** 从 businessDetail 的 tableDiffs 数组中按 tableName 查找对应节点。 */
    private JsonNode findTableDiff(JsonNode businessDetail, String tableName) {
        for (JsonNode tableDiff : businessDetail.path("tableDiffs")) {
            if (tableName.equals(tableDiff.path("tableName").asText())) {
                return tableDiff;
            }
        }
        throw new AssertionError("tableDiff not found: " + tableName);
    }

    /** 检查 tableDiff 的 recordDiffs 中是否包含指定 diffType 的记录。 */
    private boolean containsDiffType(JsonNode tableDiff, String diffType) {
        for (JsonNode recordDiff : tableDiff.path("recordDiffs")) {
            if (diffType.equals(recordDiff.path("diffType").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 调用 EXAMPLE_PRODUCT 的预览接口，返回解析后的 PreviewData。 */
    private PreviewData preview(long sessionId) throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode root = objectMapper.readTree(response).path("data");
        List<ActionPreview> actions = new ArrayList<>();
        for (JsonNode action : root.path("actions")) {
            actions.add(new ActionPreview(
                action.path("actionId").asText(),
                action.path("recordBusinessKey").asText(),
                action.path("diffType").asText()
            ));
        }
        return new PreviewData(root.path("previewToken").asText(), actions);
    }

    /** 调用 EXAMPLE_ORDER 的预览接口（默认不允许 DELETE）。 */
    private PreviewData previewOrder(long sessionId) throws Exception {
        return previewOrder(sessionId, false);
    }

    /** 调用 EXAMPLE_ORDER 的预览接口，可指定是否允许 DELETE 类型的 action。 */
    private PreviewData previewOrder(long sessionId, boolean allowDelete) throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": %s,
                        "businessTypes": ["EXAMPLE_ORDER"],
                        "diffTypes": ["INSERT", "UPDATE", "DELETE"]
                      }
                    }
                    """.formatted(sessionId, allowDelete)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode root = objectMapper.readTree(response).path("data");
        List<ActionPreview> actions = new ArrayList<>();
        for (JsonNode action : root.path("actions")) {
            actions.add(new ActionPreview(
                action.path("actionId").asText(),
                action.path("recordBusinessKey").asText(),
                action.path("diffType").asText(),
                action.path("dependencyLevel").asInt()
            ));
        }
        return new PreviewData(root.path("previewToken").asText(), actions);
    }

    private int countApplyRecords() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xai_tenant_diff_apply_record", Integer.class);
    }

    private String latestApplyStatus() {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM xai_tenant_diff_apply_record ORDER BY id DESC LIMIT 1",
            String.class
        );
    }

    private int countSnapshots() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xai_tenant_diff_snapshot", Integer.class);
    }

    private int countTargetProduct(String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = ?",
            Integer.class,
            productCode
        );
    }

    private int externalCountTargetProduct(String productCode) {
        return externalJdbc.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = ?",
            Integer.class,
            productCode
        );
    }

    private int orderCount(String orderCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = ?",
            Integer.class,
            orderCode
        );
    }

    private String orderName(String orderCode) {
        return jdbcTemplate.queryForObject(
            "SELECT order_name FROM example_order WHERE tenantsid = 2 AND order_code = ?",
            String.class,
            orderCode
        );
    }

    private int itemCountForOrder(String orderCode) {
        return jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM example_order_item i
                JOIN example_order o ON i.order_id = o.id
                WHERE i.tenantsid = 2 AND o.tenantsid = 2 AND o.order_code = ?
                """,
            Integer.class,
            orderCode
        );
    }

    private int detailCountForOrder(String orderCode) {
        return jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM example_order_item_detail d
                JOIN example_order_item i ON d.order_item_id = i.id
                JOIN example_order o ON i.order_id = o.id
                WHERE d.tenantsid = 2 AND i.tenantsid = 2 AND o.tenantsid = 2 AND o.order_code = ?
                """,
            Integer.class,
            orderCode
        );
    }

    private int itemCountByCode(String itemCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND item_code = ?",
            Integer.class,
            itemCode
        );
    }

    private int detailCountByCode(String detailCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_order_item_detail WHERE tenantsid = 2 AND detail_code = ?",
            Integer.class,
            detailCode
        );
    }

    /** 预览接口的解析结果：包含 previewToken 和 action 列表。 */
    private record PreviewData(String previewToken, List<ActionPreview> actions) {
        private ActionPreview findByRecordBusinessKey(String recordBusinessKey) {
            return actions.stream()
                .filter(action -> recordBusinessKey.equals(action.recordBusinessKey))
                .findFirst()
                .orElseThrow();
        }

        private ActionPreview findFirstByDependencyLevel(int dependencyLevel) {
            return actions.stream()
                .filter(action -> action.dependencyLevel == dependencyLevel)
                .findFirst()
                .orElse(null);
        }
    }

    /** 单个 action 的预览摘要：包含 actionId、业务键、差异类型和依赖层级。 */
    private record ActionPreview(String actionId, String recordBusinessKey, String diffType, int dependencyLevel) {
        private ActionPreview(String actionId, String recordBusinessKey, String diffType) {
            this(actionId, recordBusinessKey, diffType, 0);
        }
    }
}
