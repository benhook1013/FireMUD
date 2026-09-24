-- The legacy tenant column is retained as source evidence. New global accounts
-- must not silently acquire tenant 1 through the V3 default.
CREATE TABLE account_legacy_tenant_sources (
    account_id BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    legacy_tenant_id BIGINT NOT NULL,
    matching_membership_id BIGINT,
    matching_membership_admission_allowed BOOLEAN,
    profile_tenant_count BIGINT NOT NULL,
    matching_profile_count BIGINT NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_legacy_tenant_sources_disposition_check
        CHECK (disposition IN ('UNVERIFIED', 'TENANT_MISMATCH', 'CONFLICT'))
);

INSERT INTO account_legacy_tenant_sources (
    account_id,
    legacy_tenant_id,
    matching_membership_id,
    matching_membership_admission_allowed,
    profile_tenant_count,
    matching_profile_count,
    disposition
)
SELECT a.id,
       a.tenant_id,
       m.id,
       m.gameplay_admission_allowed,
       (SELECT COUNT(*) FROM profiles p WHERE p.account_id = a.id),
       (SELECT COUNT(*) FROM profiles p WHERE p.account_id = a.id AND p.tenant_id = a.tenant_id),
       CASE
           WHEN EXISTS (
               SELECT 1 FROM account_tenant_membership other
               WHERE other.account_id = a.id AND other.tenant_id <> a.tenant_id
           ) OR EXISTS (
               SELECT 1 FROM profiles p
               WHERE p.account_id = a.id AND p.tenant_id <> a.tenant_id
           ) THEN 'TENANT_MISMATCH'
           WHEN m.id IS NULL THEN 'CONFLICT'
           ELSE 'UNVERIFIED'
       END
FROM accounts a
LEFT JOIN account_tenant_membership m
    ON m.account_id = a.id AND m.tenant_id = a.tenant_id;

CREATE TABLE account_legacy_membership_sources (
    membership_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    original_gameplay_admission_allowed BOOLEAN NOT NULL,
    matches_account_legacy_tenant BOOLEAN NOT NULL,
    disposition VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_legacy_membership_disposition_check
        CHECK (disposition IN ('UNVERIFIED', 'VERIFIED', 'QUARANTINED'))
);

INSERT INTO account_legacy_membership_sources (
    membership_id, account_id, tenant_id, original_gameplay_admission_allowed,
    matches_account_legacy_tenant
)
SELECT m.id, m.account_id, m.tenant_id, m.gameplay_admission_allowed,
       m.tenant_id = a.tenant_id
FROM account_tenant_membership m
JOIN accounts a ON a.id = m.account_id;

CREATE TABLE account_legacy_profile_sources (
    profile_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    matches_account_legacy_tenant BOOLEAN NOT NULL,
    disposition VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_legacy_profile_disposition_check
        CHECK (disposition IN ('UNVERIFIED', 'VERIFIED', 'QUARANTINED'))
);

INSERT INTO account_legacy_profile_sources (
    profile_id, account_id, tenant_id, matches_account_legacy_tenant
)
SELECT p.id, p.account_id, p.tenant_id, p.tenant_id = a.tenant_id
FROM profiles p
JOIN accounts a ON a.id = p.account_id;

ALTER TABLE accounts ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE accounts ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE accounts ALTER COLUMN role DROP DEFAULT;
ALTER TABLE accounts ALTER COLUMN role DROP NOT NULL;

-- A subscription row ID is not a policy revision. Retained policy begins at
-- version one and supported writers advance the monotonic value.
ALTER TABLE subscription ADD COLUMN entitlement_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE subscription ADD CONSTRAINT subscription_entitlement_version_positive
    CHECK (entitlement_version > 0);

-- No pre-JOIN writer proves player intent. Retain every old membership row,
-- but quarantine its authority until an explicit reconciliation disposition.
ALTER TABLE account_tenant_membership
    ADD COLUMN lifecycle_state VARCHAR(32),
    ADD COLUMN membership_version BIGINT,
    ADD COLUMN membership_authority_generation BIGINT,
    ADD COLUMN authority_provenance VARCHAR(32);

UPDATE account_tenant_membership
SET lifecycle_state = 'LEGACY_UNVERIFIED',
    membership_version = 1,
    membership_authority_generation = 1,
    authority_provenance = 'LEGACY_UNVERIFIED',
    gameplay_admission_allowed = FALSE;

