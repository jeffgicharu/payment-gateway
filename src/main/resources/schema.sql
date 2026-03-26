-- Payment Gateway Schema

CREATE TABLE IF NOT EXISTS merchants (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    api_key VARCHAR(64) NOT NULL UNIQUE,
    api_secret VARCHAR(128) NOT NULL,
    callback_url VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    daily_limit DECIMAL(15,2) NOT NULL DEFAULT 1000000.00,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id VARCHAR(30) NOT NULL UNIQUE,
    merchant_id VARCHAR(30) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'KES',
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    source_account VARCHAR(50),
    destination_account VARCHAR(50),
    reference VARCHAR(100),
    description VARCHAR(255),
    correlation_id VARCHAR(50),
    error_code VARCHAR(20),
    error_message VARCHAR(255),
    metadata TEXT,
    initiated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    settled_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS settlement_batches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id VARCHAR(30) NOT NULL UNIQUE,
    merchant_id VARCHAR(30) NOT NULL,
    transaction_count INT NOT NULL,
    total_amount DECIMAL(15,2) NOT NULL,
    total_fees DECIMAL(15,2) NOT NULL DEFAULT 0,
    net_amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id VARCHAR(30) NOT NULL,
    merchant_id VARCHAR(30) NOT NULL,
    url VARCHAR(500) NOT NULL,
    payload TEXT NOT NULL,
    http_status INT,
    response_body TEXT,
    attempt INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL,
    entity_id VARCHAR(50) NOT NULL,
    action VARCHAR(30) NOT NULL,
    actor VARCHAR(50),
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_txn_merchant ON payment_transactions(merchant_id);
CREATE INDEX IF NOT EXISTS idx_txn_status ON payment_transactions(status);
CREATE INDEX IF NOT EXISTS idx_txn_correlation ON payment_transactions(correlation_id);
CREATE INDEX IF NOT EXISTS idx_txn_initiated ON payment_transactions(initiated_at);
CREATE INDEX IF NOT EXISTS idx_webhook_txn ON webhook_deliveries(transaction_id);
CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);

-- Seed test merchant
INSERT INTO merchants (merchant_id, name, api_key, api_secret, callback_url, daily_limit)
VALUES ('MCH-TESTSHOP', 'Test Shop Kenya', 'tk_live_a1b2c3d4e5f6g7h8i9j0', 'sk_live_secret_key_for_hmac_signing_do_not_share', 'https://webhook.site/test', 5000000.00);
