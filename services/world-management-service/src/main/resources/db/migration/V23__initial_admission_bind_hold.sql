CREATE TABLE initial_admission_bind_hold (
    hold_id UUID PRIMARY KEY,
    hold_fence UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    realm_uuid UUID NOT NULL,
    playable_state_namespace_uuid UUID NOT NULL,
    playable_state_scope VARCHAR(16) NOT NULL
        CHECK (playable_state_scope IN ('SHARED', 'ISOLATED')),
    game_instance_id BIGINT NOT NULL CHECK (game_instance_id > 0),
    version_id BIGINT NOT NULL CHECK (version_id > 0),
    active_lifecycle_epoch BIGINT NOT NULL CHECK (active_lifecycle_epoch > 0),
    initial_admission_request_id VARCHAR(128) NOT NULL
        CHECK (BTRIM(initial_admission_request_id) <> ''),
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    expected_no_prior_pointer BOOLEAN NOT NULL CHECK (expected_no_prior_pointer),
    expected_catalog_revision BIGINT NOT NULL CHECK (expected_catalog_revision > 0),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PENDING', 'RECONCILIATION_REQUIRED', 'COMMITTED', 'ABORTED')),
    diagnostic_expires_at TIMESTAMP NOT NULL,
    owner_proof_id VARCHAR(128),
    owner_proof_digest CHAR(64),
    owner_pointer_audit_id VARCHAR(128),
    owner_pointer_version BIGINT,
    reconciliation_error VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminal_at TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_initial_admission_bind_request
        UNIQUE (tenant_id, initial_admission_request_id),
    CONSTRAINT fk_initial_admission_bind_world_instance
        FOREIGN KEY (tenant_id, game_instance_id)
        REFERENCES world_instance (tenant_id, game_instance_id),
    CONSTRAINT ck_initial_admission_bind_owner_proof
        CHECK (
            (status IN ('PENDING', 'RECONCILIATION_REQUIRED')
                AND owner_proof_id IS NULL
                AND owner_proof_digest IS NULL
                AND owner_pointer_audit_id IS NULL
                AND owner_pointer_version IS NULL
                AND terminal_at IS NULL)
            OR (status = 'COMMITTED'
                AND owner_proof_id IS NOT NULL
                AND BTRIM(owner_proof_id) <> ''
                AND owner_proof_digest IS NOT NULL
                AND owner_proof_digest ~ '^[0-9a-f]{64}$'
                AND owner_pointer_audit_id IS NOT NULL
                AND BTRIM(owner_pointer_audit_id) <> ''
                AND owner_pointer_version IS NOT NULL
                AND owner_pointer_version > 0
                AND terminal_at IS NOT NULL)
            OR (status = 'ABORTED'
                AND owner_proof_id IS NOT NULL
                AND BTRIM(owner_proof_id) <> ''
                AND owner_proof_digest IS NOT NULL
                AND owner_proof_digest ~ '^[0-9a-f]{64}$'
                AND owner_pointer_audit_id IS NULL
                AND owner_pointer_version IS NULL
                AND terminal_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uk_initial_admission_bind_active_realm
    ON initial_admission_bind_hold (tenant_id, realm_uuid)
    WHERE status IN ('PENDING', 'RECONCILIATION_REQUIRED');

CREATE INDEX idx_initial_admission_bind_reconcile
    ON initial_admission_bind_hold (updated_at, hold_id)
    WHERE status IN ('PENDING', 'RECONCILIATION_REQUIRED');
