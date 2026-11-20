-- Admit the run-owned fixture's first OPEN through the existing intent, attempt and pointer owners.
-- The fixture has no Account protection or original StartSession tuple: its launch's policy-seal
-- evidence digest (the fixture provenance digest) takes the release-attestation and protection
-- slots. Every Human check below is retained unchanged; fixture rows are admitted only with their
-- exact fixture launch, attached hold and retained fixture runtime completion.
-- [jooq ignore start]
LOCK TABLE game_session_canonical_initial_admission_intent,
           game_session_canonical_initial_admission_attempt IN ACCESS EXCLUSIVE MODE;

ALTER TABLE game_session_canonical_initial_admission_attempt
    ADD COLUMN fixture_provenance_digest VARCHAR(71),
    ADD CONSTRAINT chk_gs_initial_admission_fixture_provenance CHECK (
        fixture_provenance_digest IS NULL
        OR (fixture_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
            AND account_protection_evidence_version IS NULL
            AND account_protection_id IS NULL
            AND account_protection_fence IS NULL
            AND original_owner_attempt_id IS NULL
            AND original_owner_mutation_id IS NULL
            AND original_owner_fence IS NULL
            AND account_protection_evidence_bytes IS NULL));

-- Retained historical rows stay unvalidated exactly as V1011 left them; new terminals require
-- Account protection or, for the run-owned fixture only, its fixture provenance.
ALTER TABLE game_session_canonical_initial_admission_attempt
    DROP CONSTRAINT chk_gs_initial_admission_terminal_requires_protection,
    ADD CONSTRAINT chk_gs_initial_admission_terminal_requires_protection
        CHECK (status = 'PENDING'
            OR (account_protection_evidence_version IS NOT NULL
                AND account_protection_evidence_version = 1)
            OR (account_protection_evidence_version IS NULL
                AND fixture_provenance_digest IS NOT NULL)) NOT VALID;

-- A fixture intent retains the exact catalog source identity but no original StartSession tuple.
ALTER TABLE game_session_canonical_initial_admission_intent
    DROP CONSTRAINT chk_gs_initial_admission_intent_v44_launch_evidence,
    ADD CONSTRAINT chk_gs_initial_admission_intent_v44_launch_evidence
        CHECK ((catalog_source_intake_operation_id IS NULL
                AND catalog_source_intake_request_id IS NULL
                AND original_start_session_tuple IS NULL)
            OR (catalog_source_intake_operation_id IS NOT NULL
                AND catalog_source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND catalog_source_intake_request_id IS NOT NULL
                AND catalog_source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND (original_start_session_tuple IS NULL
                     OR octet_length(original_start_session_tuple) BETWEEN 1 AND 262144)));

