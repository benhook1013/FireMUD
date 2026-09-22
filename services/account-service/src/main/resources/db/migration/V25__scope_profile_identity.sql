-- Preflight retained profile data before enforcing the tenant-scoped identity.
-- The primary key deliberately rejects duplicate (tenant_id, account_id) rows.
-- Resolve retained duplicates without deleting or rewriting profile rows, then
-- rerun this migration.
CREATE TABLE profile_identity_preflight (
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    CONSTRAINT profiles_tenant_account_identity_collision PRIMARY KEY (tenant_id, account_id)
);

INSERT INTO profile_identity_preflight (tenant_id, account_id)
SELECT tenant_id, account_id
FROM profiles;

DROP TABLE profile_identity_preflight;

ALTER TABLE profiles
    ADD CONSTRAINT profiles_tenant_account_unique UNIQUE (tenant_id, account_id);