ALTER TABLE account_tenant_membership ALTER COLUMN lifecycle_state SET NOT NULL;
ALTER TABLE account_tenant_membership ALTER COLUMN membership_version SET NOT NULL;
ALTER TABLE account_tenant_membership ALTER COLUMN membership_authority_generation SET NOT NULL;
ALTER TABLE account_tenant_membership ALTER COLUMN authority_provenance SET NOT NULL;
ALTER TABLE account_tenant_membership ADD CONSTRAINT account_membership_lifecycle_check
    CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE', 'LEGACY_UNVERIFIED'));
ALTER TABLE account_tenant_membership ADD CONSTRAINT account_membership_provenance_check
    CHECK (authority_provenance IN ('EXPLICIT_JOIN', 'LEGACY_UNVERIFIED', 'SEEDED_DEMO'));
ALTER TABLE account_tenant_membership ADD CONSTRAINT account_membership_versions_positive_check
    CHECK (membership_version > 0 AND membership_authority_generation > 0);
ALTER TABLE account_tenant_membership ADD CONSTRAINT account_membership_unverified_not_admitting_check
    CHECK (lifecycle_state <> 'LEGACY_UNVERIFIED' OR gameplay_admission_allowed = FALSE);

CREATE TABLE account_connect_scope_records (
    scope_token_hash VARCHAR(71) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    target_class VARCHAR(20) NOT NULL,
    tenant_id BIGINT NOT NULL,
    realm_id BIGINT NOT NULL,
    world_slug VARCHAR(128) NOT NULL,
    realm_slug VARCHAR(128) NOT NULL,
    playable_state_namespace_id VARCHAR(128) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL,
    game_instance_id BIGINT NOT NULL,
    catalog_revision BIGINT NOT NULL,
    pointer_version BIGINT NOT NULL,
    evaluated_at VARCHAR(40) NOT NULL,
    connect_scope_expires_at VARCHAR(40) NOT NULL,
    playtest_lifecycle_id VARCHAR(128),
    playtest_state_generation BIGINT,
    snapshot_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_connect_scope_target_check
        CHECK ((target_class = 'PUBLIC_PRODUCTION'
                AND playtest_lifecycle_id IS NULL AND playtest_state_generation IS NULL)
            OR (target_class = 'NON_PUBLIC'
                AND playtest_lifecycle_id IS NOT NULL AND playtest_state_generation > 0)),
    CONSTRAINT account_connect_scope_positive_check
        CHECK (tenant_id > 0 AND realm_id > 0 AND game_instance_id > 0
            AND catalog_revision > 0 AND pointer_version > 0)
);

CREATE INDEX idx_account_connect_scope_account_expiry
    ON account_connect_scope_records(account_id, connect_scope_expires_at);

