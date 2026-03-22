SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS xai_tenant_diff_decision_record;
DROP TABLE IF EXISTS xai_tenant_diff_snapshot;
DROP TABLE IF EXISTS xai_tenant_diff_apply_lease;
DROP TABLE IF EXISTS xai_tenant_diff_apply_record;
DROP TABLE IF EXISTS xai_tenant_diff_result;
DROP TABLE IF EXISTS xai_tenant_diff_session;
DROP TABLE IF EXISTS example_order_item_detail;
DROP TABLE IF EXISTS example_order_item;
DROP TABLE IF EXISTS example_order;
DROP TABLE IF EXISTS example_product;

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE xai_tenant_diff_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key VARCHAR(64),
    source_tenant_id BIGINT,
    target_tenant_id BIGINT,
    scope_json TEXT,
    options_json TEXT,
    status VARCHAR(32),
    error_msg TEXT,
    warning_json TEXT,
    version INT DEFAULT 0,
    created_at DATETIME,
    finished_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE xai_tenant_diff_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT,
    business_type VARCHAR(64),
    business_table VARCHAR(128),
    business_key VARCHAR(255),
    business_name VARCHAR(255),
    diff_type VARCHAR(32),
    statistics_json TEXT,
    diff_json LONGTEXT,
    created_at DATETIME,
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE xai_tenant_diff_apply_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_key VARCHAR(64),
    session_id BIGINT,
    target_tenant_id BIGINT,
    target_data_source_key VARCHAR(64),
    direction VARCHAR(32),
    plan_json TEXT,
    status VARCHAR(32),
    error_msg TEXT,
    failure_stage VARCHAR(64),
    failure_action_id VARCHAR(512),
    diagnostics_json TEXT,
    version INT DEFAULT 0,
    started_at DATETIME,
    finished_at DATETIME,
    verify_status VARCHAR(32),
    verify_json TEXT,
    INDEX idx_apply_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE xai_tenant_diff_apply_lease (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_tenant_id BIGINT NOT NULL,
    target_data_source_key VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    apply_id BIGINT,
    lease_token VARCHAR(64) NOT NULL,
    leased_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    UNIQUE KEY uk_apply_lease_target (target_tenant_id, target_data_source_key),
    UNIQUE KEY uk_apply_lease_token (lease_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE xai_tenant_diff_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_id BIGINT,
    session_id BIGINT,
    side VARCHAR(16),
    business_type VARCHAR(64),
    business_table VARCHAR(128),
    business_key VARCHAR(255),
    snapshot_json LONGTEXT,
    created_at DATETIME,
    INDEX idx_session_apply (session_id, apply_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE xai_tenant_diff_decision_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT,
    business_type VARCHAR(64),
    business_key VARCHAR(255),
    table_name VARCHAR(128),
    record_business_key VARCHAR(255),
    diff_type VARCHAR(32),
    decision VARCHAR(32),
    decision_reason TEXT,
    decision_time DATETIME,
    execution_status VARCHAR(32),
    execution_time DATETIME,
    error_msg TEXT,
    apply_id BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_decision_session_id (session_id),
    INDEX idx_decision_session_biz (session_id, business_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE example_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid BIGINT,
    product_code VARCHAR(64),
    product_name VARCHAR(255),
    price DECIMAL(10, 2),
    status VARCHAR(32) DEFAULT 'ACTIVE',
    version INT DEFAULT 0,
    data_modify_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES
(1, 'PROD-001', '标准套餐A', 99.00, 'ACTIVE'),
(1, 'PROD-002', '高级套餐B', 199.00, 'ACTIVE'),
(1, 'PROD-003', '企业套餐C', 499.00, 'ACTIVE');

INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES
(2, 'PROD-001', '标准套餐A', 99.00, 'ACTIVE'),
(2, 'PROD-002', '高级套餐B-改', 249.00, 'ACTIVE');

CREATE TABLE example_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid BIGINT,
    order_code VARCHAR(64),
    order_name VARCHAR(255),
    total_amount DECIMAL(10,2),
    status VARCHAR(32) DEFAULT 'PENDING',
    version INT DEFAULT 0,
    data_modify_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE example_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid BIGINT,
    item_code VARCHAR(64),
    order_id BIGINT,
    product_name VARCHAR(255),
    quantity INT DEFAULT 1,
    unit_price DECIMAL(10,2),
    version INT DEFAULT 0,
    data_modify_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE example_order_item_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid BIGINT,
    detail_code VARCHAR(64),
    order_item_id BIGINT,
    detail_name VARCHAR(255),
    detail_value VARCHAR(255),
    version INT DEFAULT 0,
    data_modify_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO example_order (tenantsid, order_code, order_name, total_amount, status) VALUES
(1, 'ORD-001', '测试订单A', 298.00, 'CONFIRMED'),
(1, 'ORD-002', '测试订单B', 499.00, 'CONFIRMED');

INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(1, 'ITEM-001', (SELECT id FROM example_order WHERE tenantsid = 1 AND order_code = 'ORD-001'), '标准套餐A', 2, 99.00),
(1, 'ITEM-002', (SELECT id FROM example_order WHERE tenantsid = 1 AND order_code = 'ORD-001'), '高级套餐B', 1, 100.00),
(1, 'ITEM-003', (SELECT id FROM example_order WHERE tenantsid = 1 AND order_code = 'ORD-002'), '企业套餐C', 1, 499.00);

INSERT INTO example_order (tenantsid, order_code, order_name, total_amount, status) VALUES
(2, 'ORD-001', '测试订单A-旧', 200.00, 'PENDING');

INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(2, 'ITEM-001', (SELECT id FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-001'), '标准套餐A', 1, 99.00);

INSERT INTO example_order (tenantsid, order_code, order_name, total_amount, status) VALUES
(2, 'ORD-DEL', '待删除订单', 88.00, 'PENDING');

INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(2, 'ITEM-DEL', (SELECT id FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-DEL'), '待删除商品', 1, 88.00);

INSERT INTO example_order_item_detail (tenantsid, detail_code, order_item_id, detail_name, detail_value) VALUES
(1, 'DTL-001', (SELECT id FROM example_order_item WHERE tenantsid = 1 AND item_code = 'ITEM-003'), '规格', '企业版'),
(1, 'DTL-002', (SELECT id FROM example_order_item WHERE tenantsid = 1 AND item_code = 'ITEM-003'), '有效期', '365天');

INSERT INTO example_order_item_detail (tenantsid, detail_code, order_item_id, detail_name, detail_value) VALUES
(2, 'DTL-DEL', (SELECT id FROM example_order_item WHERE tenantsid = 2 AND item_code = 'ITEM-DEL'), '待删除属性', 'DEL');
