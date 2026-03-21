package com.diff.core.domain.apply;

/**
 * Apply 选择模式。
 *
 * <p>
 * 用于支持“先 preview 再勾选执行”的交互：当选择模式为 PARTIAL 时，
 * 服务端仅执行 {@link ApplyOptions#getSelectedActionIds()} 指定的动作集合。
 * </p>
 *
 * @author tenant-diff
 * @since 1.1.0
 */
public enum SelectionMode {
    /** 全量执行（向后兼容默认值）。 */
    ALL,
    /** 仅执行 selectedActionIds 指定项（V1 仅支持主表动作）。 */
    PARTIAL
}

