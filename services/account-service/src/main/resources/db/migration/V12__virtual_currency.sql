CREATE TABLE currency_balance (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    currency_code VARCHAR(20) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL,
    UNIQUE (tenant_id, account_id, currency_code)
);
