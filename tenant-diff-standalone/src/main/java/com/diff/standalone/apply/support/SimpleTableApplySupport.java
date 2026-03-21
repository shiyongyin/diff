package com.diff.standalone.apply.support;

import com.diff.core.domain.schema.BusinessSchema;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 与 {@link com.diff.standalone.plugin.SimpleTablePlugin} 配对的 Apply 支持。
 *
 * <p>
 * 单表场景下，{@link AbstractSchemaBusinessApplySupport} 的默认行为（移除派生字段、
 * 类型归一化）已完全覆盖 Apply 的字段转换需求，本类无需额外代码。
 * </p>
 *
 * <p>
 * 使用示例：
 * <pre>{@code
 * @Bean
 * public BusinessApplySupport contractApply(ContractPlugin plugin, ObjectMapper om) {
 *     return new SimpleTableApplySupport(plugin.businessType(), om, plugin.schema());
 * }
 * }</pre>
 * </p>
 *
 * @author tenant-diff
 * @since 0.0.1
 * @see AbstractSchemaBusinessApplySupport
 */
public class SimpleTableApplySupport extends AbstractSchemaBusinessApplySupport {

    private final String businessType;

    /**
     * @param businessType 业务类型标识（需与配对的 Plugin 一致）
     * @param objectMapper JSON 序列化工具
     * @param schema       业务 Schema（通常从 Plugin.schema() 获取）
     */
    public SimpleTableApplySupport(String businessType, ObjectMapper objectMapper, BusinessSchema schema) {
        super(objectMapper, schema);
        this.businessType = businessType;
    }

    @Override
    public String businessType() {
        return businessType;
    }
}
