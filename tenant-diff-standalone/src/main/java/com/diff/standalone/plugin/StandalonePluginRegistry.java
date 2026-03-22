package com.diff.standalone.plugin;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone 插件注册表：按 businessType 查找 {@link StandaloneBusinessTypePlugin}。
 *
 * <p>
 * <b>设计动机：</b>对重复或缺失的 businessType 采用 fail-fast 策略——构造时发现重复立即抛异常，
 * 查询时发现未注册立即抛异常。这样可在应用启动或首次调用时尽早暴露配置错误，避免运行时静默失败。
 * </p>
 *
 * <p>
 * 插件负责“按业务类型加载业务模型”，注册表负责将 Spring 注入的插件集合固化为只读映射。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
public class StandalonePluginRegistry {
    private final Map<String, StandaloneBusinessTypePlugin> pluginsByType;

    /**
     * 构造注册表，对重复 businessType 立即抛异常（fail-fast）。
     *
     * @param plugins 插件列表（可为 null，null 元素和 blank businessType 会被跳过或抛异常）
     * @throws IllegalStateException 若 businessType 为空或重复
     */
    public StandalonePluginRegistry(List<StandaloneBusinessTypePlugin> plugins) {
        Map<String, StandaloneBusinessTypePlugin> map = new HashMap<>();
        if (plugins != null) {
            for (StandaloneBusinessTypePlugin plugin : plugins) {
                if (plugin == null) {
                    continue;
                }
                String type = plugin.businessType();
                if (type == null || type.isBlank()) {
                    throw new IllegalStateException("StandaloneBusinessTypePlugin.businessType() must not be blank");
                }
                warnIfPotentiallyStateful(plugin);
                if (map.putIfAbsent(type, plugin) != null) {
                    throw new IllegalStateException("Duplicate plugin for businessType=" + type);
                }
            }
        }
        this.pluginsByType = Collections.unmodifiableMap(map);
    }

    /**
     * 获取指定 businessType 的插件，未注册时抛异常（fail-fast）。
     *
     * @param businessType 业务类型标识
     * @return 对应的插件
     * @throws IllegalArgumentException 若 businessType 未注册
     */
    public StandaloneBusinessTypePlugin getRequired(String businessType) {
        StandaloneBusinessTypePlugin plugin = pluginsByType.get(businessType);
        if (plugin == null) {
            throw new IllegalArgumentException("No plugin registered for businessType=" + businessType);
        }
        return plugin;
    }

    /**
     * 返回所有已注册的插件（只读映射）。
     *
     * @return businessType -> 插件的不可变映射
     */
    public Map<String, StandaloneBusinessTypePlugin> all() {
        return pluginsByType;
    }

    private static void warnIfPotentiallyStateful(StandaloneBusinessTypePlugin plugin) {
        Class<?> type = plugin.getClass();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isVolatile(modifiers)) {
                continue;
            }
            log.warn("插件可能包含可变成员字段，需自行保证线程安全: pluginClass={}, field={}",
                type.getName(), field.getName());
        }
    }
}