CREATE TABLE game_session_run_owned_fixture_runtime_completion (
    target_namespace VARCHAR(63) NOT NULL,
    initial_admission_request_id VARCHAR(128) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    realm_id UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(16) NOT NULL,
    canonical_game_instance_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    active_lifecycle_epoch BIGINT NOT NULL,
    expected_catalog_revision BIGINT NOT NULL,
    origin_kind VARCHAR(32) NOT NULL,
    expected_prior_pointer_version BIGINT,
    hold_id UUID NOT NULL,
    hold_fence UUID NOT NULL,
    hold_binding_digest VARCHAR(71) NOT NULL,
    game_session_tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    captured_starting_row_version BIGINT NOT NULL,
    completed_running_row_version BIGINT NOT NULL,
    fixture_provenance_digest VARCHAR(71) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_gs_run_owned_fixture_runtime_completion
        PRIMARY KEY (target_namespace, initial_admission_request_id),
    CONSTRAINT uq_gs_run_owned_fixture_runtime_completion_launch
        UNIQUE (target_namespace, canonical_game_instance_id),
    CONSTRAINT uq_gs_run_owned_fixture_runtime_completion_runtime
        UNIQUE (game_session_tenant_id, game_instance_id),
    CONSTRAINT fk_gs_run_owned_fixture_runtime_completion_intent
        FOREIGN KEY (target_namespace, initial_admission_request_id)
        REFERENCES game_session_canonical_initial_admission_intent
            (target_namespace, initial_admission_request_id),
    CONSTRAINT fk_gs_run_owned_fixture_runtime_completion_attempt
        FOREIGN KEY (target_namespace, initial_admission_request_id)
        REFERENCES game_session_canonical_initial_admission_attempt
            (target_namespace, initial_admission_request_id),
    CONSTRAINT fk_gs_run_owned_fixture_runtime_completion_launch
        FOREIGN KEY (target_namespace, canonical_game_instance_id)
        REFERENCES game_session_canonical_instance_launch (target_namespace, game_instance_uuid),
    CONSTRAINT chk_gs_run_owned_fixture_runtime_completion_shape CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND playable_state_scope = 'SHARED'
        AND active_lifecycle_epoch > 0
        AND expected_catalog_revision > 0
        AND origin_kind IN ('NO_PRIOR_POINTER', 'EXPECT_CLOSED')
        AND ((origin_kind = 'NO_PRIOR_POINTER' AND expected_prior_pointer_version IS NULL)
          OR (origin_kind = 'EXPECT_CLOSED' AND expected_prior_pointer_version > 0))
        AND hold_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND fixture_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
        AND game_session_tenant_id > 0
        AND game_instance_id > 0
        AND captured_starting_row_version >= 0
        AND completed_running_row_version = captured_starting_row_version + 1)
);

CREATE FUNCTION require_gs_run_owned_fixture_runtime_completion()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime game_instances%ROWTYPE;
    launch game_session_canonical_instance_launch%ROWTYPE;
    intent game_session_canonical_initial_admission_intent%ROWTYPE;
    attempt game_session_canonical_initial_admission_attempt%ROWTYPE;
