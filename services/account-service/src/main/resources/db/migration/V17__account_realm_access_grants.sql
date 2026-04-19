CREATE TABLE IF NOT EXISTS account_realm_access_grant (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    grant_version BIGINT NOT NULL,
    granted_by VARCHAR(200) NOT NULL,
    grant_reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_account_realm_access_grant_account_realm
        UNIQUE (account_id, tenant_id, world_slug, realm_slug)
);

CREATE INDEX IF NOT EXISTS idx_account_realm_access_grant_account_tenant
    ON account_realm_access_grant(account_id, tenant_id);
