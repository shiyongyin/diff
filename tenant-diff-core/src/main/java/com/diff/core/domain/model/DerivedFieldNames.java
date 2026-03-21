package com.diff.core.domain.model;

/**
 * 插件在模型加载时附加的派生字段名称常量。
 *
 * <p>
 * 原始数据库记录中不包含这些字段——它们是插件在构建 {@link RecordData} 时
 * 根据业务逻辑注入的"虚拟列"，用途如下：
 * </p>
 * <ul>
 *   <li>{@link #MAIN_BUSINESS_KEY}：记录所属业务对象的顶层业务键，
 *       便于 Apply 阶段反查父级关系</li>
 *   <li>{@link #PARENT_BUSINESS_KEY}：记录直接父表记录的业务键，
 *       用于 Apply 阶段从 {@link com.diff.core.apply.IdMapping} 查找父记录新 id 并替换外键</li>
 * </ul>
 *
 * <p>Apply 写入目标库前，{@link com.diff.core.spi.apply.BusinessApplySupport} 会移除这些派生字段，
 * 确保不会向数据库写入不存在的列。</p>
 *
 * <p>可通过配置 {@code tenant-diff.standalone.main-business-key-field} /
 * {@code tenant-diff.standalone.parent-business-key-field} 覆盖默认值。</p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public final class DerivedFieldNames {
    public static final String MAIN_BUSINESS_KEY = "main_business_key";
    public static final String PARENT_BUSINESS_KEY = "parent_business_key";

    private DerivedFieldNames() {
    }
}
