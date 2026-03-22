package com.diff.demo;

import com.diff.demo.plugin.ExampleProductPlugin;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回滚并发互斥端到端测试：验证同一 applyId 的并发回滚通过租约机制保证只成功一次。
 *
 * <h3>测试目标</h3>
 * <p>多个线程同时对同一次同步记录（applyId）发起回滚，
 * 验证系统通过分布式租约保证恰好 1 个回滚成功，其余返回冲突错误。</p>
 *
 * <h3>为什么需要这个测试</h3>
 * <p>在多人协作或前端重试场景下，同一 applyId 可能收到并发回滚请求。
 * 如果多个回滚同时执行，可能导致：</p>
 * <ul>
 *   <li>快照被重复使用（INSERT 的回滚 DELETE 执行两次 → 第二次报错）</li>
 *   <li>UPDATE 的回滚相互覆盖</li>
 *   <li>审计记录状态不一致</li>
 * </ul>
 * <p>框架必须通过租约互斥保证同一 applyId 在同一时间只有一个回滚任务执行。</p>
 *
 * <h3>测试策略</h3>
 * <p>通过 {@link TestConfiguration} 注入 {@code SlowJdbcTemplate}，
 * 在查询 tenant 2 的产品数据时增加 300ms 延迟，扩大竞争窗口以稳定触发并发冲突。
 * 使用 {@link java.util.concurrent.CountDownLatch} 控制 3 个线程同时起跑。</p>
 *
 * <h3>运行前提</h3>
 * <ul>
 *   <li>需要运行中的 MySQL 实例（由 {@code application-mysql-e2e.yml} 配置）</li>
 *   <li>{@code allow-bean-definition-overriding=true} 允许测试配置覆盖默认 Bean</li>
 * </ul>
 *
 * @see MysqlReleaseGateE2ETest#concurrent_apply_on_same_target_should_return_target_busy 并发同步互斥测试
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql-e2e"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MysqlRollbackConcurrencyE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证 3 个线程并发回滚同一 applyId 时，仅 1 个成功、其余返回冲突。
     *
     * <p><b>测什么：</b>创建 session → 执行同步 → 3 个线程同时回滚 →
     * 期望 successCount=1 / conflictCount=2（DIFF_E_3002）。</p>
     *
     * <p><b>为什么：</b>验证回滚的租约互斥机制在真实 MySQL（SELECT ... FOR UPDATE）
     * 环境下能正确工作，防止重复回滚导致的数据不一致。</p>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>统计 success 和 DIFF_E_3002 的计数 → 1 成功 + 2 冲突</li>
     *   <li>数据验证：回滚成功后 PROD-002 恢复为"高级套餐B-改"（249.00），
     *       PROD-003 已被删除（回滚 INSERT 的效果）</li>
     * </ol>
     */
    @Test
    void concurrent_rollback_on_same_apply_should_return_conflict_on_real_mysql() throws Exception {
        long sessionId = createProductSession();
        long applyId = executeProductApply(sessionId);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runConcurrentRollback(applyId, ready, start, successCount, conflictCount)));
        futures.add(executor.submit(() -> runConcurrentRollback(applyId, ready, start, successCount, conflictCount)));
        futures.add(executor.submit(() -> runConcurrentRollback(applyId, ready, start, successCount, conflictCount)));

        ready.await();
        start.countDown();

        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(2, conflictCount.get());
        assertEquals("高级套餐B-改", productName(2L, "PROD-002"));
        assertEquals(0, new BigDecimal("249.00").compareTo(productPrice(2L, "PROD-002")));
        assertEquals(0, countTargetProduct("PROD-003"));
    }

    /** 并发回滚执行体：等待栅栏释放后发起回滚，按结果分类计数。 */
    private void runConcurrentRollback(long applyId,
                                       CountDownLatch ready,
                                       CountDownLatch start,
                                       AtomicInteger successCount,
                                       AtomicInteger conflictCount) {
        ready.countDown();
        try {
            start.await();
            String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "applyId": %d
                        }
                        """.formatted(applyId)))
                .andReturn()
                .getResponse()
                .getContentAsString();
            JsonNode root = objectMapper.readTree(response);
            if (root.path("success").asBoolean(false)) {
                successCount.incrementAndGet();
            } else if ("DIFF_E_3002".equals(root.path("code").asText())) {
                conflictCount.incrementAndGet();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== 辅助方法 ========================

    /** 创建 EXAMPLE_PRODUCT 比对 session 并返回 sessionId。 */
    private long createProductSession() throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceTenantId": 1,
                      "targetTenantId": 2,
                      "scope": {
                        "businessTypes": ["EXAMPLE_PRODUCT"]
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

    /** 执行 EXAMPLE_PRODUCT 同步并返回 applyId。 */
    private long executeProductApply(long sessionId) throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
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
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("applyId").asLong();
    }

    private int countTargetProduct(String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = ?",
            Integer.class,
            productCode
        );
    }

    private String productName(Long tenantId, String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT product_name FROM example_product WHERE tenantsid = ? AND product_code = ?",
            String.class,
            tenantId,
            productCode
        );
    }

    private BigDecimal productPrice(Long tenantId, String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = ? AND product_code = ?",
            BigDecimal.class,
            tenantId,
            productCode
        );
    }

    /**
     * 慢查询注入测试配置：替换默认 ExampleProductPlugin，在查询 tenant 2 的产品数据时增加延迟。
     *
     * <p>通过 {@code @Primary} 覆盖原始 Bean，在 resolveJdbcTemplate 返回的
     * {@link SlowJdbcTemplate} 中对 {@code tenantsid=2} 的查询增加 300ms 延迟，
     * 目的是扩大并发竞争窗口，使 3 个回滚线程更稳定地触发租约冲突。</p>
     */
    @TestConfiguration
    static class SlowRollbackProductPluginConfiguration {

        @Bean("exampleProductPlugin")
        @Primary
        ExampleProductPlugin exampleProductPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
            return new ExampleProductPlugin(objectMapper, dataSourceRegistry) {
                @Override
                protected JdbcTemplate resolveJdbcTemplate(com.diff.core.domain.scope.LoadOptions options) {
                    return new SlowJdbcTemplate(super.resolveJdbcTemplate(options));
                }
            };
        }

        private static final class SlowJdbcTemplate extends JdbcTemplate {
            private final JdbcTemplate delegate;

            private SlowJdbcTemplate(JdbcTemplate delegate) {
                this.delegate = delegate;
            }

            @Override
            public <T> java.util.List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                return delegate.queryForList(sql, elementType, args);
            }

            @Override
            public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql != null
                    && sql.startsWith("SELECT * FROM example_product")
                    && args != null
                    && args.length >= 2
                    && Long.valueOf(2L).equals(args[0])) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("rollback slowdown interrupted", e);
                    }
                }
                return delegate.queryForList(sql, args);
            }
        }
    }
}
