package com.diff.standalone.plugin;


import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.scope.ScopeFilter;
import com.diff.core.domain.schema.BusinessSchema;

import java.util.List;

/**
 * Standalone 业务类型插件扩展点（无 DAP）。
 *
 * <p>
 * <b>设计动机：</b>采用插件模式是为了支持不同业务类型（如 INSTRUCTION、API_TEMPLATE）的差异化扩展——
 * 每种业务类型的表结构、外键关系、加载逻辑各不相同，通过接口抽象可避免在核心 Diff/Apply 流程中硬编码业务逻辑，
 * 实现“按业务类型可插拔”的架构。
 * </p>
 *
 * <p>
 * 每个业务类型需实现该接口，以便：
 * <ul>
 *     <li>定义业务的 schema 元数据（表依赖、外键关系、字段类型等）</li>
 *     <li>列出目标租户的业务键（businessKey），用于对比范围收敛</li>
 *     <li>按 businessKey 加载业务数据并组装为标准化 {@link BusinessData}</li>
 * </ul>
 * </p>
 *
 * <p>
 * 注意：插件应尽量使用稳定 businessKey 定位业务对象，避免跨 tenant 依赖自增 id。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface StandaloneBusinessTypePlugin {

    /**
     * 返回业务类型标识（如 INSTRUCTION、API_TEMPLATE）。
     *
     * @return 非空业务类型标识
     */
    String businessType();

    /**
     * 返回该业务类型的 schema 元数据。
     *
     * @return 业务 schema（表依赖、外键、字段类型等）
     */
    BusinessSchema schema();

    /**
     * 列出指定租户在给定 filter 下的业务键。
     *
     * @param tenantId 租户 ID
     * @param filter   范围过滤（如 product、前缀等）
     * @return 业务键列表（可为空，不可为 null）
     */
    List<String> listBusinessKeys(Long tenantId, ScopeFilter filter);

    /**
     * 列出业务键（支持 LoadOptions 传递 dataSourceKey）。
     *
     * <p>默认实现委托到不含 LoadOptions 的旧签名，保持向后兼容。
     * 需要多数据源的插件应覆盖此方法。</p>
     *
     * @param tenantId 租户 ID
     * @param filter   范围过滤
     * @param options  加载选项（含 dataSourceKey 等）
     * @return 业务键列表（可为空，不可为 null）
     */
    default List<String> listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options) {
        return listBusinessKeys(tenantId, filter);
    }

    /**
     * 按 businessKey 加载单个业务的完整数据。
     *
     * @param tenantId   租户 ID
     * @param businessKey 业务键
     * @param options    加载选项（含 dataSourceKey 等）
     * @return 业务数据，加载失败或不存在时可返回 null
     */
    BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options);
}

