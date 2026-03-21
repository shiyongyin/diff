package com.diff.standalone.service.support;

/**
 * {@code getBusinessDetail} API 的视图模式——控制 diff 结果返回的详细程度。
 *
 * <p>
 * 通过单一枚举参数替代多个 boolean（excludeNoop / stripRawFields），
 * 使 API 语义清晰且便于扩展。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum DiffDetailView {

    /** 原始引擎输出，含 NOOP 记录 + 全量 sourceFields/targetFields。向后兼容默认值。 */
    FULL,

    /** 过滤 NOOP 记录 + 投影 showFields，保留 sourceFields/targetFields。 */
    FILTERED,

    /** 过滤 NOOP 记录 + 投影 showFields + 裁剪 sourceFields/targetFields 为 null。 */
    COMPACT
}
