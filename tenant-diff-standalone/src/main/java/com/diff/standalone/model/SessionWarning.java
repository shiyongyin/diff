package com.diff.standalone.model;

/**
 * Session 级 Compare 警告摘要。
 *
 * <p>在对比执行中按来源侧（source/target）记录结构化警告，便于统一量化告警率并支持
 * 结果页中按侧别统计。</p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 * @param side         警告来源侧（source / target）
 * @param businessType 业务类型
 * @param businessKey  业务键
 * @param message      警告说明
 */
public record SessionWarning(String side, String businessType, String businessKey, String message) {
}
