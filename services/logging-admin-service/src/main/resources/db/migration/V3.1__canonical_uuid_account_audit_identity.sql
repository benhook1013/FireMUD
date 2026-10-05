ALTER TABLE log_events
    ADD COLUMN tenant_identity_version INTEGER;

ALTER TABLE log_events
    ADD COLUMN tenant_uuid UUID;

UPDATE log_events
SET tenant_identity_version = 1
WHERE audit_event_id IS NOT NULL;

ALTER TABLE log_events
    ALTER COLUMN tenant_key DROP NOT NULL;

ALTER TABLE log_events
    DROP CONSTRAINT chk_log_events_scope_tenant;

ALTER TABLE log_events
    ADD CONSTRAINT chk_log_events_scope_tenant
        CHECK (
            (
                audit_event_id IS NULL
                AND tenant_identity_version IS NULL
                AND tenant_uuid IS NULL
                AND (
                    (
                        scope = 'platform'
                        AND tenant_id IS NULL
                        AND tenant_key IS NOT NULL
                        AND tenant_key = 0
                    )
                    OR (
                        scope = 'tenant'
                        AND tenant_id IS NOT NULL
                        AND tenant_key IS NOT NULL
                        AND tenant_key IN (0, tenant_id)
                    )
                )
            )
            OR (
                audit_event_id IS NOT NULL
                AND tenant_identity_version IS NOT NULL
                AND (
                    (
                        tenant_identity_version = 1
                        AND tenant_uuid IS NULL
                        AND (
                            (
                                scope = 'platform'
                                AND tenant_id IS NULL
                                AND tenant_key IS NOT NULL
                                AND tenant_key = 0
                            )
                            OR (
                                scope = 'tenant'
                                AND tenant_id IS NOT NULL
                                AND tenant_id > 0
                                AND tenant_key IS NOT NULL
                                AND tenant_key = tenant_id
                            )
                        )
                    )
                    OR (
                        tenant_identity_version = 2
                        AND scope = 'tenant'
                        AND tenant_id IS NULL
                        AND tenant_key IS NULL
                        AND tenant_uuid IS NOT NULL
                        AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                    )
                )
            )
        ) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;

ALTER INDEX uq_log_events_account_audit_identity
    RENAME TO uq_log_events_account_audit_v1_identity;

CREATE UNIQUE INDEX uq_log_events_account_audit_v2_identity
    ON log_events (scope, tenant_uuid, audit_event_id)
    WHERE audit_event_id IS NOT NULL AND tenant_identity_version = 2;

ALTER TABLE account_audit_receipts
    ADD COLUMN tenant_identity_version INTEGER;

ALTER TABLE account_audit_receipts
    ADD COLUMN tenant_uuid UUID;

UPDATE account_audit_receipts
SET tenant_identity_version = 1;

ALTER TABLE account_audit_receipts
    ALTER COLUMN tenant_key DROP NOT NULL;

ALTER TABLE account_audit_receipts
    DROP CONSTRAINT chk_account_audit_receipt_scope;

ALTER TABLE account_audit_receipts
    ALTER COLUMN tenant_identity_version SET NOT NULL;

ALTER TABLE account_audit_receipts
    ADD CONSTRAINT chk_account_audit_receipt_scope
        CHECK (
            (
                tenant_identity_version = 1
                AND tenant_uuid IS NULL
                AND (
                    (
                        scope = 'platform'
                        AND tenant_id IS NULL
                        AND tenant_key IS NOT NULL
                        AND tenant_key = 0
                    )
                    OR (
                        scope = 'tenant'
                        AND tenant_id IS NOT NULL
                        AND tenant_id > 0
                        AND tenant_key IS NOT NULL
                        AND tenant_key = tenant_id
                    )
                )
            )
            OR (
                tenant_identity_version = 2
                AND scope = 'tenant'
                AND tenant_id IS NULL
                AND tenant_key IS NULL
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            )
        ) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;

ALTER INDEX uq_account_audit_receipt_identity
    RENAME TO uq_account_audit_receipt_v1_identity;

CREATE UNIQUE INDEX uq_account_audit_receipt_v2_identity
    ON account_audit_receipts (scope, tenant_uuid, audit_event_id)
    WHERE tenant_identity_version = 2;

CREATE INDEX idx_account_audit_receipts_tenant_uuid_event
    ON account_audit_receipts (tenant_uuid, audit_event_id)
    WHERE tenant_identity_version = 2;
