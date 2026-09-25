CREATE TABLE account_audit_receipts (
    id BIGSERIAL PRIMARY KEY,
    receipt_id UUID NOT NULL UNIQUE,
    scope VARCHAR(16) NOT NULL,
    tenant_id BIGINT,
    tenant_key BIGINT NOT NULL,
    audit_event_id VARCHAR(36) NOT NULL,
    producer_service TEXT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at_seconds BIGINT NOT NULL,
    occurred_at_nanos INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_digest_version INTEGER NOT NULL,
    payload_digest TEXT NOT NULL,
    payload BYTEA,
    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_account_audit_receipt_scope
        CHECK (
            (scope = 'platform' AND tenant_id IS NULL AND tenant_key = 0)
            OR (
                scope = 'tenant'
                AND tenant_id IS NOT NULL
                AND tenant_id > 0
                AND tenant_key = tenant_id
            )
        ),
    CONSTRAINT chk_account_audit_receipt_occurred_at_nanos
        CHECK (occurred_at_nanos BETWEEN 0 AND 999999999),
    CONSTRAINT chk_account_audit_receipt_versions
        CHECK (schema_version > 0 AND payload_digest_version = 1),
    CONSTRAINT chk_account_audit_receipt_digest
        CHECK (payload_digest LIKE 'sha256:%' AND length(payload_digest) = 71),
    CONSTRAINT chk_account_audit_receipt_terminal_state
        CHECK (
            (status = 'COMMITTED' AND outcome IN ('ACCEPTED', 'DUPLICATE') AND payload IS NOT NULL)
            OR (status = 'MINIMIZED' AND outcome = 'NON_REPLAYABLE' AND payload IS NULL)
        )
);

CREATE UNIQUE INDEX uq_account_audit_receipt_identity
    ON account_audit_receipts (scope, tenant_key, audit_event_id);

CREATE INDEX idx_account_audit_receipts_tenant_event
    ON account_audit_receipts (tenant_id, audit_event_id)
    WHERE scope = 'tenant';
