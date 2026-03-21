package com.diff.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 租户差异对比框架的外部化配置。
 *
 * <p>
 * 通过 {@code tenant-diff.standalone.*} 前缀绑定，允许不同项目覆盖默认忽略字段、
 * 派生字段名称和 Schema 初始化策略，无需修改框架代码。
 * </p>
 *
 * <p>
 * 配置示例：
 * <pre>{@code
 * tenant-diff:
 *   standalone:
 *     enabled: true
 *     schema:
 *       init-mode: always          # none / always / embedded-only
 *       table-prefix: "xai_tenant_diff_"
 *     default-ignore-fields:
 *       - id
 *       - tenantsid
 *       - version
 *       - data_modify_time
 * }</pre>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@ConfigurationProperties(prefix = "tenant-diff.standalone")
public class TenantDiffProperties {

    /** 是否启用 tenant-diff standalone 模式（opt-in，默认 false）。 */
    private boolean enabled = false;

    /**
     * 默认忽略字段集合（用于 record 内容对比）。
     *
     * <p>对比引擎在比较两条记录的字段差异时，会跳过此集合中的字段。
     * 若未配置，使用内置默认值。</p>
     */
    private Set<String> defaultIgnoreFields = Set.of(
        "id", "tenantsid", "version", "data_modify_time"
    );

    /** 主业务键的派生字段名（用于跨租户对齐时标识主记录归属）。 */
    private String mainBusinessKeyField = "main_business_key";

    /** 父业务键的派生字段名（用于子表记录关联父记录的业务键）。 */
    private String parentBusinessKeyField = "parent_business_key";

    /** Schema 初始化配置（控制框架表的自动建表行为）。 */
    private SchemaProperties schema = new SchemaProperties();

    /**
     * Schema 初始化配置——控制框架表（session/result/apply_record/snapshot/decision_record）
     * 的自动建表行为。
     *
     * <p>
     * 三种模式：
     * <ul>
     *     <li>{@code none}（默认）：不自动建表，业务方自行执行 DDL 或集成 Flyway</li>
     *     <li>{@code always}：每次启动执行 {@code CREATE TABLE IF NOT EXISTS}</li>
     *     <li>{@code embedded-only}：仅对嵌入式数据库（H2/HSQL/Derby）自动建表</li>
     * </ul>
     * </p>
     */
    @Data
    public static class SchemaProperties {

        /**
         * 建表初始化模式。
         *
         * <ul>
         *     <li>{@code none}：不自动建表（生产环境推荐）</li>
         *     <li>{@code always}：每次启动执行建表脚本（开发/测试环境）</li>
         *     <li>{@code embedded-only}：仅对嵌入式数据库自动建表（Demo/单元测试）</li>
         * </ul>
         */
        private String initMode = "none";

        /**
         * 框架表的表名前缀（默认 {@code xai_tenant_diff_}）。
         *
         * <p>该前缀同时作用于 Schema 初始化脚本与框架自身 MyBatis 运行时访问。</p>
         */
        private String tablePrefix = "xai_tenant_diff_";
    }
}
