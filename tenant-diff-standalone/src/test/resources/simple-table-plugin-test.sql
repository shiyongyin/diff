CREATE TABLE IF NOT EXISTS test_product (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenantsid       BIGINT,
    product_code    VARCHAR(64),
    product_name    VARCHAR(255),
    price           DECIMAL(10,2),
    internal_remark VARCHAR(255),
    version         INT DEFAULT 0,
    data_modify_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO test_product (tenantsid, product_code, product_name, price, internal_remark) VALUES
(1, 'P-001', 'Standard A', 99.00, 'internal-1'),
(1, 'P-002', 'Premium B', 199.00, 'internal-2'),
(1, 'P-003', 'Enterprise C', 499.00, 'internal-3');

INSERT INTO test_product (tenantsid, product_code, product_name, price, internal_remark) VALUES
(2, 'P-001', 'Standard A', 99.00, 'internal-1'),
(2, 'P-002', 'Premium B-Modified', 249.00, 'internal-2');
