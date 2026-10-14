-- Retain complete Account source-protection evidence on the existing V28 terminal owner row.
-- Historical V28 rows remain nullable and are not backfilled or adopted as original attempts.
-- V35's original-attempt relation is intentionally absent from the jOOQ DDL simulator.
-- Keep only its cross-relation constraints out of simulation; all V28 columns stay visible.
-- [jooq ignore start]
ALTER TABLE game_session_start_session_operator_attempt
    ADD CONSTRAINT uq_gs_start_session_operator_namespace_attempt
        UNIQUE (target_namespace, owner_attempt_id);
-- [jooq ignore stop]

ALTER TABLE game_session_canonical_initial_admission_intent
    ADD COLUMN catalog_source_intake_operation_id UUID,
    ADD COLUMN catalog_source_intake_request_id UUID,
    ADD COLUMN original_start_session_tuple BYTEA,
    ADD CONSTRAINT chk_gs_initial_admission_intent_v44_launch_evidence
        CHECK ((catalog_source_intake_operation_id IS NULL
                AND catalog_source_intake_request_id IS NULL
                AND original_start_session_tuple IS NULL)
            OR (catalog_source_intake_operation_id IS NOT NULL
                AND catalog_source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND catalog_source_intake_request_id IS NOT NULL
                AND catalog_source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND original_start_session_tuple IS NOT NULL
                AND octet_length(original_start_session_tuple) BETWEEN 1 AND 262144));

-- V28's procedural owner-ledger creation is also absent from simulated metadata.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_initial_admission_attempt
    ADD COLUMN account_protection_evidence_version INTEGER,
    ADD COLUMN account_protection_id UUID,
    ADD COLUMN account_protection_fence BIGINT,
    ADD COLUMN original_owner_attempt_id UUID,
    ADD COLUMN original_owner_mutation_id UUID,
    ADD COLUMN original_owner_fence BIGINT,
    ADD COLUMN account_protection_evidence_bytes BYTEA,
    ADD CONSTRAINT chk_gs_initial_admission_account_protection_evidence_complete
        CHECK (
            (account_protection_evidence_version IS NULL
                AND account_protection_id IS NULL
                AND account_protection_fence IS NULL
                AND original_owner_attempt_id IS NULL
                AND original_owner_mutation_id IS NULL
                AND original_owner_fence IS NULL
                AND account_protection_evidence_bytes IS NULL)
            OR (account_protection_evidence_version IS NOT NULL
                AND account_protection_evidence_version = 1
                AND account_protection_id IS NOT NULL
                AND account_protection_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND account_protection_fence IS NOT NULL
                AND account_protection_fence > 0
                AND original_owner_attempt_id IS NOT NULL
                AND original_owner_mutation_id IS NOT NULL
                AND original_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND original_owner_fence IS NOT NULL
                AND original_owner_fence > 0
                AND account_protection_evidence_bytes IS NOT NULL
                AND octet_length(account_protection_evidence_bytes) BETWEEN 1 AND 8388608)),
    -- Enforce fresh terminal writes without reclassifying immutable pre1006 history.
    ADD CONSTRAINT chk_gs_initial_admission_terminal_requires_protection
        CHECK (status = 'PENDING'
            OR (account_protection_evidence_version IS NOT NULL
                AND account_protection_evidence_version = 1)) NOT VALID;

CREATE UNIQUE INDEX uq_gs_initial_admission_protected_original_attempt
    ON game_session_canonical_initial_admission_attempt (target_namespace, original_owner_attempt_id)
    WHERE original_owner_attempt_id IS NOT NULL;
CREATE UNIQUE INDEX uq_gs_initial_admission_account_protection
    ON game_session_canonical_initial_admission_attempt (target_namespace, account_protection_id)
    WHERE account_protection_id IS NOT NULL;

ALTER TABLE game_session_canonical_initial_admission_attempt
    ADD CONSTRAINT fk_gs_initial_admission_original_owner
        FOREIGN KEY (target_namespace, original_owner_attempt_id)
        REFERENCES game_session_start_session_operator_attempt (target_namespace, owner_attempt_id);

