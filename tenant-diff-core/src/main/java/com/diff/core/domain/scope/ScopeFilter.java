package com.diff.core.domain.scope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 列出业务键（businessKeys）时的可选过滤条件。
 *
 * <p>
 * 该对象主要用于插件层的"范围收敛"——当 {@link TenantModelScope#getBusinessKeysByType()}
 * 未显式指定 keys 时，插件通过 ScopeFilter 中的条件从数据库查询符合条件的业务键列表。
 * 不同业务插件可按自身语义解释这些字段（有些字段只对特定插件有意义）。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see TenantModelScope
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScopeFilter {
    /**
     * 显式 allow-list：若提供，插件可直接使用这些 keys 而不再查询数据库。
     */
    private List<String> businessKeys;

    /** 产品标识过滤（由插件按业务语义解释）。 */
    private String product;

    /** 方案/项目过滤（由插件按业务语义解释）。 */
    private String program;

    /** 指令名前缀过滤（INSTRUCTION 插件使用）。 */
    private String instructionNamePrefix;

    /** 模板名前缀过滤（OCR_TEMPLATE 插件使用）。 */
    private String templateNamePrefix;

    /** 单据类型编号前缀过滤。 */
    private String doctypenoPrefix;
}
