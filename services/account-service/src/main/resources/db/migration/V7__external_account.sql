CREATE TABLE external_account (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    tenant_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    external_id VARCHAR(100) NOT NULL,
    UNIQUE(provider, external_id)
);
