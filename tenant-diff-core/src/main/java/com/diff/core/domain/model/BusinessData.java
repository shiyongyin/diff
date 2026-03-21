package com.diff.core.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 标准化业务模型容器——对比引擎与 Apply 阶段的统一输入。
 *
 * <p>
 * 不同业务类型（指令、模板、API 定义等）的底层表结构各不相同。
 * BusinessData 提供统一的抽象层，将"一个业务对象所涉及的所有表和记录"
 * 封装为规范化结构，使对比引擎和 Apply 执行器无需感知具体业务表结构。
 * </p>
 *
 * <h3>为什么需要 businessKey 而非 businessId</h3>
 * <p>
 * businessId 是物理主键（各租户自增），跨租户无法对齐。
 * businessKey 是业务层面的唯一标识（如指令编号），天然支持跨租户配对。
 * businessId 仅在 Apply 的 UPDATE/DELETE 阶段用于定位目标记录。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see TableData
 * @see com.diff.core.engine.TenantDiffEngine
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessData {
    /** 业务类型标识（如 INSTRUCTION、OCR_TEMPLATE），用于路由到对应的插件。 */
    @JsonProperty("businessType")
    private String businessType;

    /** 业务主表名（用于 diff 结果展示与 Apply 定位）。 */
    @JsonProperty("businessTable")
    private String businessTable;

    /** 物理主键 id（仅 Apply 的 UPDATE/DELETE 阶段使用，对比阶段不依赖此值）。 */
    @JsonProperty("businessId")
    private Long businessId;

    /** 业务键——跨租户对齐的核心标识。 */
    @JsonProperty("businessKey")
    private String businessKey;

    /** 业务名称（辅助展示，不参与对比逻辑）。 */
    @JsonProperty("businessName")
    private String businessName;

    /** 数据所属租户 id。 */
    @JsonProperty("tenantId")
    private Long tenantId;

    /** 该业务对象包含的所有表数据，按 {@link TableData#getDependencyLevel()} 排序。 */
    @JsonProperty("tables")
    private List<TableData> tables;
}
