ALTER TABLE log_events
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE log_events
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'tenant',
    ADD COLUMN audit_event_id VARCHAR(36),
    ADD COLUMN tenant_key BIGINT NOT NULL DEFAULT 0;

ALTER TABLE log_events
    ADD CONSTRAINT chk_log_events_scope_tenant
        CHECK (
            (scope = 'platform' AND tenant_id IS NULL AND tenant_key = 0)
            OR (
                scope = 'tenant'
                AND tenant_id IS NOT NULL
                AND (
                    (audit_event_id IS NOT NULL AND tenant_key = tenant_id)
                    OR (audit_event_id IS NULL AND tenant_key IN (0, tenant_id))
                )
            )
        );

CREATE UNIQUE INDEX uq_log_events_account_audit_identity
    ON log_events (scope, tenant_key, audit_event_id);

ALTER TABLE account_audit_receipts
    ADD COLUMN log_event_id BIGINT;

INSERT INTO log_events (
    scope,
    tenant_id,
    tenant_key,
    audit_event_id,
    type,
    message,
    timestamp,
    account_id
)
SELECT receipt.scope,
       receipt.tenant_id,
       receipt.tenant_key,
       receipt.audit_event_id,
       'ACCOUNT_AUDIT',
       'Account audit event ' || receipt.audit_event_id,
       TIMESTAMP '1970-01-01 00:00:00'
           + receipt.occurred_at_seconds * INTERVAL '1' SECOND
           + (receipt.occurred_at_nanos / 1000) * INTERVAL '0.000001' SECOND,
       NULL
FROM account_audit_receipts AS receipt;

UPDATE account_audit_receipts AS receipt
SET log_event_id = (
    SELECT event.id
    FROM log_events AS event
    WHERE event.scope = receipt.scope
      AND event.tenant_key = receipt.tenant_key
      AND event.audit_event_id = receipt.audit_event_id
);

ALTER TABLE account_audit_receipts
    ALTER COLUMN log_event_id SET NOT NULL;

ALTER TABLE account_audit_receipts
    ADD CONSTRAINT uq_account_audit_receipts_log_event_id UNIQUE (log_event_id);

ALTER TABLE account_audit_receipts
    ADD CONSTRAINT fk_account_audit_receipts_log_event_id
        FOREIGN KEY (log_event_id) REFERENCES log_events (id);
