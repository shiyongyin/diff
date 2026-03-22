-- ============================================
-- Tenant Diff Framework Tables (MySQL)
-- Version: 0.0.1-SNAPSHOT
-- ============================================

CREATE TABLE IF NOT EXISTS xai_tenant_diff_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key     VARCHAR(64),
    source_tenant_id BIGINT,
    target_tenant_id BIGINT,
    scope_json      TEXT,
    options_json    TEXT,
    status          VARCHAR(32),
    error_msg       TEXT,
    warning_json    TEXT,
    version         INT DEFAULT 0,
    created_at      DATETIME,
    finished_at     DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户差异比对会话';

CREATE TABLE IF NOT EXISTS xai_tenant_diff_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT,
    business_type   VARCHAR(64),
    business_table  VARCHAR(128),
    business_key    VARCHAR(255),
    business_name   VARCHAR(255),
    diff_type       VARCHAR(32),
    statistics_json TEXT,
    diff_json       LONGTEXT,
    created_at      DATETIME,
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户差异比对结果';

CREATE TABLE IF NOT EXISTS xai_tenant_diff_apply_record (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_key            VARCHAR(64),
    session_id           BIGINT,
    target_tenant_id     BIGINT,
    target_data_source_key VARCHAR(64),
    direction            VARCHAR(32),
    plan_json            TEXT,
    status               VARCHAR(32),
    error_msg            TEXT,
    failure_stage        VARCHAR(64),
    failure_action_id    VARCHAR(512),
    diagnostics_json     TEXT,
    version              INT DEFAULT 0,
    started_at           DATETIME,
    finished_at          DATETIME,
    verify_status        VARCHAR(32),
    verify_json          TEXT,
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差异Apply执行记录';

CREATE TABLE IF NOT EXISTS xai_tenant_diff_apply_lease (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_tenant_id       BIGINT NOT NULL,
    target_data_source_key VARCHAR(64) NOT NULL,
    session_id             BIGINT NOT NULL,
    apply_id               BIGINT NULL,
    lease_token            VARCHAR(64) NOT NULL,
    leased_at              DATETIME NOT NULL,
    expires_at             DATETIME NOT NULL,
    UNIQUE KEY uk_apply_lease_target (target_tenant_id, target_data_source_key),
    UNIQUE KEY uk_apply_lease_token (lease_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Apply 目标租户互斥租约';

CREATE TABLE IF NOT EXISTS xai_tenant_diff_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_id        BIGINT,
    session_id      BIGINT,
    side            VARCHAR(16),
    business_type   VARCHAR(64),
    business_table  VARCHAR(128),
    business_key    VARCHAR(255),
    snapshot_json   LONGTEXT,
    created_at      DATETIME,
    INDEX idx_session_apply (session_id, apply_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Apply前快照(用于回滚)';

CREATE TABLE IF NOT EXISTS xai_tenant_diff_decision_record (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id          BIGINT,
    business_type       VARCHAR(64),
    business_key        VARCHAR(255),
    table_name          VARCHAR(128),
    record_business_key VARCHAR(255),
    diff_type           VARCHAR(32),
    decision            VARCHAR(32),
    decision_reason     TEXT,
    decision_time       DATETIME,
    execution_status    VARCHAR(32),
    execution_time      DATETIME,
    error_msg           TEXT,
    apply_id            BIGINT,
    created_at          DATETIME,
    updated_at          DATETIME,
    INDEX idx_session_id (session_id),
    INDEX idx_decision_session_biz (session_id, business_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工决策记录';