CREATE FUNCTION require_game_session_canonical_initial_admission_account_terminal_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    selected_intent game_session_canonical_initial_admission_intent%ROWTYPE;
    selected_launch game_session_canonical_instance_launch%ROWTYPE;
    selected_catalog game_session_canonical_realm_catalog%ROWTYPE;
    selected_original game_session_start_session_operator_attempt%ROWTYPE;
    evidence_json JSONB;
    evidence_request_bytes BYTEA;
    evidence_request JSONB;
    evidence_tuple_bytes BYTEA;
    evidence_projection_bytes BYTEA;
    evidence_hold_bytes BYTEA;
    hold_json JSONB;
    hold_request_json JSONB;
    expected_request_digest TEXT;
    digest_bytes BYTEA;
    domain_bytes BYTEA;
    empty_bytes BYTEA := ''::BYTEA;
    field_bytes BYTEA;
    terminal_at_text TEXT;
BEGIN
    IF TG_TABLE_NAME = 'game_session_canonical_initial_admission_intent' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Canonical initial-admission intents are durable survivor evidence'
                USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'INSERT' THEN
            IF NEW.state <> 'PENDING_HOLD'
               OR NEW.hold_identity_bytes IS NOT NULL
               OR NEW.terminal_at IS NOT NULL THEN
                RAISE EXCEPTION 'New canonical initial-admission intents must begin before World hold acquisition'
                    USING ERRCODE = '23514';
            END IF;
            SELECT * INTO selected_launch
            FROM game_session_canonical_instance_launch
            WHERE target_namespace = NEW.target_namespace
              AND control_plane_request_id = NEW.control_plane_request_id;
            SELECT * INTO selected_catalog
            FROM game_session_canonical_realm_catalog
            WHERE target_namespace = NEW.target_namespace
              AND canonical_tenant_id = NEW.canonical_tenant_id
              AND realm_id = NEW.realm_id;
            BEGIN
                hold_request_json := convert_from(NEW.canonical_hold_request_bytes, 'UTF8')::JSONB;
            EXCEPTION WHEN OTHERS THEN
                RAISE EXCEPTION 'New initial-admission intent request bytes are not structurally readable'
                    USING ERRCODE = '23514';
            END;
            IF hold_request_json ->> 'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-request/v1'
               OR jsonb_typeof(hold_request_json -> 'request') IS DISTINCT FROM 'object' THEN
                RAISE EXCEPTION 'New initial-admission intent request has an unsupported schema or shape'
                    USING ERRCODE = '23514';
            END IF;
            digest_bytes := ''::BYTEA;
            domain_bytes := convert_to('game-session-canonical-initial-admission/v1', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'targetNamespace', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'canonicalTenantId', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'worldSlug', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'realmId', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'playableStateNamespaceId', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'playableStateScope', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'canonicalGameInstanceId', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'canonicalVersionId', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'activeLifecycleEpoch', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'expectedCatalogRevision', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'initialAdmissionOrigin', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(COALESCE(hold_request_json -> 'request' ->> 'expectedPriorPointerVersion', ''), 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            domain_bytes := convert_to(hold_request_json -> 'request' ->> 'initialAdmissionRequestId', 'UTF8');
            digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
            expected_request_digest := encode(sha256(digest_bytes), 'hex');
            IF NEW.original_start_session_tuple IS NULL
               OR octet_length(NEW.original_start_session_tuple) NOT BETWEEN 1 AND 262144
               OR selected_launch.target_namespace IS NULL
               OR selected_catalog.target_namespace IS NULL
               OR selected_launch.original_start_session_tuple IS DISTINCT FROM NEW.original_start_session_tuple
               OR selected_launch.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
               OR selected_launch.canonical_realm_id IS DISTINCT FROM NEW.realm_id
               OR selected_launch.world_slug IS DISTINCT FROM NEW.world_slug
               OR selected_launch.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
               OR selected_launch.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
               OR selected_launch.game_instance_uuid IS DISTINCT FROM NEW.canonical_game_instance_id
               OR selected_launch.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
               OR selected_launch.game_instance_id IS DISTINCT FROM NEW.game_instance_id
               OR selected_launch.version_id IS DISTINCT FROM NEW.runtime_version_id
               OR selected_launch.tenant_association_operation_id IS DISTINCT FROM NEW.tenant_association_operation_id
               OR selected_launch.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
               OR selected_launch.captured_starting_row_version IS DISTINCT FROM NEW.captured_starting_row_version
               OR selected_launch.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'
                    IS DISTINCT FROM NEW.descriptor_request_digest
               OR selected_launch.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'
                    IS DISTINCT FROM NEW.descriptor_result_digest
               OR selected_launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest'
                    IS DISTINCT FROM NEW.release_attestation_digest
               OR selected_launch.catalog_creation_request_id IS DISTINCT FROM NEW.catalog_creation_request_id
               OR selected_launch.catalog_revision IS DISTINCT FROM NEW.catalog_revision
               OR selected_launch.catalog_source_intake_operation_id IS DISTINCT FROM NEW.catalog_source_intake_operation_id
               OR selected_launch.catalog_source_intake_request_id IS DISTINCT FROM NEW.catalog_source_intake_request_id
               OR selected_catalog.world_slug IS DISTINCT FROM NEW.world_slug
               OR selected_catalog.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
               OR selected_catalog.catalog_revision IS DISTINCT FROM NEW.catalog_revision
               OR selected_catalog.creation_request_id IS DISTINCT FROM NEW.catalog_creation_request_id
               OR selected_catalog.source_intake_operation_id IS DISTINCT FROM NEW.catalog_source_intake_operation_id
               OR selected_catalog.source_intake_request_id IS DISTINCT FROM NEW.catalog_source_intake_request_id
               OR selected_catalog.request_digest IS DISTINCT FROM NEW.catalog_request_digest
               OR selected_catalog.receipt_digest IS DISTINCT FROM NEW.catalog_receipt_digest
               OR selected_catalog.visible IS DISTINCT FROM TRUE
               OR selected_catalog.public_production IS DISTINCT FROM TRUE
               OR selected_catalog.state_scope IS DISTINCT FROM 'SHARED'
               OR hold_request_json -> 'request' ->> 'targetNamespace' IS DISTINCT FROM NEW.target_namespace
               OR hold_request_json -> 'request' ->> 'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
               OR hold_request_json -> 'request' ->> 'worldSlug' IS DISTINCT FROM NEW.world_slug
               OR hold_request_json -> 'request' ->> 'realmId' IS DISTINCT FROM NEW.realm_id::TEXT
               OR hold_request_json -> 'request' ->> 'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
               OR hold_request_json -> 'request' ->> 'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
               OR hold_request_json -> 'request' ->> 'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
               OR hold_request_json -> 'request' ->> 'canonicalVersionId'
                    IS DISTINCT FROM (NEW.canonical_version_id)::TEXT
               OR hold_request_json -> 'request' ->> 'initialAdmissionRequestId' IS DISTINCT FROM NEW.initial_admission_request_id
               OR hold_request_json -> 'request' ->> 'initialAdmissionRequestDigest' IS DISTINCT FROM NEW.request_digest
               OR hold_request_json -> 'request' ->> 'expectedCatalogRevision' IS DISTINCT FROM NEW.catalog_revision::TEXT
               OR (hold_request_json -> 'request' ->> 'initialAdmissionOrigin'
                    IS DISTINCT FROM 'NO_PRIOR_POINTER'
                   AND hold_request_json -> 'request' ->> 'initialAdmissionOrigin'
                    IS DISTINCT FROM 'EXPECT_CLOSED')
               OR (hold_request_json -> 'request' ->> 'initialAdmissionOrigin' = 'NO_PRIOR_POINTER'
                   AND hold_request_json -> 'request' ->> 'expectedPriorPointerVersion' IS NOT NULL)
               OR (hold_request_json -> 'request' ->> 'initialAdmissionOrigin' = 'EXPECT_CLOSED'
                   AND hold_request_json -> 'request' ->> 'expectedPriorPointerVersion' IS DISTINCT FROM '1')
               OR hold_request_json -> 'request' ->> 'activeLifecycleEpoch' IS NULL
               OR hold_request_json -> 'request' ->> 'activeLifecycleEpoch' !~ '^[1-9][0-9]{0,18}$'
               OR NEW.request_digest <> expected_request_digest
            THEN
                RAISE EXCEPTION 'New initial-admission intent must retain the exact V43 launch tuple and catalog binding'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
        IF (to_jsonb(NEW) - 'state' - 'updated_at' - 'terminal_at' - 'hold_identity_bytes')
            IS DISTINCT FROM
           (to_jsonb(OLD) - 'state' - 'updated_at' - 'terminal_at' - 'hold_identity_bytes') THEN
            RAISE EXCEPTION 'Canonical initial-admission intent identity is immutable'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.state = 'PENDING_HOLD'
           AND NEW.state = 'HOLD_ATTACHED'
           AND OLD.hold_identity_bytes IS NULL
           AND NEW.hold_identity_bytes IS NOT NULL
           AND NEW.terminal_at IS NULL THEN
            RETURN NEW;
        END IF;
        IF OLD.state = 'HOLD_ATTACHED'
           AND NEW.state = 'TERMINAL'
           AND OLD.hold_identity_bytes IS NOT NULL
           AND NEW.hold_identity_bytes = OLD.hold_identity_bytes
           AND NEW.terminal_at IS NOT NULL
           AND EXISTS (
               SELECT 1 FROM game_session_canonical_initial_admission_attempt attempt
               WHERE attempt.target_namespace = NEW.target_namespace
                 AND attempt.initial_admission_request_id = NEW.initial_admission_request_id
                 AND attempt.status IN ('COMMITTED', 'ABORTED')
                 AND attempt.account_protection_evidence_version = 1
                 AND attempt.account_protection_evidence_bytes IS NOT NULL
                 AND attempt.terminal_at = NEW.terminal_at)
        THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Canonical initial-admission intent permits only one exact hold attach and terminal transition'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Canonical initial-admission attempts cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' AND NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'New canonical initial-admission attempts must begin PENDING'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'PENDING' THEN
        IF NEW.account_protection_evidence_version IS NOT NULL
           OR NEW.account_protection_id IS NOT NULL
           OR NEW.account_protection_fence IS NOT NULL
           OR NEW.original_owner_attempt_id IS NOT NULL
           OR NEW.original_owner_mutation_id IS NOT NULL
           OR NEW.original_owner_fence IS NOT NULL
           OR NEW.account_protection_evidence_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'PENDING initial-admission attempts cannot carry terminal Account protection'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status NOT IN ('COMMITTED', 'ABORTED')
       OR NEW.account_protection_evidence_version IS DISTINCT FROM 1
       OR NEW.account_protection_id IS NULL
       OR NEW.account_protection_fence IS NULL
       OR NEW.account_protection_fence <= 0
       OR NEW.original_owner_attempt_id IS NULL
       OR NEW.original_owner_mutation_id IS NULL
       OR NEW.original_owner_fence IS NULL
       OR NEW.original_owner_fence <= 0
       OR NEW.account_protection_evidence_bytes IS NULL
       OR octet_length(NEW.account_protection_evidence_bytes) NOT BETWEEN 1 AND 8388608
    THEN
        RAISE EXCEPTION 'New canonical initial-admission terminal requires complete retained Account protection evidence'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_initial_admission_terminal_requires_protection';
    END IF;

    IF TG_OP = 'UPDATE'
       AND (OLD.status <> 'PENDING'
            OR OLD.account_protection_evidence_version IS NOT NULL
            OR OLD.account_protection_id IS NOT NULL
            OR OLD.account_protection_fence IS NOT NULL
            OR OLD.original_owner_attempt_id IS NOT NULL
            OR OLD.original_owner_mutation_id IS NOT NULL
            OR OLD.original_owner_fence IS NOT NULL
            OR OLD.account_protection_evidence_bytes IS NOT NULL)
    THEN
        RAISE EXCEPTION 'Terminal Account protection evidence is immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO selected_intent
    FROM game_session_canonical_initial_admission_intent
    WHERE target_namespace = NEW.target_namespace
      AND initial_admission_request_id = NEW.initial_admission_request_id;
    SELECT * INTO selected_launch
    FROM game_session_canonical_instance_launch
    WHERE target_namespace = NEW.target_namespace
      AND control_plane_request_id = selected_intent.control_plane_request_id;
    SELECT * INTO selected_catalog
    FROM game_session_canonical_realm_catalog
    WHERE target_namespace = NEW.target_namespace
      AND canonical_tenant_id = NEW.canonical_tenant_id
      AND realm_id = NEW.realm_id;
    SELECT * INTO selected_original
    FROM game_session_start_session_operator_attempt
    WHERE target_namespace = NEW.target_namespace
      AND control_plane_request_id = selected_intent.control_plane_request_id
      AND owner_attempt_id = NEW.original_owner_attempt_id;

    IF selected_intent.target_namespace IS NULL
       OR selected_intent.state <> 'HOLD_ATTACHED'
       OR selected_intent.terminal_at IS NOT NULL
       OR selected_intent.hold_identity_bytes IS NULL
       OR selected_launch.target_namespace IS NULL
       OR selected_catalog.target_namespace IS NULL
       OR selected_original.target_namespace IS NULL
       OR selected_original.phase_state <> 'OWNER_EXECUTION_PENDING'
       OR selected_intent.request_digest <> NEW.request_digest
       OR selected_intent.canonical_tenant_id <> NEW.canonical_tenant_id
       OR selected_intent.world_slug <> NEW.world_slug
       OR selected_intent.realm_id <> NEW.realm_id
       OR selected_intent.playable_state_namespace_id <> NEW.playable_state_namespace_id
       OR selected_intent.playable_state_scope <> NEW.playable_state_scope
       OR selected_intent.canonical_game_instance_id <> NEW.canonical_game_instance_id
       OR selected_intent.canonical_version_id <> NEW.canonical_version_id
       OR selected_intent.game_session_tenant_id <> NEW.game_session_tenant_id
       OR selected_intent.game_instance_id <> NEW.game_instance_id
       OR selected_intent.runtime_version_id <> NEW.runtime_version_id
       OR selected_intent.catalog_revision <> NEW.expected_catalog_revision
       OR selected_intent.control_plane_request_id <> selected_original.control_plane_request_id
       OR selected_launch.original_start_session_tuple <> selected_original.post_authorization_execution_tuple
       OR selected_launch.original_start_session_tuple IS NULL
       OR selected_intent.original_start_session_tuple IS DISTINCT FROM selected_launch.original_start_session_tuple
       OR selected_intent.launch_descriptor_id <> selected_launch.launch_descriptor_id
       OR selected_intent.captured_starting_row_version <> selected_launch.captured_starting_row_version
       OR selected_launch.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'
            IS DISTINCT FROM selected_intent.descriptor_request_digest
       OR selected_launch.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'
            IS DISTINCT FROM selected_intent.descriptor_result_digest
       OR selected_launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest'
            IS DISTINCT FROM selected_intent.release_attestation_digest
       OR selected_intent.tenant_association_operation_id <> selected_launch.tenant_association_operation_id
       OR selected_intent.catalog_source_intake_operation_id <> selected_launch.catalog_source_intake_operation_id
       OR selected_intent.catalog_source_intake_request_id <> selected_launch.catalog_source_intake_request_id
       OR selected_original.owner_mutation_id <> NEW.original_owner_mutation_id
       OR selected_original.owner_fence <> NEW.original_owner_fence
       OR selected_original.lease_expires_at <= clock_timestamp()
       OR selected_original.canonical_tenant_id <> NEW.canonical_tenant_id
       OR selected_catalog.creation_request_id <> selected_intent.catalog_creation_request_id
       OR selected_catalog.catalog_revision <> selected_intent.catalog_revision
       OR selected_catalog.source_intake_operation_id <> selected_intent.catalog_source_intake_operation_id
       OR selected_catalog.source_intake_request_id <> selected_intent.catalog_source_intake_request_id
       OR selected_catalog.request_digest <> selected_intent.catalog_request_digest
       OR selected_catalog.receipt_digest <> selected_intent.catalog_receipt_digest
       OR selected_launch.catalog_creation_request_id <> selected_catalog.creation_request_id
       OR selected_launch.catalog_revision <> selected_catalog.catalog_revision
       OR selected_launch.catalog_source_intake_operation_id <> selected_catalog.source_intake_operation_id
       OR selected_launch.catalog_source_intake_request_id <> selected_catalog.source_intake_request_id
       OR selected_launch.playable_state_scope <> selected_catalog.state_scope
       OR selected_launch.public_production IS NOT TRUE
       OR selected_catalog.visible IS NOT TRUE
       OR selected_catalog.public_production IS NOT TRUE
       OR selected_catalog.state_scope <> 'SHARED'
    THEN
        RAISE EXCEPTION 'Terminal Account protection is not linked to the exact V28 intent, V43 launch/catalog, and V35 original owner'
            USING ERRCODE = '23514';
    END IF;

    BEGIN
        evidence_json := convert_from(NEW.account_protection_evidence_bytes, 'UTF8')::JSONB;
        evidence_request_bytes := decode(evidence_json ->> 'requestBytesBase64', 'base64');
        evidence_request := convert_from(evidence_request_bytes, 'UTF8')::JSONB;
        evidence_tuple_bytes := decode(evidence_request ->> 'originalPostAuthorizationTupleBytesBase64', 'base64');
        evidence_projection_bytes := decode(evidence_request ->> 'accountRedemptionProjectionBytesBase64', 'base64');
        evidence_hold_bytes := decode(evidence_request ->> 'worldAdmissionHoldIdentityBytesBase64', 'base64');
        hold_json := convert_from(evidence_hold_bytes, 'UTF8')::JSONB;
        hold_request_json := convert_from(decode(hold_json ->> 'requestBytesBase64', 'base64'), 'UTF8')::JSONB;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Terminal Account protection evidence bytes are not structurally readable'
            USING ERRCODE = '23514';
    END;

    IF evidence_json ->> 'schema' IS DISTINCT FROM 'account-start-session-admission-protection-evidence/v1'
       OR jsonb_typeof(evidence_json -> 'sourceEvidenceVector') IS DISTINCT FROM 'array'
       OR jsonb_array_length(evidence_json -> 'sourceEvidenceVector') < 1
       OR evidence_request ->> 'schema' IS DISTINCT FROM 'account-start-session-admission-protection-request/v1'
       OR evidence_json ->> 'accountProtectionId' IS DISTINCT FROM NEW.account_protection_id::TEXT
       OR evidence_json ->> 'accountProtectionFence' IS DISTINCT FROM NEW.account_protection_fence::TEXT
       OR evidence_request ->> 'gameSessionOwnerAttemptId' IS DISTINCT FROM NEW.original_owner_attempt_id::TEXT
       OR evidence_request ->> 'gameSessionOwnerMutationId' IS DISTINCT FROM NEW.original_owner_mutation_id::TEXT
       OR evidence_request ->> 'gameSessionOwnerFence' IS DISTINCT FROM NEW.original_owner_fence::TEXT
       OR evidence_request ->> 'originalLeaseExpiresAt' IS DISTINCT FROM
            to_char(selected_original.lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
       OR evidence_tuple_bytes IS DISTINCT FROM selected_original.post_authorization_execution_tuple
       OR evidence_tuple_bytes IS DISTINCT FROM selected_launch.original_start_session_tuple
       OR evidence_projection_bytes IS DISTINCT FROM selected_original.account_redemption_projection
       OR selected_original.account_redemption_projection IS NULL
       OR evidence_hold_bytes IS DISTINCT FROM selected_intent.hold_identity_bytes
       OR hold_json ->> 'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-identity/v1'
       OR hold_json ->> 'holdId' IS DISTINCT FROM NEW.hold_id::TEXT
       OR hold_json ->> 'holdFence' IS DISTINCT FROM NEW.hold_fence::TEXT
       OR hold_json ->> 'holdBindingDigest' IS DISTINCT FROM NEW.hold_binding_digest
       OR hold_request_json ->> 'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-request/v1'
       OR jsonb_typeof(hold_request_json -> 'request') IS DISTINCT FROM 'object'
       OR hold_request_json -> 'request' ->> 'targetNamespace' IS DISTINCT FROM NEW.target_namespace
       OR hold_request_json -> 'request' ->> 'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
       OR hold_request_json -> 'request' ->> 'worldSlug' IS DISTINCT FROM NEW.world_slug
       OR hold_request_json -> 'request' ->> 'realmId' IS DISTINCT FROM NEW.realm_id::TEXT
       OR hold_request_json -> 'request' ->> 'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
       OR hold_request_json -> 'request' ->> 'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
       OR hold_request_json -> 'request' ->> 'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
       OR hold_request_json -> 'request' ->> 'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
       OR hold_request_json -> 'request' ->> 'activeLifecycleEpoch' IS DISTINCT FROM NEW.active_lifecycle_epoch::TEXT
       OR hold_request_json -> 'request' ->> 'initialAdmissionRequestId' IS DISTINCT FROM NEW.initial_admission_request_id
       OR hold_request_json -> 'request' ->> 'initialAdmissionRequestDigest' IS DISTINCT FROM NEW.request_digest
       OR hold_request_json -> 'request' ->> 'initialAdmissionOrigin' IS DISTINCT FROM NEW.origin_kind
       OR hold_request_json -> 'request' ->> 'expectedCatalogRevision' IS DISTINCT FROM NEW.expected_catalog_revision::TEXT
       OR COALESCE(hold_request_json -> 'request' ->> 'expectedPriorPointerVersion', '')
            IS DISTINCT FROM COALESCE(NEW.expected_prior_pointer_version::TEXT, '')
       OR decode(hold_json ->> 'requestBytesBase64', 'base64') IS DISTINCT FROM selected_intent.canonical_hold_request_bytes
    THEN
        RAISE EXCEPTION 'Full Account protection evidence differs from its exact tuple, projection, lease, V35 owner, or World hold'
            USING ERRCODE = '23514';
    END IF;

    -- Re-derive the established CanonicalInitialAdmissionRequest digest from the exact V28 fields
    -- using the same int32 big-endian UTF-8 byte-length framing as ADR0047 and the Java owner.
    digest_bytes := ''::BYTEA;
    domain_bytes := convert_to('game-session-canonical-initial-admission/v1', 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.target_namespace, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.canonical_tenant_id::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.world_slug, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.realm_id::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.playable_state_namespace_id::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.playable_state_scope, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.canonical_game_instance_id::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.canonical_version_id::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.active_lifecycle_epoch::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.expected_catalog_revision::TEXT, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.origin_kind, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(COALESCE(NEW.expected_prior_pointer_version::TEXT, ''), 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.initial_admission_request_id, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    expected_request_digest := encode(sha256(digest_bytes), 'hex');
    IF NEW.request_digest <> expected_request_digest THEN
        RAISE EXCEPTION 'Canonical initial-admission request digest does not bind the exact V28 fields'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'COMMITTED' THEN
        IF NEW.pointer_id IS NULL OR NEW.pointer_version IS NULL OR NEW.audit_event_id IS NULL
           OR NEW.abort_reason IS NOT NULL OR NEW.abort_proof_digest IS NOT NULL THEN
            RAISE EXCEPTION 'COMMITTED initial-admission terminal fields are incomplete or mixed'
                USING ERRCODE = '23514';
        END IF;
        terminal_at_text := to_char(NEW.terminal_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
    ELSE
        IF NEW.pointer_id IS NOT NULL OR NEW.pointer_version IS NOT NULL OR NEW.audit_event_id IS NOT NULL
           OR NEW.abort_reason IS NULL OR NEW.commit_proof_digest IS NOT NULL THEN
            RAISE EXCEPTION 'ABORTED initial-admission terminal fields are incomplete or mixed'
                USING ERRCODE = '23514';
        END IF;
        terminal_at_text := to_char(NEW.terminal_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
    END IF;

    digest_bytes := ''::BYTEA;
    domain_bytes := convert_to('game-session-canonical-initial-admission-terminal/v1', 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    digest_bytes := digest_bytes || int4send(octet_length(NEW.account_protection_evidence_bytes))
        || NEW.account_protection_evidence_bytes;
    domain_bytes := convert_to(NEW.request_digest, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(NEW.status, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(COALESCE(NEW.pointer_id::TEXT, ''), 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(COALESCE(NEW.pointer_version::TEXT, ''), 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(COALESCE(NEW.audit_event_id::TEXT, ''), 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(terminal_at_text, 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    domain_bytes := convert_to(COALESCE(NEW.abort_reason, ''), 'UTF8');
    digest_bytes := digest_bytes || int4send(octet_length(domain_bytes)) || domain_bytes;
    IF NEW.status = 'COMMITTED' THEN
        NEW.commit_proof_digest := 'sha256:' || encode(sha256(digest_bytes), 'hex');
    ELSE
        NEW.abort_proof_digest := 'sha256:' || encode(sha256(digest_bytes), 'hex');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_initial_admission_v44_terminal_evidence
BEFORE INSERT OR UPDATE ON game_session_canonical_initial_admission_attempt
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_account_terminal_evidence();

CREATE TRIGGER game_session_canonical_initial_admission_intent_v44_immutable
BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_initial_admission_intent
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_account_terminal_evidence();

CREATE FUNCTION reject_game_session_canonical_initial_admission_protection_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_session_canonical_initial_admission_attempt
               WHERE account_protection_evidence_bytes IS NOT NULL)
       OR EXISTS (SELECT 1 FROM game_session_canonical_initial_admission_intent
               WHERE hold_identity_bytes IS NOT NULL) THEN
        RAISE EXCEPTION 'Canonical initial-admission Account protection evidence cannot be truncated'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER game_session_canonical_initial_admission_protection_no_truncate
BEFORE TRUNCATE ON game_session_canonical_initial_admission_attempt
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_initial_admission_protection_truncate();
CREATE TRIGGER game_session_canonical_initial_admission_intent_no_truncate
BEFORE TRUNCATE ON game_session_canonical_initial_admission_intent
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_initial_admission_protection_truncate();
-- [jooq ignore stop]