BEGIN
    SELECT * INTO launch FROM game_session_canonical_instance_launch
     WHERE target_namespace=NEW.target_namespace
       AND game_instance_uuid=NEW.canonical_game_instance_id;
    SELECT * INTO runtime FROM game_instances
     WHERE tenant_id=NEW.game_session_tenant_id AND id=NEW.game_instance_id;
    SELECT * INTO intent FROM game_session_canonical_initial_admission_intent
     WHERE target_namespace=NEW.target_namespace
       AND initial_admission_request_id=NEW.initial_admission_request_id;
    SELECT * INTO attempt FROM game_session_canonical_initial_admission_attempt
     WHERE target_namespace=NEW.target_namespace
       AND initial_admission_request_id=NEW.initial_admission_request_id;

    IF launch.target_namespace IS NULL OR runtime.id IS NULL OR intent.target_namespace IS NULL
       OR attempt.target_namespace IS NULL
       OR launch.fixture_policy_request_bytes IS NULL
       OR launch.original_start_session_tuple IS NOT NULL
       OR launch.fixture_policy_seal_evidence_digest IS DISTINCT FROM NEW.fixture_provenance_digest
       OR runtime.status IS DISTINCT FROM 'RUNNING'
       OR runtime.row_version IS DISTINCT FROM NEW.completed_running_row_version
       OR runtime.game_instance_uuid IS DISTINCT FROM NEW.canonical_game_instance_id
       OR runtime.run_owned_start_request_id IS DISTINCT FROM launch.control_plane_request_id
       OR launch.captured_starting_row_version IS DISTINCT FROM NEW.captured_starting_row_version
       OR launch.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
       OR launch.game_instance_id IS DISTINCT FROM NEW.game_instance_id
       OR launch.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
       OR launch.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR launch.canonical_realm_id IS DISTINCT FROM NEW.realm_id
       OR launch.world_slug IS DISTINCT FROM NEW.world_slug
       OR launch.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
       OR launch.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
       OR intent.state IS DISTINCT FROM 'HOLD_ATTACHED'
       OR intent.hold_identity_bytes IS NULL
       OR intent.request_digest IS DISTINCT FROM NEW.request_digest
       OR intent.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
       OR intent.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
       OR intent.release_attestation_digest IS DISTINCT FROM NEW.fixture_provenance_digest
       OR intent.original_start_session_tuple IS NOT NULL
       OR attempt.status IS DISTINCT FROM 'PENDING'
       OR attempt.fixture_provenance_digest IS DISTINCT FROM NEW.fixture_provenance_digest
       OR attempt.request_digest IS DISTINCT FROM NEW.request_digest
       OR attempt.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR attempt.world_slug IS DISTINCT FROM NEW.world_slug
       OR attempt.realm_id IS DISTINCT FROM NEW.realm_id
       OR attempt.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
       OR attempt.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
       OR attempt.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
       OR attempt.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
       OR attempt.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
       OR attempt.game_instance_id IS DISTINCT FROM NEW.game_instance_id
       OR attempt.active_lifecycle_epoch IS DISTINCT FROM NEW.active_lifecycle_epoch
       OR attempt.expected_catalog_revision IS DISTINCT FROM NEW.expected_catalog_revision
       OR attempt.origin_kind IS DISTINCT FROM NEW.origin_kind
       OR attempt.expected_prior_pointer_version IS DISTINCT FROM NEW.expected_prior_pointer_version
       OR attempt.hold_id IS DISTINCT FROM NEW.hold_id
       OR attempt.hold_fence IS DISTINCT FROM NEW.hold_fence
       OR attempt.hold_binding_digest IS DISTINCT FROM NEW.hold_binding_digest
       OR EXISTS (SELECT 1 FROM gameplay_admission_pointer p
                   WHERE p.target_namespace=NEW.target_namespace
                     AND p.initial_admission_request_id=NEW.initial_admission_request_id)
       OR EXISTS (SELECT 1 FROM gameplay_admission_pointer_event e
                   WHERE e.target_namespace=NEW.target_namespace
                     AND e.initial_admission_request_id=NEW.initial_admission_request_id)
    THEN
        RAISE EXCEPTION 'Fixture runtime completion requires the exact pending fixture attempt, attached hold, fixture launch and RUNNING CAS'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_run_owned_fixture_runtime_completion_guard
BEFORE INSERT ON game_session_run_owned_fixture_runtime_completion
FOR EACH ROW EXECUTE FUNCTION require_gs_run_owned_fixture_runtime_completion();

CREATE FUNCTION reject_gs_run_owned_fixture_runtime_completion_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Run-owned fixture runtime completion evidence is immutable'
        USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER gs_run_owned_fixture_runtime_completion_immutable
BEFORE UPDATE OR DELETE ON game_session_run_owned_fixture_runtime_completion
FOR EACH ROW EXECUTE FUNCTION reject_gs_run_owned_fixture_runtime_completion_mutation();
CREATE TRIGGER gs_run_owned_fixture_runtime_completion_no_truncate
BEFORE TRUNCATE ON game_session_run_owned_fixture_runtime_completion
FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_run_owned_fixture_runtime_completion_mutation();

