-- ============================================
-- Tenant Diff Framework Tables (H2)
-- Version: 0.0.1-SNAPSHOT
-- ============================================

CREATE TABLE IF NOT EXISTS xai_tenant_diff_session (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key VARCHAR(64),
    source_tenant_id BIGINT,
    target_tenant_id BIGINT,
    scope_json  CLOB,
    options_json CLOB,
    status      VARCHAR(32),
    error_msg   CLOB,
    warning_json CLOB,
    version     INT DEFAULT 0,
    created_at  TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS xai_tenant_diff_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT,
    business_type   VARCHAR(64),
    business_table  VARCHAR(128),
    business_key    VARCHAR(255),
    business_name   VARCHAR(255),
    diff_type       VARCHAR(32),
    statistics_json CLOB,
    diff_json       CLOB,
    created_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS xai_tenant_diff_apply_record (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_key            VARCHAR(64),
    session_id           BIGINT,
    target_tenant_id     BIGINT,
    target_data_source_key VARCHAR(64),
    direction            VARCHAR(32),
    plan_json            CLOB,
    status               VARCHAR(32),
    error_msg            CLOB,
    failure_stage        VARCHAR(64),
    failure_action_id    VARCHAR(512),
    diagnostics_json     CLOB,
    version              INT DEFAULT 0,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    verify_status        VARCHAR(32),
    verify_json          CLOB
);

CREATE TABLE IF NOT EXISTS xai_tenant_diff_apply_lease (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_tenant_id       BIGINT NOT NULL,
    target_data_source_key VARCHAR(64) NOT NULL,
    session_id             BIGINT NOT NULL,
    apply_id               BIGINT,
    lease_token            VARCHAR(64) NOT NULL,
    leased_at              TIMESTAMP NOT NULL,
    expires_at             TIMESTAMP NOT NULL,
    CONSTRAINT uk_apply_lease_target UNIQUE (target_tenant_id, target_data_source_key),
    CONSTRAINT uk_apply_lease_token UNIQUE (lease_token)
);

CREATE TABLE IF NOT EXISTS xai_tenant_diff_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_id        BIGINT,
    session_id      BIGINT,
    side            VARCHAR(16),
    business_type   VARCHAR(64),
    business_table  VARCHAR(128),
    business_key    VARCHAR(255),
    snapshot_json   CLOB,
    created_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS xai_tenant_diff_decision_record (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id          BIGINT,
    business_type       VARCHAR(64),
    business_key        VARCHAR(255),
    table_name          VARCHAR(128),
    record_business_key VARCHAR(255),
    diff_type           VARCHAR(32),
    decision            VARCHAR(32),
    decision_reason     CLOB,
    decision_time       TIMESTAMP,
    execution_status    VARCHAR(32),
    execution_time      TIMESTAMP,
    error_msg           CLOB,
    apply_id            BIGINT,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_decision_session_biz ON xai_tenant_diff_decision_record (session_id, business_type, business_key);
