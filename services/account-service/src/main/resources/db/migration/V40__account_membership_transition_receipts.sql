-- Provisional transition receipts retain exact Account JOIN/reactivation history without
-- claiming the full authorityTuple or issuanceFence required by the canonical authority outbox.
-- Their distinct stream key cannot consume canonical account:auth-authority:v1 sequence values.
CREATE TABLE account_membership_transition_receipt_stream_heads (
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    receipt_stream_key VARCHAR(256) NOT NULL,
    last_receipt_sequence BIGINT NOT NULL CHECK (last_receipt_sequence > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_membership_transition_receipt_stream_heads_pk
        PRIMARY KEY (account_id, tenant_id),
    CONSTRAINT account_membership_transition_receipt_stream_heads_key_unique
        UNIQUE (receipt_stream_key),
    CONSTRAINT account_membership_transition_receipt_stream_heads_key_check
        CHECK (receipt_stream_key =
            'account:membership-transition-receipt:v1:membership/'
                || account_id::TEXT || '/' || tenant_id::TEXT)
);

CREATE TABLE account_membership_transition_receipts (
    receipt_stream_key VARCHAR(256) NOT NULL,
    receipt_sequence BIGINT NOT NULL CHECK (receipt_sequence > 0),
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    evidence_status VARCHAR(48) NOT NULL,
    transition_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    membership_id BIGINT NOT NULL CHECK (membership_id > 0),
    membership_lifecycle_state VARCHAR(32) NOT NULL,
    gameplay_admission_allowed BOOLEAN NOT NULL,
    membership_version BIGINT NOT NULL CHECK (membership_version > 0),
    membership_authority_generation BIGINT NOT NULL CHECK (membership_authority_generation > 0),
    authority_provenance VARCHAR(32) NOT NULL,
    receipt_id UUID NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_membership_transition_receipts_pk
        PRIMARY KEY (receipt_stream_key, receipt_sequence),
    CONSTRAINT account_membership_transition_receipts_id_unique
        UNIQUE (receipt_id),
    CONSTRAINT account_membership_transition_receipts_request_unique
        UNIQUE (request_id),
    CONSTRAINT account_membership_transition_receipts_stream_fk
        FOREIGN KEY (account_id, tenant_id)
        REFERENCES account_membership_transition_receipt_stream_heads (account_id, tenant_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_membership_transition_receipts_key_check
        CHECK (receipt_stream_key =
            'account:membership-transition-receipt:v1:membership/'
                || account_id::TEXT || '/' || tenant_id::TEXT),
    CONSTRAINT account_membership_transition_receipts_status_check
        CHECK (evidence_status = 'PROVISIONAL_TRANSITION_RECEIPT'),
    CONSTRAINT account_membership_transition_receipts_transition_check
        CHECK (transition_type IN ('MEMBERSHIP_JOINED', 'MEMBERSHIP_REACTIVATED')),
    CONSTRAINT account_membership_transition_receipts_state_check
        CHECK (membership_lifecycle_state = 'ACTIVE'
            AND gameplay_admission_allowed = TRUE
            AND authority_provenance = 'EXPLICIT_JOIN'),
    CONSTRAINT account_membership_transition_receipts_digest_check
        CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_account_membership_transition_receipts_latest
    ON account_membership_transition_receipts (account_id, tenant_id, receipt_sequence DESC);