CREATE TABLE account_join_operations (
    request_id VARCHAR(128) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    tenant_id BIGINT NOT NULL,
    verified_caller_binding VARCHAR(128) NOT NULL,
    scope_token_hash VARCHAR(71) NOT NULL,
    connect_scope_digest VARCHAR(71) NOT NULL,
    world_slug VARCHAR(128) NOT NULL,
    realm_slug VARCHAR(128) NOT NULL,
    realm_id BIGINT NOT NULL,
    playable_state_namespace_id VARCHAR(128) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL,
    game_instance_id BIGINT NOT NULL,
    catalog_revision BIGINT NOT NULL,
    pointer_version BIGINT NOT NULL,
    intent_digest_version INTEGER NOT NULL,
    intent_digest VARCHAR(71) NOT NULL,
    entitlement_version BIGINT,
    allow_public_join BOOLEAN,
    entitlement_authority_availability VARCHAR(16) NOT NULL DEFAULT 'NOT_EVALUATED',
    caller_bound_authority_invalidated BOOLEAN NOT NULL,
    request_digest_version INTEGER,
    request_digest VARCHAR(71),
    last_attempt_failure_code VARCHAR(64),
    last_attempt_authority_availability VARCHAR(16) NOT NULL DEFAULT 'NOT_EVALUATED',
    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(64),
    membership_id BIGINT REFERENCES account_tenant_membership(id),
    outcome_membership_version BIGINT,
    outcome_membership_authority_generation BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_join_operation_status_check
        CHECK (status IN ('PENDING', 'COMMITTED', 'FAILED')),
    CONSTRAINT account_join_operation_positive_versions_check
        CHECK (tenant_id > 0 AND realm_id > 0 AND game_instance_id > 0 AND catalog_revision > 0
            AND pointer_version > 0
            AND (entitlement_version IS NULL OR entitlement_version > 0)),
    CONSTRAINT account_join_operation_policy_evidence_check
        CHECK ((entitlement_authority_availability = 'AVAILABLE'
                AND entitlement_version IS NOT NULL AND allow_public_join IS NOT NULL)
            OR (entitlement_authority_availability IN ('UNAVAILABLE', 'NOT_EVALUATED')
                AND entitlement_version IS NULL AND allow_public_join IS NULL)),
    CONSTRAINT account_join_last_attempt_availability_check
        CHECK (last_attempt_authority_availability IN ('AVAILABLE', 'UNAVAILABLE', 'NOT_EVALUATED')),
    CONSTRAINT account_join_intent_digest_version_check
        CHECK (intent_digest_version = 1 AND length(intent_digest) = 71
            AND intent_digest LIKE 'sha256:%'),
    CONSTRAINT account_join_policy_digest_check
        CHECK ((request_digest_version IS NULL AND request_digest IS NULL
                    AND entitlement_authority_availability <> 'AVAILABLE'
                    AND entitlement_version IS NULL AND allow_public_join IS NULL)
            OR (request_digest_version = 1 AND request_digest IS NOT NULL
                AND length(request_digest) = 71 AND request_digest LIKE 'sha256:%'
                AND entitlement_authority_availability = 'AVAILABLE'
                AND entitlement_version IS NOT NULL AND allow_public_join IS NOT NULL)),
    CONSTRAINT account_join_outcome_state_check
        CHECK ((status = 'PENDING' AND outcome IS NULL
                    AND membership_id IS NULL AND outcome_membership_version IS NULL
                    AND outcome_membership_authority_generation IS NULL)
            OR (status = 'COMMITTED' AND outcome IS NOT NULL
                    AND membership_id IS NOT NULL
                    AND outcome_membership_version IS NOT NULL
                    AND outcome_membership_version > 0
                    AND outcome_membership_authority_generation IS NOT NULL
                    AND outcome_membership_authority_generation > 0)
            OR (status = 'FAILED' AND outcome IS NOT NULL
                    AND membership_id IS NULL AND outcome_membership_version IS NULL
                    AND outcome_membership_authority_generation IS NULL)),
    CONSTRAINT account_join_pending_attempt_failure_check
        CHECK (last_attempt_failure_code IS NULL
            OR (status = 'PENDING' AND btrim(last_attempt_failure_code) <> '')),
    CONSTRAINT account_join_committed_policy_check
        CHECK (status <> 'COMMITTED' OR (entitlement_version IS NOT NULL
            AND entitlement_version > 0 AND allow_public_join = TRUE
            AND membership_id IS NOT NULL AND outcome_membership_version IS NOT NULL
            AND outcome_membership_version > 0
            AND outcome_membership_authority_generation IS NOT NULL
            AND outcome_membership_authority_generation > 0))
);

CREATE INDEX idx_account_join_operations_account_tenant
    ON account_join_operations(account_id, tenant_id);

CREATE TABLE account_audit_outbox (
    audit_event_id UUID PRIMARY KEY,
    scope VARCHAR(8) NOT NULL,
    tenant_id BIGINT,
    producer_service VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_digest_version INTEGER NOT NULL,
    payload_digest VARCHAR(71) NOT NULL,
    payload TEXT NOT NULL,
    receiver_receipt_id VARCHAR(128),
    receiver_log_event_id VARCHAR(128),
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_attempt_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_audit_outbox_scope_check
        CHECK ((scope = 'platform' AND tenant_id IS NULL)
            OR (scope = 'tenant' AND tenant_id > 0)),
    CONSTRAINT account_audit_outbox_version_check
        CHECK (schema_version > 0 AND payload_digest_version = 1),
    CONSTRAINT account_audit_outbox_delivery_check
        CHECK (delivery_status IN ('PENDING', 'COMMITTED', 'MINIMIZED'))
);

CREATE INDEX idx_account_audit_outbox_pending
    ON account_audit_outbox(created_at, audit_event_id)
    WHERE delivery_status = 'PENDING';