-- A run-owned fixture runtime reaches RUNNING only with its exact retained completion row.
CREATE FUNCTION require_gs_run_owned_fixture_running_completion()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM game_session_canonical_instance_launch launch
         WHERE launch.game_session_tenant_id = NEW.tenant_id
           AND launch.game_instance_id = NEW.id
           AND launch.fixture_policy_request_bytes IS NOT NULL) THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM game_session_run_owned_fixture_runtime_completion completion
          JOIN game_session_canonical_instance_launch launch
            ON launch.target_namespace = completion.target_namespace
           AND launch.game_instance_uuid = completion.canonical_game_instance_id
           AND launch.game_session_tenant_id = completion.game_session_tenant_id
           AND launch.game_instance_id = completion.game_instance_id
           AND launch.fixture_policy_seal_evidence_digest = completion.fixture_provenance_digest
         WHERE completion.game_session_tenant_id = NEW.tenant_id
           AND completion.game_instance_id = NEW.id
           AND completion.canonical_game_instance_id = NEW.game_instance_uuid
           AND completion.captured_starting_row_version = OLD.row_version
           AND completion.completed_running_row_version = NEW.row_version) THEN
        RAISE EXCEPTION 'Run-owned fixture STARTING to RUNNING requires its exact retained runtime completion'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_run_owned_fixture_running_completion_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER game_instances_run_owned_fixture_running_completion
    AFTER UPDATE ON game_instances
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status = 'STARTING' AND NEW.status = 'RUNNING')
    EXECUTE FUNCTION require_gs_run_owned_fixture_running_completion();

-- The fixture attempt guard mirrors the protected terminal guard with no Account protection; the
-- fixture provenance digest's UTF-8 bytes take the V1006 terminal preimage's protection slot.
CREATE FUNCTION require_gs_run_owned_fixture_initial_admission_attempt(
    p_op TEXT,
    p_old game_session_canonical_initial_admission_attempt,
    p_new game_session_canonical_initial_admission_attempt)
RETURNS game_session_canonical_initial_admission_attempt
LANGUAGE plpgsql
AS $$
DECLARE
    selected_intent game_session_canonical_initial_admission_intent%ROWTYPE;
    selected_launch game_session_canonical_instance_launch%ROWTYPE;
    selected_catalog game_session_canonical_realm_catalog%ROWTYPE;
    hold_json JSONB;
    expected_request_digest TEXT;
    digest_bytes BYTEA;
    field_bytes BYTEA;
