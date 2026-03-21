package com.diff.core.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 标准化记录模型容器——承载表中一行数据的业务字段与元数据。
 *
 * <p>
 * 记录的 {@link #businessKey} 是跨租户对齐的关键：对比引擎通过它配对
 * "逻辑上相同"的记录，而非依赖各租户独立自增的物理 {@link #id}。
 * {@link #id} 仅在 Apply 的 UPDATE/DELETE 阶段用于在目标租户中精确定位物理行。
 * </p>
 *
 * <h3>为什么需要 fingerprint</h3>
 * <p>
 * 全量逐字段对比在记录数较多时开销显著。若插件在构建阶段预计算了 fingerprint
 * （通常是对业务字段排序后取 MD5），引擎可在 O(1) 时间内判定 NOOP，跳过字段级遍历。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see BusinessData
 * @see TableData
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordData {
    /** 物理主键（仅 Apply 阶段用于 UPDATE/DELETE 定位）。 */
    @JsonProperty("id")
    private Long id;

    /** 业务键——跨租户对齐的核心标识（如 instructionno + 行序号组合）。 */
    @JsonProperty("businessKey")
    private String businessKey;

    /** 辅助说明（不参与对比，仅用于日志和展示）。 */
    @JsonProperty("businessNote")
    private String businessNote;

    /** 是否为公共数据（部分插件据此决定是否纳入对比范围）。 */
    @JsonProperty("publicFlag")
    private boolean publicFlag;

    /** 记录的全量业务字段（key = 列名，value = 列值）。 */
    @JsonProperty("fields")
    private Map<String, Object> fields;

    /**
     * 记录指纹——用于快速判断 NOOP，避免逐字段对比。
     *
     * <p>
     * 由插件预计算或引擎即时计算（对 fields 排序后排除 ignoreFields 再取 MD5）。
     * {@code null} 表示未提供，引擎将自行计算。
     * </p>
     */
    @JsonProperty("fingerprint")
    private String fingerprint;

    /** 记录最后修改时间（辅助信息，不参与对比逻辑）。 */
    @JsonProperty("modifyTime")
    private LocalDateTime modifyTime;
}
