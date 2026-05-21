CREATE TABLE account_tenant_membership (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    tenant_id BIGINT NOT NULL,
    gameplay_admission_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (account_id, tenant_id)
);

INSERT INTO account_tenant_membership (account_id, tenant_id, gameplay_admission_allowed)
SELECT a.id, a.tenant_id, TRUE
FROM accounts a
WHERE NOT EXISTS (
    SELECT 1
    FROM account_tenant_membership m
    WHERE m.account_id = a.id
      AND m.tenant_id = a.tenant_id
);

CREATE INDEX idx_account_tenant_membership_account_id ON account_tenant_membership(account_id);
CREATE INDEX idx_account_tenant_membership_tenant_id ON account_tenant_membership(tenant_id);
