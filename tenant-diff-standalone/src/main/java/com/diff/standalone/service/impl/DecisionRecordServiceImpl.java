package com.diff.standalone.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.persistence.mapper.TenantDiffDecisionRecordMapper;
import com.diff.standalone.service.DecisionRecordService;
import com.diff.standalone.web.dto.request.DecisionItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link DecisionRecordService} 默认实现——基于 MyBatis-Plus 持久化。
 *
 * <p>
 * 保存逻辑采用 upsert 语义：按 (sessionId, businessType, businessKey, tableName, recordBusinessKey)
 * 唯一定位一条记录，存在则更新 decision/reason，不存在则新增。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public class DecisionRecordServiceImpl implements DecisionRecordService {

    private final TenantDiffDecisionRecordMapper mapper;

    public DecisionRecordServiceImpl(TenantDiffDecisionRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int saveDecisions(Long sessionId, String businessType,
                             String businessKey, List<DecisionItem> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return 0;
        }

        int count = 0;
        LocalDateTime now = LocalDateTime.now();

        for (DecisionItem item : decisions) {
            TenantDiffDecisionRecordPo existing = findExisting(
                sessionId, businessType, businessKey,
                item.getTableName(), item.getRecordBusinessKey());

            if (existing != null) {
                existing.setDecision(item.getDecision());
                existing.setDecisionReason(item.getDecisionReason());
                existing.setDecisionTime(now);
                existing.setUpdatedAt(now);
                mapper.updateById(existing);
            } else {
                TenantDiffDecisionRecordPo po = TenantDiffDecisionRecordPo.builder()
                    .sessionId(sessionId)
                    .businessType(businessType)
                    .businessKey(businessKey)
                    .tableName(item.getTableName())
                    .recordBusinessKey(item.getRecordBusinessKey())
                    .decision(item.getDecision())
                    .decisionReason(item.getDecisionReason())
                    .decisionTime(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
                mapper.insert(po);
            }
            count++;
        }
        return count;
    }

    @Override
    public List<TenantDiffDecisionRecordPo> listDecisions(Long sessionId,
                                                           String businessType,
                                                           String businessKey) {
        LambdaQueryWrapper<TenantDiffDecisionRecordPo> qw = new LambdaQueryWrapper<>();
        qw.eq(TenantDiffDecisionRecordPo::getSessionId, sessionId)
          .eq(TenantDiffDecisionRecordPo::getBusinessType, businessType)
          .eq(TenantDiffDecisionRecordPo::getBusinessKey, businessKey)
          .orderByAsc(TenantDiffDecisionRecordPo::getTableName)
          .orderByAsc(TenantDiffDecisionRecordPo::getRecordBusinessKey);
        return mapper.selectList(qw);
    }

    private TenantDiffDecisionRecordPo findExisting(
            Long sessionId, String businessType, String businessKey,
            String tableName, String recordBusinessKey) {
        LambdaQueryWrapper<TenantDiffDecisionRecordPo> qw = new LambdaQueryWrapper<>();
        qw.eq(TenantDiffDecisionRecordPo::getSessionId, sessionId)
          .eq(TenantDiffDecisionRecordPo::getBusinessType, businessType)
          .eq(TenantDiffDecisionRecordPo::getBusinessKey, businessKey)
          .eq(TenantDiffDecisionRecordPo::getTableName, tableName)
          .eq(TenantDiffDecisionRecordPo::getRecordBusinessKey, recordBusinessKey)
          .last("LIMIT 1");
        return mapper.selectOne(qw);
    }
}
