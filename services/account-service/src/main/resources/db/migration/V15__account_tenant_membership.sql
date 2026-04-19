CREATE TABLE account_tenant_membership (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    tenant_id BIGINT NOT NULL,
    gameplay_admission_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (account_id, tenant_id)
);

INSERT INTO account_tenant_membership (account_id, tenant_id, gameplay_admission_allowed)
SELECT id, tenant_id, TRUE
FROM accounts
ON CONFLICT (account_id, tenant_id) DO NOTHING;

CREATE INDEX idx_account_tenant_membership_account_id ON account_tenant_membership(account_id);
CREATE INDEX idx_account_tenant_membership_tenant_id ON account_tenant_membership(tenant_id);
