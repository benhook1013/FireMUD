ALTER TABLE account_audit_outbox
    ADD COLUMN tenant_identity_version INTEGER;

ALTER TABLE account_audit_outbox
    ADD COLUMN tenant_uuid UUID;

-- Retained rows are representation one exactly as stored; the new UUID field stays absent.
UPDATE account_audit_outbox
SET tenant_identity_version = 1;

ALTER TABLE account_audit_outbox
    ALTER COLUMN tenant_identity_version SET NOT NULL;

ALTER TABLE account_audit_outbox
    DROP CONSTRAINT account_audit_outbox_scope_check;

ALTER TABLE account_audit_outbox
    ADD CONSTRAINT account_audit_outbox_scope_identity_check
        CHECK (
            (scope = 'platform'
                AND tenant_identity_version = 1
                AND tenant_id IS NULL
                AND tenant_uuid IS NULL)
            OR (scope = 'tenant'
                AND tenant_identity_version = 1
                AND tenant_id IS NOT NULL
                AND tenant_id > 0
                AND tenant_uuid IS NULL)
            OR (scope = 'tenant'
                AND tenant_identity_version = 2
                AND tenant_id IS NULL
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::uuid)
        );
