-- Disjoint fixed-capability fixture purpose on the existing canonical owners. No history adoption.
-- [jooq ignore start]
LOCK TABLE game_session_canonical_instance_launch, game_session_canonical_initial_admission_intent,
    game_session_canonical_initial_admission_attempt IN ACCESS EXCLUSIVE MODE;
-- [jooq ignore stop]

ALTER TABLE game_session_canonical_instance_launch ADD COLUMN fixture_canonical_version_id UUID;

ALTER TABLE game_session_canonical_initial_admission_intent
    ADD COLUMN authority_kind VARCHAR(24),
    ADD COLUMN fixture_request_bytes BYTEA,
    ADD COLUMN fixture_policy_request_bytes BYTEA,
    ADD COLUMN fixture_policy_seal_evidence_digest VARCHAR(71);

ALTER TABLE game_session_canonical_initial_admission_intent ALTER COLUMN release_attestation_digest DROP NOT NULL;

-- The historical attempt family is SQL-owned and not present in the existing jOOQ DDL model.
-- Keep its ALTER/guards out of generation; real launch/intent columns above remain visible.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_instance_launch ADD CONSTRAINT chk_gs_fixture_canonical_version
    CHECK (fixture_policy_request_bytes IS NULL AND fixture_canonical_version_id IS NULL
        OR fixture_policy_request_bytes IS NOT NULL AND fixture_canonical_version_id IS NOT NULL
           AND fixture_canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID)
    
    NOT VALID;

