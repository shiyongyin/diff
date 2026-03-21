package com.diff.standalone.model;


import com.diff.core.domain.scope.TenantModelScope;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.standalone.plugin.StandaloneBusinessTypePlugin;
import com.diff.standalone.plugin.StandalonePluginRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone 租户模型构建器：按业务类型路由到插件，构建标准化 BusinessData 列表。
 *
 * <p>
 * 该构建器是“对比/快照/回滚”的数据入口：
 * <ul>
 *     <li>通过 {@link StandalonePluginRegistry} 找到指定 businessType 的 {@link StandaloneBusinessTypePlugin}</li>
 *     <li>根据 {@link TenantModelScope} 决定要加载哪些 businessKey</li>
 *     <li>调用插件加载业务数据并组装为 {@link BusinessData}</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>设计动机：</b>采用“单 key 失败不中断整体”的容错策略——部分 businessKey 加载失败（如网络抖动、单条数据异常）
 * 时，仍可返回已成功加载的模型，并将失败信息收集到 warnings。调用方可根据业务策略决定是否容忍部分失败，
 * 实现“尽量完成”的 partial success。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public class StandaloneTenantModelBuilder {
    private final StandalonePluginRegistry pluginRegistry;

    /**
     * 构造租户模型构建器。
     *
     * @param pluginRegistry 插件注册表
     */
    public StandaloneTenantModelBuilder(StandalonePluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 获取指定业务类型的 schema 元数据。
     *
     * @param businessType 业务类型标识
     * @return 对应的 BusinessSchema；未注册时返回 {@code null}
     */
    public com.diff.core.domain.schema.BusinessSchema getSchema(String businessType) {
        if (businessType == null || businessType.isBlank()) {
            return null;
        }
        try {
            StandaloneBusinessTypePlugin plugin = pluginRegistry.getRequired(businessType);
            return plugin.schema();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 构建结果。
     *
     * <p>包含成功加载的 models 和失败/警告信息，支持 partial success 场景。</p>
     *
     * @param models   构建出的业务模型（BusinessData）
     * @param warnings 构建过程中的警告信息（如单个 businessKey 加载失败）
     * @author tenant-diff
     * @since 2026-01-20
     */
    public record BuildResult(List<BusinessData> models, List<String> warnings) {
    }

    /**
     * 构建指定租户在指定 scope 下的业务模型集合，并收集 warnings。
     *
     * <p>单个 businessKey 加载失败不会中断，失败信息会写入 warnings。</p>
     *
     * @param tenantId 租户 ID
     * @param scope    构建范围（businessTypes + 可选 allow-list + filter）
     * @param options  加载选项（插件可选使用）
     * @return 构建结果（models + warnings）
     * @throws IllegalArgumentException tenantId 或 scope 无效
     */
    public BuildResult buildWithWarnings(Long tenantId, TenantModelScope scope, LoadOptions options) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (scope == null || scope.getBusinessTypes() == null || scope.getBusinessTypes().isEmpty()) {
            throw new IllegalArgumentException("scope.businessTypes must not be empty");
        }

        LoadOptions effectiveOptions = options == null ? LoadOptions.builder().build() : options;

        List<BusinessData> results = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String businessType : scope.getBusinessTypes()) {
            StandaloneBusinessTypePlugin plugin = pluginRegistry.getRequired(businessType);

            List<String> keys = scope.getBusinessKeysByType() == null ? null : scope.getBusinessKeysByType().get(businessType);
            if (keys == null || keys.isEmpty()) {
                // 未显式指定 allow-list：交由插件按 filter 列出业务键（可做产品/前缀等筛选）。
                keys = plugin.listBusinessKeys(tenantId, scope.getFilter(), effectiveOptions);
            }
            if (keys == null) {
                keys = Collections.emptyList();
            }

            for (String key : keys) {
                try {
                    BusinessData model = plugin.loadBusiness(tenantId, key, effectiveOptions);
                    if (model != null) {
                        results.add(model);
                    } else {
                        // 插件返回空模型：记录警告但不中断
                        warnings.add("插件返回空模型: businessType=" + businessType + ", businessKey=" + key);
                    }
                } catch (Exception e) {
                    // 容错策略：单个业务键加载失败不阻断整体构建
                    // 将失败信息收集到 warnings，由调用方决定如何处理（容忍 or 中断）
                    warnings.add("加载业务数据失败: businessType=" + businessType + ", businessKey=" + key + ", error=" + e.getMessage());
                }
            }
        }

        return new BuildResult(results, warnings);
    }
}