BEGIN
    IF p_new.status NOT IN ('PENDING', 'COMMITTED')
       OR p_new.fixture_provenance_digest IS NULL
       OR p_new.fixture_provenance_digest !~ '^sha256:[0-9a-f]{64}$'
       OR p_new.account_protection_evidence_version IS NOT NULL
       OR p_new.account_protection_id IS NOT NULL
       OR p_new.account_protection_fence IS NOT NULL
       OR p_new.original_owner_attempt_id IS NOT NULL
       OR p_new.original_owner_mutation_id IS NOT NULL
       OR p_new.original_owner_fence IS NOT NULL
       OR p_new.account_protection_evidence_bytes IS NOT NULL
       OR (p_op = 'INSERT' AND p_new.status <> 'PENDING')
       OR (p_op = 'UPDATE'
           AND (p_old.status <> 'PENDING'
                OR p_old.fixture_provenance_digest IS DISTINCT FROM p_new.fixture_provenance_digest))
    THEN
        RAISE EXCEPTION 'Run-owned fixture initial admission requires one immutable fixture provenance and no Account protection'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_initial_admission_fixture_provenance';
    END IF;

    SELECT * INTO selected_intent
    FROM game_session_canonical_initial_admission_intent
    WHERE target_namespace = p_new.target_namespace
      AND initial_admission_request_id = p_new.initial_admission_request_id;
    SELECT * INTO selected_launch
    FROM game_session_canonical_instance_launch
    WHERE target_namespace = p_new.target_namespace
      AND control_plane_request_id = selected_intent.control_plane_request_id;
    SELECT * INTO selected_catalog
    FROM game_session_canonical_realm_catalog
    WHERE target_namespace = p_new.target_namespace
      AND canonical_tenant_id = p_new.canonical_tenant_id
      AND realm_id = p_new.realm_id;

    IF selected_intent.target_namespace IS NULL
       OR selected_intent.state <> 'HOLD_ATTACHED'
       OR selected_intent.terminal_at IS NOT NULL
       OR selected_intent.hold_identity_bytes IS NULL
       OR selected_launch.target_namespace IS NULL
       OR selected_catalog.target_namespace IS NULL
       OR selected_launch.fixture_policy_request_bytes IS NULL
       OR selected_launch.original_start_session_tuple IS NOT NULL
       OR selected_intent.original_start_session_tuple IS NOT NULL
       OR selected_launch.fixture_policy_seal_evidence_digest IS DISTINCT FROM p_new.fixture_provenance_digest
       OR selected_intent.release_attestation_digest IS DISTINCT FROM p_new.fixture_provenance_digest
       OR selected_launch.game_instance_uuid IS DISTINCT FROM p_new.canonical_game_instance_id
       OR selected_launch.game_session_tenant_id IS DISTINCT FROM p_new.game_session_tenant_id
       OR selected_launch.game_instance_id IS DISTINCT FROM p_new.game_instance_id
       OR selected_launch.version_id IS DISTINCT FROM p_new.runtime_version_id
       OR selected_intent.request_digest <> p_new.request_digest
       OR selected_intent.canonical_tenant_id <> p_new.canonical_tenant_id
       OR selected_intent.world_slug <> p_new.world_slug
       OR selected_intent.realm_id <> p_new.realm_id
       OR selected_intent.playable_state_namespace_id <> p_new.playable_state_namespace_id
       OR selected_intent.playable_state_scope <> p_new.playable_state_scope
       OR selected_intent.canonical_game_instance_id <> p_new.canonical_game_instance_id
       OR selected_intent.canonical_version_id <> p_new.canonical_version_id
       OR selected_intent.game_session_tenant_id <> p_new.game_session_tenant_id
       OR selected_intent.game_instance_id <> p_new.game_instance_id
       OR selected_intent.runtime_version_id <> p_new.runtime_version_id
       OR selected_intent.catalog_revision <> p_new.expected_catalog_revision
       OR selected_intent.launch_descriptor_id <> selected_launch.launch_descriptor_id
       OR selected_intent.captured_starting_row_version <> selected_launch.captured_starting_row_version
       OR selected_launch.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'
            IS DISTINCT FROM selected_intent.descriptor_request_digest
       OR selected_launch.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'
            IS DISTINCT FROM selected_intent.descriptor_result_digest
       OR selected_intent.tenant_association_operation_id <> selected_launch.tenant_association_operation_id
       OR selected_intent.catalog_source_intake_operation_id <> selected_launch.catalog_source_intake_operation_id
       OR selected_intent.catalog_source_intake_request_id <> selected_launch.catalog_source_intake_request_id
       OR selected_catalog.creation_request_id <> selected_intent.catalog_creation_request_id
       OR selected_catalog.catalog_revision <> selected_intent.catalog_revision
       OR selected_catalog.source_intake_operation_id <> selected_intent.catalog_source_intake_operation_id
       OR selected_catalog.source_intake_request_id <> selected_intent.catalog_source_intake_request_id
       OR selected_catalog.request_digest <> selected_intent.catalog_request_digest
       OR selected_catalog.receipt_digest <> selected_intent.catalog_receipt_digest
       OR selected_catalog.fixture_policy_seal_evidence_digest IS DISTINCT FROM p_new.fixture_provenance_digest
       OR selected_launch.catalog_creation_request_id <> selected_catalog.creation_request_id
       OR selected_launch.catalog_revision <> selected_catalog.catalog_revision
       OR selected_launch.playable_state_scope <> selected_catalog.state_scope
       OR selected_launch.public_production IS NOT TRUE
       OR selected_catalog.visible IS NOT TRUE
       OR selected_catalog.public_production IS NOT TRUE
       OR selected_catalog.state_scope <> 'SHARED'
    THEN
        RAISE EXCEPTION 'Run-owned fixture admission is not linked to its exact intent, fixture launch and catalog'
            USING ERRCODE = '23514';
    END IF;

    BEGIN
        hold_json := convert_from(selected_intent.hold_identity_bytes, 'UTF8')::JSONB;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Fixture admission hold identity is not structurally readable'
            USING ERRCODE = '23514';
    END;
    IF hold_json ->> 'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-identity/v1'
       OR hold_json ->> 'holdId' IS DISTINCT FROM p_new.hold_id::TEXT
       OR hold_json ->> 'holdFence' IS DISTINCT FROM p_new.hold_fence::TEXT
       OR hold_json ->> 'holdBindingDigest' IS DISTINCT FROM p_new.hold_binding_digest
       OR decode(hold_json ->> 'requestBytesBase64', 'base64')
            IS DISTINCT FROM selected_intent.canonical_hold_request_bytes
    THEN
        RAISE EXCEPTION 'Fixture admission differs from its attached World hold identity'
            USING ERRCODE = '23514';
    END IF;

    digest_bytes := ''::BYTEA;
    FOREACH field_bytes IN ARRAY ARRAY[
        convert_to('game-session-canonical-initial-admission/v1', 'UTF8'),
        convert_to(p_new.target_namespace, 'UTF8'),
        convert_to(p_new.canonical_tenant_id::TEXT, 'UTF8'),
        convert_to(p_new.world_slug, 'UTF8'),
        convert_to(p_new.realm_id::TEXT, 'UTF8'),
        convert_to(p_new.playable_state_namespace_id::TEXT, 'UTF8'),
        convert_to(p_new.playable_state_scope, 'UTF8'),
        convert_to(p_new.canonical_game_instance_id::TEXT, 'UTF8'),
        convert_to(p_new.canonical_version_id::TEXT, 'UTF8'),
        convert_to(p_new.active_lifecycle_epoch::TEXT, 'UTF8'),
        convert_to(p_new.expected_catalog_revision::TEXT, 'UTF8'),
        convert_to(p_new.origin_kind, 'UTF8'),
        convert_to(COALESCE(p_new.expected_prior_pointer_version::TEXT, ''), 'UTF8'),
        convert_to(p_new.initial_admission_request_id, 'UTF8')]
    LOOP
        digest_bytes := digest_bytes || int4send(octet_length(field_bytes)) || field_bytes;
    END LOOP;
    expected_request_digest := encode(sha256(digest_bytes), 'hex');
    IF p_new.request_digest <> expected_request_digest THEN
        RAISE EXCEPTION 'Canonical initial-admission request digest does not bind the exact V28 fields'
            USING ERRCODE = '23514';
    END IF;

    IF p_new.status = 'PENDING' THEN
        RETURN p_new;
    END IF;

    IF p_new.pointer_id IS NULL OR p_new.pointer_version IS NULL OR p_new.audit_event_id IS NULL
       OR p_new.abort_reason IS NOT NULL OR p_new.abort_proof_digest IS NOT NULL
       OR NOT EXISTS (
           SELECT 1 FROM game_session_run_owned_fixture_runtime_completion completion
            WHERE completion.target_namespace = p_new.target_namespace
              AND completion.initial_admission_request_id = p_new.initial_admission_request_id
              AND completion.request_digest = p_new.request_digest
              AND completion.canonical_game_instance_id = p_new.canonical_game_instance_id
              AND completion.canonical_version_id = p_new.canonical_version_id
              AND completion.game_session_tenant_id = p_new.game_session_tenant_id
              AND completion.game_instance_id = p_new.game_instance_id
              AND completion.hold_id = p_new.hold_id
              AND completion.hold_fence = p_new.hold_fence
              AND completion.hold_binding_digest = p_new.hold_binding_digest
              AND completion.fixture_provenance_digest = p_new.fixture_provenance_digest)
    THEN
        RAISE EXCEPTION 'COMMITTED fixture admission requires complete terminal fields and its retained runtime completion'
            USING ERRCODE = '23514';
    END IF;

    digest_bytes := ''::BYTEA;
    FOREACH field_bytes IN ARRAY ARRAY[
        convert_to('game-session-canonical-initial-admission-terminal/v1', 'UTF8'),
        convert_to(p_new.fixture_provenance_digest, 'UTF8'),
        convert_to(p_new.request_digest, 'UTF8'),
        convert_to(p_new.status, 'UTF8'),
        convert_to(COALESCE(p_new.pointer_id::TEXT, ''), 'UTF8'),
        convert_to(COALESCE(p_new.pointer_version::TEXT, ''), 'UTF8'),
        convert_to(COALESCE(p_new.audit_event_id::TEXT, ''), 'UTF8'),
        convert_to(to_char(p_new.terminal_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), 'UTF8'),
        convert_to(COALESCE(p_new.abort_reason, ''), 'UTF8')]
    LOOP
        digest_bytes := digest_bytes || int4send(octet_length(field_bytes)) || field_bytes;
    END LOOP;
    p_new.commit_proof_digest := 'sha256:' || encode(sha256(digest_bytes), 'hex');
    RETURN p_new;