ALTER TABLE game_session_canonical_initial_admission_intent ADD CONSTRAINT chk_gs_intent_fixture_purpose CHECK (
    (authority_kind IS NULL AND fixture_request_bytes IS NULL AND fixture_policy_request_bytes IS NULL
       AND fixture_policy_seal_evidence_digest IS NULL)
    OR (authority_kind IS NOT NULL AND authority_kind = 'RUN_OWNED_FIXTURE'
        AND fixture_request_bytes IS NOT NULL AND octet_length(fixture_request_bytes) BETWEEN 1 AND 65536
        AND fixture_policy_request_bytes IS NOT NULL AND octet_length(fixture_policy_request_bytes) BETWEEN 1 AND 65536
        AND fixture_policy_seal_evidence_digest IS NOT NULL
        AND fixture_policy_seal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'));

ALTER TABLE game_session_canonical_initial_admission_attempt
    ADD COLUMN authority_kind VARCHAR(24),
    ADD COLUMN fixture_request_bytes BYTEA,
    ADD COLUMN fixture_policy_request_bytes BYTEA,
    ADD COLUMN fixture_policy_seal_evidence_digest VARCHAR(71);

ALTER TABLE game_session_canonical_initial_admission_attempt ADD CONSTRAINT chk_gs_attempt_fixture_purpose CHECK (
    (authority_kind IS NULL AND fixture_request_bytes IS NULL AND fixture_policy_request_bytes IS NULL
       AND fixture_policy_seal_evidence_digest IS NULL)
    OR (authority_kind IS NOT NULL AND authority_kind = 'RUN_OWNED_FIXTURE'
        AND fixture_request_bytes IS NOT NULL AND octet_length(fixture_request_bytes) BETWEEN 1 AND 65536
        AND fixture_policy_request_bytes IS NOT NULL AND octet_length(fixture_policy_request_bytes) BETWEEN 1 AND 65536
        AND fixture_policy_seal_evidence_digest IS NOT NULL
        AND fixture_policy_seal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'));

-- Preserve the exact original V1006 Human/history expression as its own purpose arm.
-- Fixtures retain real source identifiers but never fabricate the Human execution tuple.
ALTER TABLE game_session_canonical_initial_admission_intent
    DROP CONSTRAINT chk_gs_initial_admission_intent_v44_launch_evidence,
    ADD CONSTRAINT chk_gs_initial_admission_intent_v44_launch_evidence CHECK (
        (authority_kind IS NULL AND (
            (catalog_source_intake_operation_id IS NULL
                AND catalog_source_intake_request_id IS NULL
                AND original_start_session_tuple IS NULL)
            OR (catalog_source_intake_operation_id IS NOT NULL
                AND catalog_source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND catalog_source_intake_request_id IS NOT NULL
                AND catalog_source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND original_start_session_tuple IS NOT NULL
                AND octet_length(original_start_session_tuple) BETWEEN 1 AND 262144)))
        OR (authority_kind IS NOT NULL AND authority_kind = 'RUN_OWNED_FIXTURE'
            AND catalog_source_intake_operation_id IS NOT NULL
            AND catalog_source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_source_intake_request_id IS NOT NULL
            AND catalog_source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND original_start_session_tuple IS NULL
            AND fixture_request_bytes IS NOT NULL
            AND fixture_policy_request_bytes IS NOT NULL
            AND fixture_policy_seal_evidence_digest IS NOT NULL));

ALTER TABLE game_session_canonical_initial_admission_intent ADD CONSTRAINT chk_gs_intent_release_purpose CHECK (
    authority_kind IS NULL AND release_attestation_digest IS NOT NULL
    OR authority_kind IS NOT NULL AND authority_kind = 'RUN_OWNED_FIXTURE' AND release_attestation_digest IS NULL AND original_start_session_tuple IS NULL);

ALTER TABLE game_session_canonical_initial_admission_attempt
    DROP CONSTRAINT chk_gs_initial_admission_terminal_requires_protection;

ALTER TABLE game_session_canonical_initial_admission_attempt
    ADD CONSTRAINT chk_gs_initial_admission_terminal_requires_protection CHECK (
        status = 'PENDING' OR account_protection_evidence_version = 1 AND account_protection_evidence_version IS NOT NULL
        OR authority_kind = 'RUN_OWNED_FIXTURE' AND authority_kind IS NOT NULL)
    
    NOT VALID;

-- The existing Human trigger function stays unchanged. Only the separately guarded fixture
-- discriminator selects the new path; retained NULL-purpose rows cannot be reclassified.
DROP TRIGGER game_session_canonical_initial_admission_v44_terminal_evidence ON game_session_canonical_initial_admission_attempt;
CREATE TRIGGER game_session_canonical_initial_admission_v44_terminal_evidence
BEFORE INSERT OR UPDATE ON game_session_canonical_initial_admission_attempt
FOR EACH ROW WHEN (NEW.authority_kind IS NULL)
EXECUTE FUNCTION require_game_session_canonical_initial_admission_account_terminal_evidence();
DROP TRIGGER game_session_canonical_initial_admission_intent_v44_immutable ON game_session_canonical_initial_admission_intent;
CREATE TRIGGER game_session_canonical_initial_admission_intent_v44_immutable
BEFORE INSERT OR UPDATE ON game_session_canonical_initial_admission_intent
FOR EACH ROW WHEN (NEW.authority_kind IS NULL)
EXECUTE FUNCTION require_game_session_canonical_initial_admission_account_terminal_evidence();
CREATE TRIGGER gs_initial_admission_intent_delete
BEFORE DELETE ON game_session_canonical_initial_admission_intent
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_initial_admission_account_terminal_evidence();

CREATE FUNCTION require_gs_fixture_initial_admission()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    launch game_session_canonical_instance_launch%ROWTYPE;
    catalog game_session_canonical_realm_catalog%ROWTYPE;
    intent game_session_canonical_initial_admission_intent%ROWTYPE;
    terminal game_session_canonical_initial_admission_attempt%ROWTYPE;
    runtime game_instances%ROWTYPE;
    frame JSONB;
    selector JSONB;
    activation JSONB;
    hold JSONB;
    request_bytes BYTEA;
    field TEXT;
    expected_digest TEXT;
    digest_bytes BYTEA := ''::BYTEA;
    value_bytes BYTEA;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.authority_kind IS DISTINCT FROM OLD.authority_kind THEN
        RAISE EXCEPTION 'Initial-admission authority cannot be reclassified' USING ERRCODE = '23514';
    END IF;
    IF NEW.authority_kind IS NULL THEN RETURN NEW; END IF;
    IF NEW.authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE' THEN
        RAISE EXCEPTION 'Unsupported initial-admission authority' USING ERRCODE = '23514';
    END IF;
    frame := convert_from(NEW.fixture_request_bytes, 'UTF8')::JSONB;
    request_bytes := decode(frame ->> 'selectorBytesBase64', 'base64');
    selector := convert_from(request_bytes, 'UTF8')::JSONB;
    activation := convert_from(decode(frame ->> 'activationRequestBytesBase64', 'base64'), 'UTF8')::JSONB;
    IF (SELECT count(*) FROM jsonb_object_keys(frame)) <> 5
       OR (SELECT count(*) FROM jsonb_object_keys(selector)) <> 2
       OR (SELECT count(*) FROM jsonb_object_keys(selector -> 'request')) <> 14
       OR (SELECT count(*) FROM jsonb_object_keys(activation)) <> 2
       OR (SELECT count(*) FROM jsonb_object_keys(activation -> 'request')) <> 7
       OR NOT ((selector -> 'request') ? 'expectedPriorPointerVersion')
       OR (activation -> 'request' ->> 'activationRequestId')::UUID IS NULL
       OR (activation -> 'request' ->> 'activationRequestId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
       OR frame ->> 'schema' IS DISTINCT FROM 'world-fixture-initial-admission-hold-request/v1'
       OR frame ->> 'authorityKind' IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
       OR frame ->> 'fixturePolicySealEvidenceDigest' IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
       OR selector ->> 'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-request/v1'
       OR activation ->> 'schema' IS DISTINCT FROM 'world-canonical-instance-fixture-activation-request/v1'
       OR activation -> 'request' ->> 'authorityKind' IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
       OR activation -> 'request' ->> 'fixturePolicySealEvidenceDigest' IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
       OR activation -> 'request' ->> 'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
       OR activation -> 'request' ->> 'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
       OR activation -> 'request' ->> 'expectedLifecycleEpoch' IS DISTINCT FROM '1'
       OR activation -> 'request' ->> 'expectedRowVersion' IS DISTINCT FROM '0'
       OR selector -> 'request' ->> 'targetNamespace' IS DISTINCT FROM NEW.target_namespace
       OR selector -> 'request' ->> 'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
       OR selector -> 'request' ->> 'worldSlug' IS DISTINCT FROM NEW.world_slug
       OR selector -> 'request' ->> 'realmId' IS DISTINCT FROM NEW.realm_id::TEXT
       OR selector -> 'request' ->> 'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
       OR selector -> 'request' ->> 'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
       OR selector -> 'request' ->> 'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
       OR selector -> 'request' ->> 'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
       OR selector -> 'request' ->> 'initialAdmissionRequestId' IS DISTINCT FROM NEW.initial_admission_request_id
       OR selector -> 'request' ->> 'initialAdmissionRequestDigest' IS DISTINCT FROM NEW.request_digest
       OR selector -> 'request' ->> 'activeLifecycleEpoch' IS DISTINCT FROM '2'
       OR selector -> 'request' ->> 'initialAdmissionOrigin' IS DISTINCT FROM 'NO_PRIOR_POINTER'
       OR selector -> 'request' ->> 'expectedPriorPointerVersion' IS NOT NULL THEN
        RAISE EXCEPTION 'Fixture admission requires exact fresh original request and activation' USING ERRCODE = '23514';
    END IF;
    value_bytes := convert_to('game-session-canonical-initial-admission/v1', 'UTF8');
    digest_bytes := int4send(octet_length(value_bytes)) || value_bytes;
    FOREACH field IN ARRAY ARRAY['targetNamespace','canonicalTenantId','worldSlug','realmId','playableStateNamespaceId',
        'playableStateScope','canonicalGameInstanceId','canonicalVersionId','activeLifecycleEpoch','expectedCatalogRevision',
        'initialAdmissionOrigin','expectedPriorPointerVersion','initialAdmissionRequestId'] LOOP
        value_bytes := convert_to(COALESCE(selector -> 'request' ->> field, ''), 'UTF8');
        digest_bytes := digest_bytes || int4send(octet_length(value_bytes)) || value_bytes;
    END LOOP;
    expected_digest := encode(sha256(digest_bytes), 'hex');
    IF NEW.request_digest IS DISTINCT FROM expected_digest THEN
        RAISE EXCEPTION 'Fixture selector digest is not exact' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO launch FROM game_session_canonical_instance_launch
      WHERE target_namespace = NEW.target_namespace AND game_instance_uuid = NEW.canonical_game_instance_id FOR UPDATE;
    SELECT * INTO catalog FROM game_session_canonical_realm_catalog
      WHERE target_namespace = NEW.target_namespace AND realm_id = NEW.realm_id FOR UPDATE;
    SELECT * INTO runtime FROM game_instances WHERE tenant_id = launch.game_session_tenant_id AND id = launch.game_instance_id FOR UPDATE;
    IF launch.target_namespace IS NULL OR catalog.target_namespace IS NULL OR runtime.id IS NULL
       OR launch.original_start_session_tuple IS NOT NULL
       OR launch.fixture_canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
       OR launch.fixture_policy_request_bytes IS DISTINCT FROM NEW.fixture_policy_request_bytes
       OR catalog.fixture_policy_request_bytes IS DISTINCT FROM NEW.fixture_policy_request_bytes
       OR launch.fixture_policy_seal_evidence_digest IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
       OR catalog.fixture_policy_seal_evidence_digest IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
       OR launch.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR catalog.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR launch.canonical_realm_id IS DISTINCT FROM NEW.realm_id
       OR launch.world_slug IS DISTINCT FROM NEW.world_slug OR catalog.world_slug IS DISTINCT FROM NEW.world_slug
       OR launch.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
       OR catalog.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
       OR launch.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
       OR launch.game_instance_id IS DISTINCT FROM NEW.game_instance_id
       OR launch.version_id IS DISTINCT FROM NEW.runtime_version_id
       OR runtime.game_instance_uuid IS DISTINCT FROM NEW.canonical_game_instance_id THEN
        RAISE EXCEPTION 'Fixture admission differs from actual original launch/catalog provenance' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'game_session_canonical_initial_admission_intent' THEN
        IF selector -> 'request' ->> 'expectedCatalogRevision' IS DISTINCT FROM NEW.catalog_revision::TEXT
           OR launch.catalog_revision IS DISTINCT FROM NEW.catalog_revision OR catalog.catalog_revision IS DISTINCT FROM NEW.catalog_revision
           OR NEW.catalog_creation_request_id IS DISTINCT FROM catalog.creation_request_id
           OR NEW.catalog_request_digest IS DISTINCT FROM catalog.request_digest OR NEW.catalog_receipt_digest IS DISTINCT FROM catalog.receipt_digest
           OR NEW.catalog_source_intake_operation_id IS DISTINCT FROM catalog.source_intake_operation_id
           OR NEW.catalog_source_intake_request_id IS DISTINCT FROM catalog.source_intake_request_id
           OR NEW.tenant_association_operation_id IS DISTINCT FROM launch.tenant_association_operation_id
           OR NEW.control_plane_request_id IS DISTINCT FROM launch.control_plane_request_id
           OR NEW.launch_descriptor_id IS DISTINCT FROM launch.launch_descriptor_id
           OR NEW.captured_starting_row_version IS DISTINCT FROM launch.captured_starting_row_version
           OR NEW.descriptor_request_digest IS DISTINCT FROM launch.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'
           OR NEW.descriptor_result_digest IS DISTINCT FROM launch.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'
           OR NEW.original_start_session_tuple IS NOT NULL OR NEW.release_attestation_digest IS NOT NULL
           OR NEW.canonical_hold_request_bytes IS DISTINCT FROM request_bytes
           OR NEW.canonical_lifecycle_request_bytes IS DISTINCT FROM decode(frame ->> 'activationRequestBytesBase64', 'base64') THEN
            RAISE EXCEPTION 'Fixture intent source fences are not exact' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'INSERT' THEN
            IF NEW.state <> 'PENDING_HOLD' OR NEW.hold_identity_bytes IS NOT NULL OR NEW.terminal_at IS NOT NULL
               OR runtime.status <> 'STARTING' OR runtime.row_version <> 0 OR NEW.current_row_version <> 0
               OR launch.captured_starting_row_version <> 0 THEN
                RAISE EXCEPTION 'Fresh fixture intent requires original STARTING before hold' USING ERRCODE = '23514';
            END IF;
        ELSE
            IF (to_jsonb(NEW) - 'state' - 'updated_at' - 'terminal_at' - 'hold_identity_bytes')
                IS DISTINCT FROM (to_jsonb(OLD) - 'state' - 'updated_at' - 'terminal_at' - 'hold_identity_bytes')
               OR NEW.updated_at <= OLD.updated_at THEN
                RAISE EXCEPTION 'Fixture intent immutable provenance changed' USING ERRCODE = '23514';
            END IF;
            IF OLD.state = 'PENDING_HOLD' AND NEW.state = 'HOLD_ATTACHED'
               AND OLD.hold_identity_bytes IS NULL AND NEW.hold_identity_bytes IS NOT NULL AND NEW.terminal_at IS NULL THEN
                hold := convert_from(NEW.hold_identity_bytes, 'UTF8')::JSONB;
                IF hold ->> 'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-identity/v1'
                   OR decode(hold ->> 'requestBytesBase64', 'base64') IS DISTINCT FROM request_bytes
                   OR hold ->> 'holdBindingDigest' IS DISTINCT FROM 'sha256:' || encode(sha256(request_bytes), 'hex')
                   OR (hold ->> 'holdId')::UUID IS NULL OR (hold ->> 'holdFence')::UUID IS NULL
                   OR (hold ->> 'holdId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
                   OR (hold ->> 'holdFence')::UUID = '00000000-0000-0000-0000-000000000000'::UUID THEN
                    RAISE EXCEPTION 'Fixture hold attachment identity mismatch' USING ERRCODE = '23514';
                END IF;
            ELSIF OLD.state = 'HOLD_ATTACHED' AND NEW.state = 'TERMINAL' AND NEW.hold_identity_bytes = OLD.hold_identity_bytes THEN
                SELECT * INTO terminal FROM game_session_canonical_initial_admission_attempt
                  WHERE target_namespace = NEW.target_namespace AND initial_admission_request_id = NEW.initial_admission_request_id;
                IF terminal.status NOT IN ('COMMITTED','ABORTED') OR terminal.status IS NULL
                   OR terminal.fixture_request_bytes IS DISTINCT FROM NEW.fixture_request_bytes
                   OR terminal.terminal_at IS DISTINCT FROM NEW.terminal_at THEN
                    RAISE EXCEPTION 'Fixture intent terminal lacks exact attempt' USING ERRCODE = '23514';
                END IF;
            ELSE RAISE EXCEPTION 'Fixture intent permits only exact attach and terminal' USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSE
        SELECT * INTO intent FROM game_session_canonical_initial_admission_intent
          WHERE target_namespace = NEW.target_namespace AND initial_admission_request_id = NEW.initial_admission_request_id FOR UPDATE;
        hold := convert_from(intent.hold_identity_bytes, 'UTF8')::JSONB;
        IF NEW.account_protection_evidence_version IS NOT NULL OR NEW.account_protection_id IS NOT NULL
           OR NEW.account_protection_fence IS NOT NULL OR NEW.original_owner_attempt_id IS NOT NULL
           OR NEW.original_owner_mutation_id IS NOT NULL OR NEW.original_owner_fence IS NOT NULL
           OR NEW.account_protection_evidence_bytes IS NOT NULL
           OR NEW.origin_kind IS DISTINCT FROM 'NO_PRIOR_POINTER' OR NEW.expected_prior_pointer_version IS NOT NULL
           OR NEW.active_lifecycle_epoch IS DISTINCT FROM 2
           OR intent.state IS DISTINCT FROM 'HOLD_ATTACHED' OR intent.authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
           OR intent.fixture_request_bytes IS DISTINCT FROM NEW.fixture_request_bytes
           OR intent.fixture_policy_request_bytes IS DISTINCT FROM NEW.fixture_policy_request_bytes
           OR intent.fixture_policy_seal_evidence_digest IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
           OR intent.catalog_revision IS DISTINCT FROM NEW.expected_catalog_revision
           OR NEW.hold_id::TEXT IS DISTINCT FROM hold ->> 'holdId'
           OR NEW.hold_fence::TEXT IS DISTINCT FROM hold ->> 'holdFence'
           OR NEW.hold_binding_digest IS DISTINCT FROM hold ->> 'holdBindingDigest' THEN
            RAISE EXCEPTION 'Fixture attempt requires exact attached intent, no Human protection' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'INSERT' THEN
            IF NEW.status IS DISTINCT FROM 'PENDING' OR runtime.status IS DISTINCT FROM 'STARTING' OR runtime.row_version IS DISTINCT FROM 0 THEN
                RAISE EXCEPTION 'New fixture attempt requires original STARTING PENDING' USING ERRCODE = '23514';
            END IF;
        ELSE
            IF (to_jsonb(NEW) - 'status' - 'pointer_id' - 'pointer_version' - 'audit_event_id' - 'commit_proof_digest'
                    - 'abort_proof_digest' - 'abort_reason' - 'updated_at' - 'terminal_at')
               IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'pointer_id' - 'pointer_version' - 'audit_event_id' - 'commit_proof_digest'
                    - 'abort_proof_digest' - 'abort_reason' - 'updated_at' - 'terminal_at')
               OR OLD.status <> 'PENDING' OR NEW.status NOT IN ('COMMITTED','ABORTED') OR NEW.terminal_at IS NULL THEN
                RAISE EXCEPTION 'Fixture terminal/provenance is irreversible' USING ERRCODE = '23514';
            END IF;
            IF NEW.status = 'COMMITTED' AND (runtime.status IS DISTINCT FROM 'RUNNING' OR runtime.row_version IS DISTINCT FROM 1) THEN
                RAISE EXCEPTION 'Fixture OPEN requires exact RUNNING CAS' USING ERRCODE = '23514';
            END IF;
            value_bytes := convert_to('game-session-fixture-initial-admission-terminal/v1', 'UTF8');
            digest_bytes := int4send(octet_length(value_bytes)) || value_bytes
                || int4send(octet_length(NEW.fixture_request_bytes)) || NEW.fixture_request_bytes
                || int4send(octet_length(NEW.fixture_policy_request_bytes)) || NEW.fixture_policy_request_bytes
                || int4send(octet_length(intent.hold_identity_bytes)) || intent.hold_identity_bytes;
            FOREACH field IN ARRAY ARRAY[NEW.status, NEW.fixture_policy_seal_evidence_digest,
                COALESCE(NEW.pointer_id::TEXT,''), COALESCE(NEW.pointer_version::TEXT,''),
                COALESCE(NEW.audit_event_id::TEXT,''),
                to_char(NEW.terminal_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                COALESCE(NEW.abort_reason,'')] LOOP
                value_bytes := convert_to(field, 'UTF8');
                digest_bytes := digest_bytes || int4send(octet_length(value_bytes)) || value_bytes;
            END LOOP;
            IF NEW.status = 'COMMITTED' THEN NEW.commit_proof_digest := 'sha256:' || encode(sha256(digest_bytes),'hex');
            ELSE NEW.abort_proof_digest := 'sha256:' || encode(sha256(digest_bytes),'hex'); END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_fixture_initial_intent_guard BEFORE INSERT OR UPDATE ON game_session_canonical_initial_admission_intent
FOR EACH ROW EXECUTE FUNCTION require_gs_fixture_initial_admission();
CREATE TRIGGER gs_fixture_initial_attempt_guard BEFORE INSERT OR UPDATE ON game_session_canonical_initial_admission_attempt
FOR EACH ROW EXECUTE FUNCTION require_gs_fixture_initial_admission();

-- Actual immutable fixture bundle version, never synthetic Human release evidence.
CREATE FUNCTION gs_fixture_admission_launch_matches(ns TEXT, rid TEXT, version UUID, instance UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
      SELECT 1 FROM game_session_canonical_initial_admission_attempt a
      JOIN game_session_canonical_initial_admission_intent i
        ON i.target_namespace = a.target_namespace AND i.initial_admission_request_id = a.initial_admission_request_id
      JOIN game_session_canonical_instance_launch l
        ON l.target_namespace = a.target_namespace AND l.game_instance_uuid = a.canonical_game_instance_id
      WHERE a.target_namespace = ns AND a.initial_admission_request_id = rid
        AND a.canonical_version_id = version AND a.canonical_game_instance_id = instance
        AND a.authority_kind = 'RUN_OWNED_FIXTURE' AND i.authority_kind = 'RUN_OWNED_FIXTURE'
        AND a.origin_kind = 'NO_PRIOR_POINTER' AND a.expected_prior_pointer_version IS NULL
        AND a.fixture_request_bytes = i.fixture_request_bytes
        AND a.fixture_policy_request_bytes = i.fixture_policy_request_bytes
        AND a.fixture_policy_request_bytes = l.fixture_policy_request_bytes
        AND a.fixture_policy_seal_evidence_digest = l.fixture_policy_seal_evidence_digest
        AND l.fixture_canonical_version_id = version AND l.original_start_session_tuple IS NULL
        AND i.state IN ('HOLD_ATTACHED','TERMINAL') AND a.status IN ('PENDING','COMMITTED'))
$$;

CREATE OR REPLACE FUNCTION require_game_session_canonical_initial_admission_catalog()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.representation_version <> 3 THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM game_session_canonical_realm_catalog catalog
        JOIN game_session_canonical_instance_launch launch
          ON launch.target_namespace = catalog.target_namespace
         AND launch.canonical_tenant_id = catalog.canonical_tenant_id
         AND launch.canonical_realm_id = catalog.realm_id
         AND launch.world_slug = catalog.world_slug
         AND launch.playable_state_namespace_id = catalog.playable_state_namespace_id
         AND launch.game_instance_uuid = NEW.canonical_game_instance_id
         AND launch.game_session_tenant_id = NEW.tenant_id
         AND launch.game_instance_id = NEW.game_instance_id
         AND launch.version_id = NEW.runtime_version_id
         AND (launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = NEW.canonical_version_id::text OR gs_fixture_admission_launch_matches(NEW.target_namespace, NEW.initial_admission_request_id, NEW.canonical_version_id, NEW.canonical_game_instance_id))
         AND launch.playable_state_scope = catalog.state_scope
         AND launch.public_production = catalog.public_production
        JOIN game_instances current
          ON current.tenant_id = launch.game_session_tenant_id
         AND current.id = launch.game_instance_id
         AND current.game_instance_uuid = launch.game_instance_uuid
         AND current.version_id = NEW.runtime_version_id
         AND current.status = 'RUNNING'
        WHERE catalog.target_namespace = NEW.target_namespace
          AND catalog.canonical_tenant_id = NEW.canonical_tenant_id
          AND catalog.realm_id = NEW.realm_id
          AND catalog.world_slug = NEW.world_slug
          AND catalog.realm_slug = NEW.realm_slug
          AND catalog.catalog_revision = NEW.catalog_revision
          AND catalog.playable_state_namespace_id = NEW.playable_state_namespace_id
          AND catalog.state_scope = NEW.state_scope
          AND catalog.visible = NEW.visible
          AND catalog.public_production = NEW.public_production_realm
          AND catalog.character_creation_policy = NEW.character_creation_policy
    ) THEN
        RAISE EXCEPTION 'Canonical OPEN pointer requires exact catalog and launch owner evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION reserve_game_session_tenant_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_kind VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id THEN
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <= 0 THEN
        RAISE EXCEPTION 'Game Session tenant scope must be positive'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_tenant_scope_reservation_positive';
    END IF;

    INSERT INTO game_session_tenant_scope_reservation
        (game_session_tenant_id, reservation_kind)
    VALUES (NEW.tenant_id, 'LEGACY_OCCUPIED')
    ON CONFLICT (game_session_tenant_id) DO NOTHING;

    SELECT reservation_kind INTO current_kind
      FROM game_session_tenant_scope_reservation
     WHERE game_session_tenant_id = NEW.tenant_id
     FOR KEY SHARE;

    IF current_kind = 'FRESH_SOURCE_BOUND' AND TG_TABLE_NAME = 'game_instances'
       AND EXISTS (
           SELECT 1
             FROM game_session_tenant_canonical_claim claim
             JOIN game_session_fresh_tenant_association association
               ON association.target_namespace = claim.target_namespace
              AND association.canonical_tenant_id = claim.canonical_tenant_id
              AND association.legacy_game_session_tenant_id = claim.legacy_game_session_tenant_id
              AND association.association_operation_id = claim.association_operation_id
              AND association.association_kind = claim.association_kind
            WHERE claim.legacy_game_session_tenant_id = NEW.tenant_id
              AND claim.association_kind = 'FRESH_SOURCE_BOUND'
              AND association.association_kind = 'FRESH_SOURCE_BOUND'
       ) THEN
        RETURN NEW;
    END IF;

    IF current_kind = 'FRESH_SOURCE_BOUND'
       AND TG_TABLE_NAME IN ('gameplay_admission_pointer', 'gameplay_admission_pointer_event') THEN
      IF NEW.representation_version = 3
         AND NEW.admission_state = 'OPEN'
         AND EXISTS (
           SELECT 1
             FROM game_session_canonical_initial_admission_attempt attempt
             JOIN game_session_canonical_instance_launch launch
               ON launch.target_namespace = attempt.target_namespace
              AND launch.canonical_tenant_id = attempt.canonical_tenant_id
              AND launch.canonical_realm_id = attempt.realm_id
              AND launch.world_slug = attempt.world_slug
              AND launch.playable_state_namespace_id = attempt.playable_state_namespace_id
              AND launch.playable_state_scope = attempt.playable_state_scope
              AND launch.game_session_tenant_id = attempt.game_session_tenant_id
              AND launch.game_instance_id = attempt.game_instance_id
              AND launch.game_instance_uuid = attempt.canonical_game_instance_id
              AND launch.version_id = attempt.runtime_version_id
              AND (launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
                  = attempt.canonical_version_id::text OR gs_fixture_admission_launch_matches(attempt.target_namespace, attempt.initial_admission_request_id, attempt.canonical_version_id, attempt.canonical_game_instance_id))
             JOIN game_session_tenant_canonical_claim claim
               ON claim.target_namespace = launch.target_namespace
              AND claim.canonical_tenant_id = launch.canonical_tenant_id
              AND claim.legacy_game_session_tenant_id = launch.game_session_tenant_id
              AND claim.association_operation_id = launch.tenant_association_operation_id
              AND claim.association_request_id = launch.tenant_association_request_id
              AND claim.association_kind = launch.tenant_association_kind
              AND claim.association_kind = 'FRESH_SOURCE_BOUND'
              AND claim.reservation_kind = 'FRESH_SOURCE_BOUND'
             JOIN game_session_fresh_tenant_association source_association
               ON source_association.target_namespace = claim.target_namespace
              AND source_association.canonical_tenant_id = claim.canonical_tenant_id
              AND source_association.legacy_game_session_tenant_id = claim.legacy_game_session_tenant_id
              AND source_association.association_operation_id = claim.association_operation_id
              AND source_association.association_request_id = launch.tenant_association_request_id
              AND source_association.source_schema_version = launch.tenant_source_schema_version
              AND source_association.source_target_namespace = launch.tenant_source_target_namespace
              AND source_association.source_request_id = launch.tenant_source_request_id
              AND source_association.source_canonical_tenant_id = launch.tenant_source_canonical_tenant_id
              AND source_association.source_game_row_id = launch.tenant_source_game_row_id
              AND source_association.source_game_tenant_key = launch.tenant_source_game_tenant_key
              AND source_association.provenance_kind = launch.tenant_source_provenance_kind
              AND source_association.association_kind = launch.tenant_association_kind
            WHERE attempt.status = 'PENDING'
              AND attempt.target_namespace = NEW.target_namespace
              AND attempt.initial_admission_request_id = NEW.initial_admission_request_id
              AND attempt.request_digest = NEW.initial_admission_request_digest
              AND attempt.canonical_tenant_id = NEW.canonical_tenant_id
              AND attempt.world_slug = NEW.world_slug
              AND attempt.realm_id = NEW.realm_id
              AND attempt.playable_state_namespace_id = NEW.playable_state_namespace_id
              AND attempt.playable_state_scope = NEW.state_scope
              AND attempt.canonical_game_instance_id = NEW.canonical_game_instance_id
              AND attempt.canonical_version_id = NEW.canonical_version_id
              AND attempt.game_session_tenant_id = NEW.tenant_id
              AND attempt.game_instance_id = NEW.game_instance_id
              AND attempt.runtime_version_id = NEW.runtime_version_id
              AND attempt.expected_catalog_revision = NEW.catalog_revision
              AND attempt.origin_kind = NEW.initial_admission_origin_kind
              AND attempt.expected_prior_pointer_version IS NOT DISTINCT FROM
                  NEW.initial_admission_prior_pointer_version
              AND attempt.active_lifecycle_epoch = NEW.initial_admission_active_epoch
              AND attempt.hold_id = NEW.initial_admission_hold_id
              AND attempt.hold_fence = NEW.initial_admission_hold_fence
              AND attempt.hold_binding_digest = NEW.initial_admission_hold_binding_digest
         ) THEN
        IF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
            IF NEW.last_updated_by IS DISTINCT FROM 'game-session-canonical-initial-admission'
               OR NEW.last_update_reason IS DISTINCT FROM 'World-held initial admission' THEN
                RAISE EXCEPTION 'Canonical OPEN pointer requires its exact initial-admission owner audit identity'
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_initial_admission_owner_write';
            END IF;
        END IF;
        IF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
            IF NEW.control_plane_request_id IS DISTINCT FROM NEW.initial_admission_request_id
               OR NEW.actor_principal IS DISTINCT FROM 'game-session-canonical-initial-admission'
               OR NEW.reason IS DISTINCT FROM 'World-held initial admission' THEN
                RAISE EXCEPTION 'Canonical OPEN audit event requires its exact initial-admission owner identity'
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_initial_admission_owner_write';
            END IF;
        END IF;
        RETURN NEW;
      END IF;
    END IF;

    IF current_kind IS DISTINCT FROM 'LEGACY_OCCUPIED' THEN
        RAISE EXCEPTION 'Numeric-only tenant scope cannot use a fresh source-bound key'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_numeric_fresh_tenant_scope_denied';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION require_game_session_canonical_initial_admission_atomic_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    selected_namespace character varying(63);
    selected_request_id character varying(128);
    selected_status character varying(16);
BEGIN
    IF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
        IF NEW.representation_version <> 3 THEN
            RETURN NULL;
        END IF;
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
        IF NEW.representation_version <> 3 THEN
            RETURN NULL;
        END IF;
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    ELSE
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    END IF;

    SELECT status INTO selected_status
    FROM game_session_canonical_initial_admission_attempt
    WHERE target_namespace = selected_namespace
      AND initial_admission_request_id = selected_request_id;

    IF selected_status = 'PENDING' THEN
        IF EXISTS (
            SELECT 1 FROM gameplay_admission_pointer
            WHERE representation_version = 3
              AND target_namespace = selected_namespace
              AND initial_admission_request_id = selected_request_id
        ) OR EXISTS (
            SELECT 1 FROM gameplay_admission_pointer_event
            WHERE representation_version = 3
              AND target_namespace = selected_namespace
              AND initial_admission_request_id = selected_request_id
        ) THEN
            RAISE EXCEPTION 'PENDING canonical initial-admission attempt cannot carry pointer evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF selected_status = 'COMMITTED' AND EXISTS (
        SELECT 1
        FROM game_session_canonical_initial_admission_attempt attempt
        JOIN gameplay_admission_pointer pointer
          ON pointer.id = attempt.pointer_id
         AND pointer.representation_version = 3
         AND pointer.target_namespace = attempt.target_namespace
         AND pointer.canonical_tenant_id = attempt.canonical_tenant_id
         AND pointer.realm_id = attempt.realm_id
         AND pointer.world_slug = attempt.world_slug
         AND pointer.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND pointer.state_scope = attempt.playable_state_scope
         AND pointer.canonical_game_instance_id = attempt.canonical_game_instance_id
         AND pointer.canonical_version_id = attempt.canonical_version_id
         AND pointer.runtime_version_id = attempt.runtime_version_id
         AND pointer.tenant_id = attempt.game_session_tenant_id
         AND pointer.game_instance_id = attempt.game_instance_id
         AND pointer.pointer_version = attempt.pointer_version
         AND pointer.catalog_revision = attempt.expected_catalog_revision
         AND pointer.initial_admission_request_id = attempt.initial_admission_request_id
         AND pointer.initial_admission_request_digest = attempt.request_digest
         AND pointer.initial_admission_origin_kind = attempt.origin_kind
         AND pointer.initial_admission_prior_pointer_version IS NOT DISTINCT FROM attempt.expected_prior_pointer_version
         AND pointer.initial_admission_active_epoch = attempt.active_lifecycle_epoch
         AND pointer.initial_admission_hold_id = attempt.hold_id
         AND pointer.initial_admission_hold_fence = attempt.hold_fence
         AND pointer.initial_admission_hold_binding_digest = attempt.hold_binding_digest
         AND pointer.admission_state = 'OPEN'
        JOIN gameplay_admission_pointer_event event
          ON event.id = attempt.audit_event_id
         AND event.representation_version = 3
         AND event.target_namespace = attempt.target_namespace
         AND event.canonical_tenant_id = attempt.canonical_tenant_id
         AND event.realm_id = attempt.realm_id
         AND event.world_slug = attempt.world_slug
         AND event.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND event.state_scope = attempt.playable_state_scope
         AND event.canonical_game_instance_id = attempt.canonical_game_instance_id
         AND event.canonical_version_id = attempt.canonical_version_id
         AND event.runtime_version_id = attempt.runtime_version_id
         AND event.tenant_id = attempt.game_session_tenant_id
         AND event.game_instance_id = attempt.game_instance_id
         AND event.pointer_version = attempt.pointer_version
         AND event.catalog_revision = attempt.expected_catalog_revision
         AND event.initial_admission_request_id = attempt.initial_admission_request_id
         AND event.control_plane_request_id = attempt.initial_admission_request_id
         AND event.initial_admission_request_digest = attempt.request_digest
         AND event.initial_admission_origin_kind = attempt.origin_kind
         AND event.initial_admission_prior_pointer_version IS NOT DISTINCT FROM attempt.expected_prior_pointer_version
         AND event.initial_admission_active_epoch = attempt.active_lifecycle_epoch
         AND event.initial_admission_hold_id = attempt.hold_id
         AND event.initial_admission_hold_fence = attempt.hold_fence
         AND event.initial_admission_hold_binding_digest = attempt.hold_binding_digest
         AND event.admission_state = 'OPEN'
         AND event.prepared_version_upgrade_id IS NULL
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = attempt.target_namespace
         AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
         AND catalog.realm_id = attempt.realm_id
         AND catalog.world_slug = attempt.world_slug
         AND catalog.catalog_revision = attempt.expected_catalog_revision
         AND catalog.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND catalog.state_scope = attempt.playable_state_scope
         AND catalog.visible = pointer.visible
         AND catalog.public_production = pointer.public_production_realm
         AND catalog.character_creation_policy = pointer.character_creation_policy
        JOIN game_session_canonical_instance_launch launch
          ON launch.target_namespace = attempt.target_namespace
         AND launch.canonical_tenant_id = attempt.canonical_tenant_id
         AND launch.canonical_realm_id = attempt.realm_id
         AND launch.world_slug = attempt.world_slug
         AND launch.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND launch.playable_state_scope = attempt.playable_state_scope
         AND launch.public_production = TRUE
         AND launch.game_instance_uuid = attempt.canonical_game_instance_id
         AND launch.game_session_tenant_id = attempt.game_session_tenant_id
         AND launch.game_instance_id = attempt.game_instance_id
         AND launch.version_id = attempt.runtime_version_id
         AND (launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = attempt.canonical_version_id::text OR gs_fixture_admission_launch_matches(attempt.target_namespace, attempt.initial_admission_request_id, attempt.canonical_version_id, attempt.canonical_game_instance_id))
        JOIN game_instances current
          ON current.tenant_id = launch.game_session_tenant_id
         AND current.id = launch.game_instance_id
         AND current.game_instance_uuid = launch.game_instance_uuid
         AND current.version_id = attempt.runtime_version_id
         AND current.status = 'RUNNING'
        WHERE attempt.target_namespace = selected_namespace
          AND attempt.initial_admission_request_id = selected_request_id
          AND attempt.status = 'COMMITTED'
          AND attempt.pointer_version = CASE
              WHEN attempt.origin_kind = 'NO_PRIOR_POINTER' THEN 1
              ELSE attempt.expected_prior_pointer_version + 1 END
          AND attempt.terminal_at = (pointer.updated_at AT TIME ZONE 'UTC')
          AND attempt.terminal_at = (event.occurred_at AT TIME ZONE 'UTC')
          AND ((attempt.origin_kind = 'NO_PRIOR_POINTER'
                AND attempt.expected_prior_pointer_version IS NULL
                AND attempt.prior_pointer_request_id IS NULL
                AND attempt.prior_pointer_request_digest IS NULL
                AND attempt.prior_pointer_audit_event_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM game_session_canonical_closed_admission_pointer_request origin
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id)
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer other_pointer
                    WHERE other_pointer.id <> pointer.id
                      AND (other_pointer.realm_id = attempt.realm_id
                        OR (other_pointer.target_namespace = attempt.target_namespace
                            AND other_pointer.canonical_tenant_id = attempt.canonical_tenant_id)
                        OR (other_pointer.world_slug = attempt.world_slug
                            AND other_pointer.realm_slug = catalog.realm_slug)
                        OR (other_pointer.tenant_id = attempt.game_session_tenant_id
                            AND other_pointer.game_instance_id = attempt.game_instance_id)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer_event other_event
                    WHERE other_event.id <> event.id
                      AND (other_event.realm_id = attempt.realm_id
                        OR (other_event.target_namespace = attempt.target_namespace
                            AND other_event.canonical_tenant_id = attempt.canonical_tenant_id)
                        OR (other_event.world_slug = attempt.world_slug
                            AND other_event.realm_slug = catalog.realm_slug)
                        OR (other_event.tenant_id = attempt.game_session_tenant_id
                            AND other_event.game_instance_id = attempt.game_instance_id)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_catalog legacy_catalog
                    WHERE legacy_catalog.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_catalog.realm_id = attempt.realm_id
                        OR (legacy_catalog.world_slug = attempt.world_slug
                            AND legacy_catalog.realm_slug = catalog.realm_slug)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_attempt legacy_attempt
                    WHERE legacy_attempt.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_attempt.realm_id = attempt.realm_id
                        OR legacy_attempt.game_instance_id = attempt.game_instance_id)))
            OR (attempt.origin_kind = 'EXPECT_CLOSED'
                AND attempt.expected_prior_pointer_version = 1
                AND attempt.prior_pointer_request_id IS NOT NULL
                AND attempt.prior_pointer_request_digest IS NOT NULL
                AND attempt.prior_pointer_audit_event_id IS NOT NULL
                AND EXISTS (
                    SELECT 1
                    FROM game_session_canonical_closed_admission_pointer_request origin
                    JOIN gameplay_admission_pointer_event origin_event
                      ON origin_event.id = origin.audit_event_id
                     AND origin_event.representation_version = 2
                     AND origin_event.admission_state = 'CLOSED'
                     AND origin_event.pointer_version = 1
                     AND origin_event.target_namespace = origin.target_namespace
                     AND origin_event.canonical_tenant_id = origin.canonical_tenant_id
                     AND origin_event.realm_id = origin.realm_id
                     AND origin_event.catalog_revision = origin.catalog_revision
                     AND origin_event.control_plane_request_id = origin.request_id::text
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.request_id = attempt.prior_pointer_request_id
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id
                      AND origin.request_digest = attempt.prior_pointer_request_digest
                      AND origin.audit_event_id = attempt.prior_pointer_audit_event_id
                      AND origin.pointer_version = attempt.expected_prior_pointer_version
                      AND origin.catalog_revision = attempt.expected_catalog_revision)))
    ) THEN
        RETURN NULL;
    END IF;

    IF selected_status = 'ABORTED' AND EXISTS (
        SELECT 1
        FROM game_session_canonical_initial_admission_attempt attempt
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = attempt.target_namespace
         AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
         AND catalog.realm_id = attempt.realm_id
         AND catalog.world_slug = attempt.world_slug
         AND catalog.catalog_revision = attempt.expected_catalog_revision
         AND catalog.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND catalog.state_scope = attempt.playable_state_scope
        WHERE attempt.target_namespace = selected_namespace
          AND attempt.initial_admission_request_id = selected_request_id
          AND attempt.status = 'ABORTED'
          AND attempt.pointer_id IS NULL
          AND attempt.audit_event_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM gameplay_admission_pointer pointer
              WHERE pointer.representation_version = 3
                AND pointer.target_namespace = attempt.target_namespace
                AND pointer.initial_admission_request_id = attempt.initial_admission_request_id)
          AND NOT EXISTS (
              SELECT 1 FROM gameplay_admission_pointer_event event
              WHERE event.representation_version = 3
                AND event.target_namespace = attempt.target_namespace
                AND event.initial_admission_request_id = attempt.initial_admission_request_id)
          AND ((attempt.origin_kind = 'NO_PRIOR_POINTER'
                AND (
                    -- The protection-bound terminal has already been checked against the exact
                    -- retained owner, intent and Account evidence by the independent terminal
                    -- guard. This negative result never consumes or changes another pointer.
                    (attempt.account_protection_id IS NOT NULL
                        AND attempt.expected_prior_pointer_version IS NULL
                        AND attempt.prior_pointer_request_id IS NULL
                        AND attempt.prior_pointer_request_digest IS NULL
                        AND attempt.prior_pointer_audit_event_id IS NULL)
                    OR (attempt.authority_kind = 'RUN_OWNED_FIXTURE'
                        AND attempt.expected_prior_pointer_version IS NULL
                        AND attempt.prior_pointer_request_id IS NULL
                        AND attempt.prior_pointer_request_digest IS NULL
                        AND attempt.prior_pointer_audit_event_id IS NULL)
                    OR (
                        NOT EXISTS (
                            SELECT 1 FROM gameplay_admission_pointer pointer
                            WHERE pointer.realm_id = attempt.realm_id
                              OR (pointer.target_namespace = attempt.target_namespace
                                  AND pointer.canonical_tenant_id = attempt.canonical_tenant_id)
                              OR (pointer.world_slug = attempt.world_slug
                                  AND pointer.realm_slug = catalog.realm_slug)
                              OR (pointer.tenant_id = attempt.game_session_tenant_id
                                  AND pointer.game_instance_id = attempt.game_instance_id))
                        AND NOT EXISTS (
                            SELECT 1 FROM gameplay_admission_pointer_event event
                            WHERE event.realm_id = attempt.realm_id
                              OR (event.target_namespace = attempt.target_namespace
                                  AND event.canonical_tenant_id = attempt.canonical_tenant_id)
                              OR (event.world_slug = attempt.world_slug
                                  AND event.realm_slug = catalog.realm_slug)
                              OR (event.tenant_id = attempt.game_session_tenant_id
                                  AND event.game_instance_id = attempt.game_instance_id))
                        AND NOT EXISTS (
                            SELECT 1 FROM game_session_canonical_closed_admission_pointer_request origin
                            WHERE origin.target_namespace = attempt.target_namespace
                              AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                              AND origin.realm_id = attempt.realm_id)
                        AND NOT EXISTS (
                            SELECT 1 FROM gameplay_initial_admission_bind_catalog legacy_catalog
                            WHERE legacy_catalog.tenant_id = attempt.game_session_tenant_id
                              AND (legacy_catalog.realm_id = attempt.realm_id
                                OR (legacy_catalog.world_slug = attempt.world_slug
                                    AND legacy_catalog.realm_slug = catalog.realm_slug)))
                        AND NOT EXISTS (
                            SELECT 1 FROM gameplay_initial_admission_bind_attempt legacy_attempt
                            WHERE legacy_attempt.tenant_id = attempt.game_session_tenant_id
                              AND (legacy_attempt.realm_id = attempt.realm_id
                                OR legacy_attempt.game_instance_id = attempt.game_instance_id))
                    )))
            OR (attempt.origin_kind = 'EXPECT_CLOSED'
                AND attempt.expected_prior_pointer_version = 1
                AND EXISTS (
                    SELECT 1
                    FROM game_session_canonical_closed_admission_pointer_request origin
                    JOIN gameplay_admission_pointer_event origin_event
                      ON origin_event.id = origin.audit_event_id
                     AND origin_event.representation_version = 2
                     AND origin_event.admission_state = 'CLOSED'
                     AND origin_event.pointer_version = 1
                     AND origin_event.target_namespace = origin.target_namespace
                     AND origin_event.canonical_tenant_id = origin.canonical_tenant_id
                     AND origin_event.realm_id = origin.realm_id
                     AND origin_event.catalog_revision = origin.catalog_revision
                     AND origin_event.control_plane_request_id = origin.request_id::text
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.request_id = attempt.prior_pointer_request_id
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id
                      AND origin.request_digest = attempt.prior_pointer_request_digest
                      AND origin.audit_event_id = attempt.prior_pointer_audit_event_id
                      AND origin.pointer_version = attempt.expected_prior_pointer_version
                      AND origin.catalog_revision = attempt.expected_catalog_revision
                      -- Protected abort is historical; legacy abort still requires current CLOSED.
                      AND (attempt.account_protection_id IS NOT NULL
                        OR EXISTS (
                            SELECT 1 FROM gameplay_admission_pointer pointer
                            WHERE pointer.representation_version = 2
                              AND pointer.target_namespace = attempt.target_namespace
                              AND pointer.canonical_tenant_id = attempt.canonical_tenant_id
                              AND pointer.realm_id = attempt.realm_id
                              AND pointer.pointer_version = attempt.expected_prior_pointer_version
                              AND pointer.catalog_revision = attempt.expected_catalog_revision
                              AND pointer.admission_state = 'CLOSED')))))
    ) THEN
        RETURN NULL;
    END IF;

    RAISE EXCEPTION 'Canonical initial-admission attempt lacks matching atomic pointer/audit or fenced abort evidence'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION require_gs_fixture_initial_atomic_intent()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    intent game_session_canonical_initial_admission_intent%ROWTYPE;
    attempt game_session_canonical_initial_admission_attempt%ROWTYPE;
BEGIN
    IF NEW.authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE' THEN RETURN NULL; END IF;
    SELECT * INTO intent FROM game_session_canonical_initial_admission_intent
      WHERE target_namespace = NEW.target_namespace AND initial_admission_request_id = NEW.initial_admission_request_id;
    SELECT * INTO attempt FROM game_session_canonical_initial_admission_attempt
      WHERE target_namespace = NEW.target_namespace AND initial_admission_request_id = NEW.initial_admission_request_id;
    IF intent.state = 'PENDING_HOLD' AND attempt.target_namespace IS NULL THEN RETURN NULL; END IF;
    IF intent.fixture_request_bytes IS DISTINCT FROM attempt.fixture_request_bytes
       OR intent.fixture_policy_request_bytes IS DISTINCT FROM attempt.fixture_policy_request_bytes
       OR intent.fixture_policy_seal_evidence_digest IS DISTINCT FROM attempt.fixture_policy_seal_evidence_digest
       OR attempt.authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
       OR (attempt.status = 'PENDING' AND intent.state IS DISTINCT FROM 'HOLD_ATTACHED')
       OR (attempt.status IN ('COMMITTED','ABORTED') AND
            (intent.state IS DISTINCT FROM 'TERMINAL' OR intent.terminal_at IS DISTINCT FROM attempt.terminal_at))
       OR attempt.status IS NULL THEN
        RAISE EXCEPTION 'Fixture intent and attempt must commit one exact atomic outcome' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_fixture_intent_atomic AFTER INSERT OR UPDATE ON game_session_canonical_initial_admission_intent
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_gs_fixture_initial_atomic_intent();
CREATE CONSTRAINT TRIGGER gs_fixture_attempt_atomic AFTER INSERT OR UPDATE ON game_session_canonical_initial_admission_attempt
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_gs_fixture_initial_atomic_intent();

CREATE OR REPLACE FUNCTION reject_game_session_canonical_initial_admission_protection_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_session_canonical_initial_admission_attempt
               WHERE account_protection_evidence_bytes IS NOT NULL)
       OR EXISTS (SELECT 1 FROM game_session_canonical_initial_admission_intent
               WHERE hold_identity_bytes IS NOT NULL OR authority_kind = 'RUN_OWNED_FIXTURE') THEN
        RAISE EXCEPTION 'Canonical initial-admission Account protection evidence cannot be truncated'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
-- [jooq ignore stop]
