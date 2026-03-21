package com.diff.standalone.service;

import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.web.dto.request.DecisionItem;

import java.util.List;

/**
 * Decision 管理服务——提供 diff 记录的逐条审查决策持久化能力。
 *
 * <p>
 * Decision 与 Selection 互补：Selection 是 Apply 阶段的一次性 action 选择，
 * Decision 是 Review 阶段的持久化审查结论（ACCEPT/SKIP），可跨多次 Apply 复用。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see com.diff.core.domain.diff.DecisionType
 */
public interface DecisionRecordService {

    /**
     * 批量保存用户决策（upsert 语义：不存在则创建，已存在则更新）。
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型
     * @param businessKey  业务键
     * @param decisions    决策列表
     * @return 实际保存的条数
     */
    int saveDecisions(Long sessionId, String businessType,
                      String businessKey, List<DecisionItem> decisions);

    /**
     * 查询某业务对象下所有决策记录。
     *
     * @param sessionId    会话 ID
     * @param businessType 业务类型
     * @param businessKey  业务键
     * @return 决策记录列表（可为空）
     */
    List<TenantDiffDecisionRecordPo> listDecisions(Long sessionId,
                                                    String businessType,
                                                    String businessKey);
}
