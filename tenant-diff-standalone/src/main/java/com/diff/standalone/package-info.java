/**
 * Tenant Diff 的独立运行（Standalone）实现。
 *
 * <p>
 * 该包用于在不依赖 DAP 的场景下运行租户差异对比与 Apply/回滚能力：使用 Spring MVC 提供 REST API，
 * 使用 Spring JDBC（JdbcTemplate）直接访问数据库。
 * </p>
 *
 * <h2>核心流程</h2>
 * <ol>
 *     <li>创建 Session：记录 source/target tenant、scope、options，并落库。</li>
 *     <li>执行 Compare：按 scope 通过插件加载两侧模型，调用 {@code TenantDiffEngine} 生成 diff 并落库。</li>
 *     <li>生成/执行 Apply：基于 diff 构建 apply plan，执行 SQL（强制 tenantsid 约束）。</li>
 *     <li>回滚 Rollback：对 apply 前的 TARGET 快照与当前 TARGET 再次 diff，生成“恢复计划”并执行。</li>
 * </ol>
 *
 * <p>
 * <b>注意：</b>Standalone 以工具化、可移植为目标：部分地方刻意避免依赖 MyBatis-Plus 的分页拦截器，
 * 以及在执行层仅使用 “tenantsid + id” 定位记录（如需更强的业务键定位可通过扩展点演进）。
 * </p>
 */
package com.diff.standalone;

