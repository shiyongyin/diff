-- ============================================================
-- Tenant-Diff 框架表
-- ============================================================

CREATE TABLE IF NOT EXISTS xai_tenant_diff_session (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key VARCHAR(64),
    source_tenant_id BIGINT,
    target_tenant_id BIGINT,
    scope_json  CLOB,
    options_json CLOB,
    status      VARCHAR(32),
    error_msg   CLOB,
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
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_key   VARCHAR(64),
    session_id  BIGINT,
    direction   VARCHAR(32),
    plan_json   CLOB,
    status      VARCHAR(32),
    error_msg   CLOB,
    version     INT DEFAULT 0,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP
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

-- ============================================================
-- 示例业务表 (ExampleProduct)
-- ============================================================

DROP TABLE IF EXISTS example_order_item_detail;
DROP TABLE IF EXISTS example_order_item;
DROP TABLE IF EXISTS example_order;
DROP TABLE IF EXISTS example_product;

CREATE TABLE IF NOT EXISTS example_product (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid       BIGINT,
    product_code    VARCHAR(64),
    product_name    VARCHAR(255),
    price           DECIMAL(10,2),
    status          VARCHAR(32) DEFAULT 'ACTIVE',
    version         INT DEFAULT 0,
    data_modify_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 种子数据：租户 1
INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES
(1, 'PROD-001', '标准套餐A', 99.00, 'ACTIVE'),
(1, 'PROD-002', '高级套餐B', 199.00, 'ACTIVE'),
(1, 'PROD-003', '企业套餐C', 499.00, 'ACTIVE');

-- 种子数据：租户 2（部分差异）
INSERT INTO example_product (tenantsid, product_code, product_name, price, status) VALUES
(2, 'PROD-001', '标准套餐A', 99.00, 'ACTIVE'),
(2, 'PROD-002', '高级套餐B-改', 249.00, 'ACTIVE');

-- ============================================================
-- 测试用父子表 (ExampleOrder + ExampleOrderItem)
-- 用于验证 Apply 链路的外键替换和依赖排序
-- ============================================================

CREATE TABLE IF NOT EXISTS example_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid       BIGINT,
    order_code      VARCHAR(64),
    order_name      VARCHAR(255),
    total_amount    DECIMAL(10,2),
    status          VARCHAR(32) DEFAULT 'PENDING',
    version         INT DEFAULT 0,
    data_modify_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS example_order_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid       BIGINT,
    item_code       VARCHAR(64),
    order_id        BIGINT,
    product_name    VARCHAR(255),
    quantity        INT DEFAULT 1,
    unit_price      DECIMAL(10,2),
    version         INT DEFAULT 0,
    data_modify_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 种子数据：租户 1（源）有 2 个订单 + 子项
INSERT INTO example_order (tenantsid, order_code, order_name, total_amount, status) VALUES
(1, 'ORD-001', '测试订单A', 298.00, 'CONFIRMED'),
(1, 'ORD-002', '测试订单B', 499.00, 'CONFIRMED');

INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(1, 'ITEM-001', (SELECT id FROM example_order WHERE tenantsid=1 AND order_code='ORD-001'), '标准套餐A', 2, 99.00);
INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(1, 'ITEM-002', (SELECT id FROM example_order WHERE tenantsid=1 AND order_code='ORD-001'), '高级套餐B', 1, 100.00);
INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(1, 'ITEM-003', (SELECT id FROM example_order WHERE tenantsid=1 AND order_code='ORD-002'), '企业套餐C', 1, 499.00);

-- 种子数据：租户 2（目标）只有 1 个订单（部分差异）
INSERT INTO example_order (tenantsid, order_code, order_name, total_amount, status) VALUES
(2, 'ORD-001', '测试订单A-旧', 200.00, 'PENDING');

INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES
(2, 'ITEM-001', (SELECT id FROM example_order WHERE tenantsid=2 AND order_code='ORD-001'), '标准套餐A', 1, 99.00);

-- ============================================================
-- 第3层子表 (ExampleOrderItemDetail)
-- 用于验证3层级联 Apply/Rollback 的 FK 约束和执行顺序
-- ============================================================

CREATE TABLE IF NOT EXISTS example_order_item_detail (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid       BIGINT,
    detail_code     VARCHAR(64),
    order_item_id   BIGINT,
    detail_name     VARCHAR(255),
    detail_value    VARCHAR(255),
    version         INT DEFAULT 0,
    data_modify_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 种子数据：租户 1（源）有明细数据，租户 2 没有（A_TO_B 会产生 INSERT）
INSERT INTO example_order_item_detail (tenantsid, detail_code, order_item_id, detail_name, detail_value) VALUES
(1, 'DTL-001', (SELECT id FROM example_order_item WHERE tenantsid=1 AND item_code='ITEM-003'), '规格', '企业版');
INSERT INTO example_order_item_detail (tenantsid, detail_code, order_item_id, detail_name, detail_value) VALUES
(1, 'DTL-002', (SELECT id FROM example_order_item WHERE tenantsid=1 AND item_code='ITEM-003'), '有效期', '365天');
