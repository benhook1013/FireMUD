-- UpdateScript identifies one definition by (tenant, patch version, script name).
-- Do not discard retained definitions to manufacture a winner: PostgreSQL rejects
-- this migration when an existing duplicate is present, leaving retained data
-- unchanged for explicit operator/data reconciliation.

-- Detect retained duplicates before attempting the constraint so the owner-specific
-- migration failure is explicit and no partial uniqueness change is attempted.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM scripts
        GROUP BY tenant_id, version, name
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V8 cannot install scripts definition identity: retained duplicate rows exist';
    END IF;
END $$;
-- [jooq ignore stop]

ALTER TABLE scripts
    ADD CONSTRAINT uq_scripts_definition_identity UNIQUE (tenant_id, version, name);
