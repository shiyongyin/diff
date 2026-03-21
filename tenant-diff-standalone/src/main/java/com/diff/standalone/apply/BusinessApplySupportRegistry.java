package com.diff.standalone.apply;


import com.diff.core.spi.apply.BusinessApplySupport;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link BusinessApplySupport} 注册表。
 *
 * <p>
 * 按 businessType 查找对应的字段变换/外键替换实现。
 * 对重复 businessType 直接抛异常，避免执行期出现不确定性。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public class BusinessApplySupportRegistry {
    private final Map<String, BusinessApplySupport> supportsByType;

    public BusinessApplySupportRegistry(List<BusinessApplySupport> supports) {
        Map<String, BusinessApplySupport> map = new HashMap<>();
        if (supports != null) {
            for (BusinessApplySupport support : supports) {
                if (support == null) {
                    continue;
                }
                String type = support.businessType();
                if (type == null || type.isBlank()) {
                    throw new IllegalStateException("BusinessApplySupport.businessType() must not be blank");
                }
                if (map.putIfAbsent(type, support) != null) {
                    throw new IllegalStateException("Duplicate BusinessApplySupport for businessType=" + type);
                }
            }
        }
        this.supportsByType = Collections.unmodifiableMap(map);
    }

    /**
     * 根据 businessType 获取对应的 Apply 支持实现。
     *
     * @param businessType 业务类型标识（如 instruction、order 等）
     * @return 对应的 BusinessApplySupport，若未注册则返回 null
     */
    public BusinessApplySupport get(String businessType) {
        return supportsByType.get(businessType);
    }
}
