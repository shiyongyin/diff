package com.diff.core.domain.apply;


import com.diff.core.domain.diff.DiffType;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Apply 选项与安全约束——控制 Apply 行为的"安全阀"。
 *
 * <h3>为什么默认禁止 DELETE</h3>
 * <p>
 * DELETE 操作不可逆（即使有快照回滚也存在时间窗口风险），
 * 因此默认 {@link #allowDelete} = false，必须显式开启。
 * 这是一种"默认安全"的设计原则。
 * </p>
 *
 * <h3>为什么需要 maxAffectedRows</h3>
 * <p>
 * 防止因 scope 配置错误导致全量数据被修改。
 * v1 以 {@code actions.size()} 作为粗略预估，超过阈值时拒绝生成计划。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyPlan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyOptions {

    /** 执行模式：DRY_RUN（只预览）或 EXECUTE（实际写库）。 */
    @Builder.Default
    private ApplyMode mode = ApplyMode.DRY_RUN;

    /** 是否允许 DELETE 操作（默认关闭，需显式开启）。 */
    @Builder.Default
    private boolean allowDelete = false;

    /** 预估影响行数上限（超过则拒绝生成计划），0 表示不限制。 */
    @Builder.Default
    private int maxAffectedRows = 1000;

    /** 可选白名单：仅同步指定的 businessKey（空列表表示不过滤）。 */
    @Builder.Default
    private List<String> businessKeys = Collections.emptyList();

    /** 可选白名单：仅同步指定的 businessType（空列表表示不过滤）。 */
    @Builder.Default
    private List<String> businessTypes = Collections.emptyList();

    /** 可选白名单：仅允许指定的记录操作类型（空列表表示不过滤）。 */
    @Builder.Default
    private List<DiffType> diffTypes = Collections.emptyList();

    /** ALL=全量执行；PARTIAL=仅执行 selectedActionIds 指定动作。 */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private SelectionMode selectionMode = SelectionMode.ALL;

    /** 用户勾选的 actionId 集合。selectionMode=PARTIAL 时必须非空。 */
    @Builder.Default
    private Set<String> selectedActionIds = Collections.emptySet();

    /** preview 返回的一致性令牌。selectionMode=PARTIAL 时必须回传。 */
    private String previewToken;

    /** 客户端请求标识（可选），仅用于审计日志追踪，不做服务端幂等。 */
    private String clientRequestId;
}
