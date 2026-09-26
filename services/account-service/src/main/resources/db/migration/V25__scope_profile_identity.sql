-- Preflight retained profile data before enforcing the tenant-scoped identity.
-- Resolve retained duplicates without deleting or rewriting profile rows, then
-- rerun this migration.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM profiles
        WHERE tenant_id IS NULL OR account_id IS NULL
    )
    OR EXISTS (
        SELECT 1
        FROM profiles
        GROUP BY tenant_id, account_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'profiles_tenant_account_identity_collision';
    END IF;
END
$$;
-- [jooq ignore stop]

ALTER TABLE profiles
    ADD CONSTRAINT profiles_tenant_account_unique UNIQUE (tenant_id, account_id);
