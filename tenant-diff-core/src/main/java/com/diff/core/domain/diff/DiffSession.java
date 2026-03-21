package com.diff.core.domain.diff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Diff Session 元数据（对外传输契约）。
 *
 * <p>
 * DiffSession 作为 domain 层的传输对象，面向 API 调用方传递会话信息。
 * standalone 模式下实际持久化使用 {@code TenantDiffSessionPo}，
 * 两者之间通过 Service 层做转换，分离持久化细节与对外契约。
 * </p>
 *
 * <h3>为什么 scope/options 使用 Map 而非强类型</h3>
 * <p>
 * 作为传输契约，scope 和 options 的结构可能随版本演进而变化。
 * 使用 {@code Map<String, Object>} 保持向后兼容，避免反序列化旧版数据时抛异常。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see SessionStatus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiffSession {
    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("sourceTenantId")
    private String sourceTenantId;

    @JsonProperty("targetTenantId")
    private String targetTenantId;

    @JsonProperty("scope")
    private Map<String, Object> scope;

    @JsonProperty("options")
    private Map<String, Object> options;

    @JsonProperty("status")
    private SessionStatus status;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("finishedAt")
    private Instant finishedAt;

    @JsonProperty("errorMsg")
    private String errorMsg;
}