END;
$$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    -- Intent and attempt terminal guard: branch the fixture provenance, leave Human checks intact.
    SELECT pg_get_functiondef((quote_ident(current_schema())
        ||'.require_game_session_canonical_initial_admission_account_terminal_evidence()')::regprocedure)
        INTO STRICT definition;

    original := $needle$            IF NEW.original_start_session_tuple IS NULL
               OR octet_length(NEW.original_start_session_tuple) NOT BETWEEN 1 AND 262144
               OR selected_launch.target_namespace IS NULL$needle$;
    replacement := $replacement$            IF (CASE WHEN selected_launch.fixture_policy_request_bytes IS NOT NULL
                     THEN NEW.original_start_session_tuple IS NOT NULL
                     ELSE NEW.original_start_session_tuple IS NULL
                          OR octet_length(NEW.original_start_session_tuple) NOT BETWEEN 1 AND 262144
                END)
               OR selected_launch.target_namespace IS NULL$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact intent original tuple clause'; END IF;
    definition := replace(definition,original,replacement);

    original := $needle$               OR selected_launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest'
                    IS DISTINCT FROM NEW.release_attestation_digest$needle$;
    replacement := $replacement$               OR COALESCE(selected_launch.fixture_policy_seal_evidence_digest,
                    selected_launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest')
                    IS DISTINCT FROM NEW.release_attestation_digest$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact intent release attestation clause'; END IF;
    definition := replace(definition,original,replacement);

    original := $needle$                 AND attempt.account_protection_evidence_version = 1
                 AND attempt.account_protection_evidence_bytes IS NOT NULL$needle$;
    replacement := $replacement$                 AND ((attempt.account_protection_evidence_version = 1
                       AND attempt.account_protection_evidence_bytes IS NOT NULL)
                      OR (attempt.status = 'COMMITTED'
                          AND attempt.account_protection_evidence_version IS NULL
                          AND attempt.fixture_provenance_digest IS NOT NULL
                          AND attempt.fixture_provenance_digest = NEW.release_attestation_digest
                          AND NEW.original_start_session_tuple IS NULL))$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact intent terminal protection clause'; END IF;
    definition := replace(definition,original,replacement);

    original := $needle$    IF NEW.status NOT IN ('PENDING', 'COMMITTED', 'ABORTED')
       OR NEW.account_protection_evidence_version IS DISTINCT FROM 1$needle$;
    replacement := $replacement$    IF NEW.fixture_provenance_digest IS NOT NULL
       OR (TG_OP = 'UPDATE' AND OLD.fixture_provenance_digest IS NOT NULL) THEN
        NEW := require_gs_run_owned_fixture_initial_admission_attempt(TG_OP, OLD, NEW);
        RETURN NEW;
    END IF;

$replacement$ || original;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact attempt protection clause'; END IF;
    definition := replace(definition,original,replacement);
    EXECUTE definition;

    -- Atomic pointer/audit evidence: the fixture binds its canonical version through its completion.
    SELECT pg_get_functiondef((quote_ident(current_schema())
        ||'.require_game_session_canonical_initial_admission_atomic_evidence()')::regprocedure)
        INTO STRICT definition;
    original := $needle$         AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = attempt.canonical_version_id::text$needle$;
    replacement := $replacement$         AND (launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = attempt.canonical_version_id::text
              OR (launch.fixture_policy_request_bytes IS NOT NULL
                  AND attempt.fixture_provenance_digest = launch.fixture_policy_seal_evidence_digest
                  AND EXISTS (
                      SELECT 1 FROM game_session_run_owned_fixture_runtime_completion completion
                       WHERE completion.target_namespace = attempt.target_namespace
                         AND completion.initial_admission_request_id = attempt.initial_admission_request_id
                         AND completion.canonical_game_instance_id = attempt.canonical_game_instance_id
                         AND completion.canonical_version_id = attempt.canonical_version_id
                         AND completion.fixture_provenance_digest = attempt.fixture_provenance_digest)))$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact atomic launch canonical version clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    -- OPEN pointer owner guard: same fixture canonical-version binding.
    SELECT pg_get_functiondef((quote_ident(current_schema())
        ||'.require_game_session_canonical_initial_admission_catalog()')::regprocedure)
        INTO STRICT definition;
    original := $needle$         AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = NEW.canonical_version_id::text$needle$;
    replacement := $replacement$         AND (launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = NEW.canonical_version_id::text
              OR (launch.fixture_policy_request_bytes IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM game_session_run_owned_fixture_runtime_completion completion
                       WHERE completion.target_namespace = NEW.target_namespace
                         AND completion.initial_admission_request_id = NEW.initial_admission_request_id
                         AND completion.request_digest = NEW.initial_admission_request_digest
                         AND completion.canonical_game_instance_id = NEW.canonical_game_instance_id
                         AND completion.canonical_version_id = NEW.canonical_version_id
                         AND completion.fixture_provenance_digest = launch.fixture_policy_seal_evidence_digest)))$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact pointer launch canonical version clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    -- Fresh source-bound tenant scope for OPEN pointer/audit rows: same fixture binding.
    SELECT pg_get_functiondef((quote_ident(current_schema())
        ||'.reserve_game_session_tenant_scope()')::regprocedure)
        INTO STRICT definition;
    original := $needle$              AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
                  = attempt.canonical_version_id::text$needle$;
    replacement := $replacement$              AND (launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
                  = attempt.canonical_version_id::text
                   OR (launch.fixture_policy_request_bytes IS NOT NULL
                       AND attempt.fixture_provenance_digest = launch.fixture_policy_seal_evidence_digest
                       AND EXISTS (
                           SELECT 1 FROM game_session_run_owned_fixture_runtime_completion completion
                            WHERE completion.target_namespace = attempt.target_namespace
                              AND completion.initial_admission_request_id = attempt.initial_admission_request_id
                              AND completion.canonical_game_instance_id = attempt.canonical_game_instance_id
                              AND completion.canonical_version_id = attempt.canonical_version_id
                              AND completion.fixture_provenance_digest = attempt.fixture_provenance_digest)))$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact tenant scope launch canonical version clause'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_run_owned_fixture_runtime_completion','game_session_run_owned_fixture_runtime_completion',
    'PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY','OWNER_POLICY_RETAINED',
    'Original run-owned fixture STARTING-to-RUNNING row versions and fixture provenance remain immutable provenance, not first OPEN, Account protection or private runtime creation.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1066.1'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1066.1'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1070__game_session_run_owned_fixture_initial_admission.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1070'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1066.1'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1070''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1066.1','V1066.1__transferred_capacity_origin_recovery.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1070'',''V1070__game_session_run_owned_fixture_initial_admission.sql''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog marker'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes FROM (
        SELECT coalesce(max(catalog_epoch),0)+1 AS epoch FROM game_session_runtime_family_catalog_epoch
    ) next_epoch
) seal;
-- [jooq ignore stop]
