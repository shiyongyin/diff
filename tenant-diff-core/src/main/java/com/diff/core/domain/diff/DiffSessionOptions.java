package com.diff.core.domain.diff;

import com.diff.core.engine.DiffRules;
import com.diff.core.domain.scope.LoadOptions;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Diff Session 的可选执行参数——控制模型加载行为与对比规则。
 *
 * <h3>为什么支持源/目标独立的 LoadOptions</h3>
 * <p>
 * 实际场景中，源租户和目标租户的数据可能存在于不同的数据源
 * （如多库部署），需要各自独立指定 dataSourceKey、版本号等加载参数。
 * 当 {@link #sourceLoadOptions} / {@link #targetLoadOptions} 未提供时，
 * 自动回退到通用的 {@link #loadOptions}。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see LoadOptions
 * @see DiffRules
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffSessionOptions {
    /** 默认加载选项（未区分源/目标时使用）。 */
    private LoadOptions loadOptions;

    /**
     * 源租户加载选项（优先级高于 {@link #loadOptions}）。
     *
     * <p>为空时回退到 {@link #loadOptions}。</p>
     */
    private LoadOptions sourceLoadOptions;

    /**
     * 目标租户加载选项（优先级高于 {@link #loadOptions}）。
     *
     * <p>为空时回退到 {@link #loadOptions}。</p>
     */
    private LoadOptions targetLoadOptions;

    /** 对比规则（主要是忽略字段配置）。 */
    private DiffRules diffRules;
}
