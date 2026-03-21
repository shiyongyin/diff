package com.diff.standalone.apply;

import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.diff.BusinessDiff;

/**
 * 按 ApplyAction 提供业务级 diff 明细的加载器。
 *
 * <p>
 * <b>设计动机（WHY 函数式接口）</b>：Apply 执行器需要按 action 按需加载 diff，
 * 但 diff 来源有多种（结果表、内存缓存、远程 API 等）。通过本函数式接口注入加载策略，
 * 调用方可根据场景提供不同实现，执行器无需关心具体来源，实现策略与执行逻辑解耦。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@FunctionalInterface
public interface BusinessDiffLoader {
    /**
     * 根据 ApplyAction 加载对应的 BusinessDiff 明细。
     *
     * @param action 待加载的 Apply 动作，含 businessType、businessKey 等定位信息
     * @return 对应的业务级 diff 明细，不得为 null
     * @throws IllegalArgumentException 当 action 无效或 diff 不存在时
     */
    BusinessDiff load(ApplyAction action);
}
