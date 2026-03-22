package com.diff.standalone.model;

/**
 * 单侧租户模型构建过程中的结构化警告。
 *
 * <p>用于替代自由文本 warning，便于 Session 汇总、日志和 JSON 持久化统一消费，
 * 同时为调用方提供 businessType/businessKey 维度的定位信息。</p>
 *
 * <p>集合模式支持“部分失败不断开”的策略：构建器可继续返回已成功的模型并把异常转成警告。</p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 * @param businessType 业务类型
 * @param businessKey  业务键
 * @param message      警告说明
 */
public record BuildWarning(String businessType, String businessKey, String message) {
}
