package com.diff.standalone.util;

import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.diff.DiffSessionOptions;

/**
 * Standalone 模式的 {@link LoadOptions} 解析器。
 *
 * <p>
 * <b>设计动机：</b>source 与 target 租户可能使用不同的数据源（如 source 读 ERP 只读库、target 写业务库），
 * 因此需要支持 source/target 独立的 LoadOptions（含 dataSourceKey 等）。通用 loadOptions 作为 fallback，
 * 未单独配置时使用。
 * </p>
 *
 * <p>
 * 用于从 {@link DiffSessionOptions} 中解析出正确的加载选项，支持：
 * <ul>
 *     <li>源端/目标端独立配置</li>
 *     <li>通用配置作为 fallback</li>
 *     <li>根据 Apply 方向自动选择</li>
 * </ul>
 * </p>
 *
 * <p>
 * 优先级规则：{@code sourceLoadOptions/targetLoadOptions} > {@code loadOptions} > 空默认值
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 * @see DiffSessionOptions
 * @see LoadOptions
 */
public final class StandaloneLoadOptionsResolver {

    private StandaloneLoadOptionsResolver() {
    }

    /**
     * 解析首选的 LoadOptions，若首选为空则使用 fallback。
     *
     * @param preferred 首选配置（可为 null）
     * @param fallback  备选配置（可为 null）
     * @return 非 null 的 LoadOptions（若都为空则返回空默认实例）
     */
    public static LoadOptions resolvePreferred(LoadOptions preferred, LoadOptions fallback) {
        if (preferred != null) {
            return preferred;
        }
        if (fallback != null) {
            return fallback;
        }
        return LoadOptions.builder().build();
    }

    /**
     * 解析源端（租户 A）的加载选项。
     *
     * <p>优先级：{@code sourceLoadOptions} > {@code loadOptions}</p>
     *
     * @param options 会话选项（可为 null）
     * @return 源端加载选项
     */
    public static LoadOptions resolveSource(DiffSessionOptions options) {
        if (options == null) {
            return LoadOptions.builder().build();
        }
        return resolvePreferred(options.getSourceLoadOptions(), options.getLoadOptions());
    }

    /**
     * 解析目标端（租户 B）的加载选项。
     *
     * <p>优先级：{@code targetLoadOptions} > {@code loadOptions}</p>
     *
     * @param options 会话选项（可为 null）
     * @return 目标端加载选项
     */
    public static LoadOptions resolveTarget(DiffSessionOptions options) {
        if (options == null) {
            return LoadOptions.builder().build();
        }
        return resolvePreferred(options.getTargetLoadOptions(), options.getLoadOptions());
    }

    /**
     * 根据 Apply 方向解析对应端的加载选项。
     *
     * <p>
     * 方向映射：
     * <ul>
     *     <li>{@link ApplyDirection#A_TO_B} → 目标端（B）选项</li>
     *     <li>{@link ApplyDirection#B_TO_A} → 源端（A）选项</li>
     *     <li>{@code null} → 通用选项</li>
     * </ul>
     * </p>
     *
     * @param options   会话选项（可为 null）
     * @param direction Apply 方向（可为 null）
     * @return 对应端的加载选项
     */
    public static LoadOptions resolveForDirection(DiffSessionOptions options, ApplyDirection direction) {
        if (direction == null) {
            return resolvePreferred(null, options == null ? null : options.getLoadOptions());
        }
        return direction == ApplyDirection.A_TO_B ? resolveTarget(options) : resolveSource(options);
    }
}
