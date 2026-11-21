-- Local run-owned fixture World activation uses its own immutable owner operation. The existing
-- V1016/V1038 operation and Account-protection fence remain the complete Human authority path.
CREATE TABLE world_run_owned_fixture_activation_operation (
    activation_request_id UUID PRIMARY KEY,
    canonical_game_instance_id UUID NOT NULL,
    world_instance_id BIGINT NOT NULL,
    expected_lifecycle_epoch BIGINT NOT NULL CHECK (expected_lifecycle_epoch = 1),
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version = 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome = 'COMMITTED'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) > 0),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    preparing_evidence_bytes BYTEA NOT NULL CHECK (octet_length(preparing_evidence_bytes) > 0),
    preparing_evidence_digest VARCHAR(71) NOT NULL CHECK (preparing_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    preset_plan_digest VARCHAR(71) NOT NULL CHECK (preset_plan_digest ~ '^sha256:[0-9a-f]{64}$'),
    fixture_policy_seal_evidence_digest VARCHAR(71) NOT NULL CHECK (fixture_policy_seal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    preparation_input_digest VARCHAR(71) NOT NULL CHECK (preparation_input_digest ~ '^sha256:[0-9a-f]{64}$'),
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    result_digest VARCHAR(71) NOT NULL CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    result_lifecycle_epoch BIGINT NOT NULL CHECK (result_lifecycle_epoch = 2),
    result_row_version BIGINT NOT NULL CHECK (result_row_version = 1),
    preset_plan_json TEXT NOT NULL CHECK (length(preset_plan_json) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_world_fixture_activation_association
        FOREIGN KEY (canonical_game_instance_id)
        REFERENCES world_canonical_instance_association(canonical_game_instance_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_world_fixture_activation_request_pair
        UNIQUE (activation_request_id, canonical_game_instance_id),
    CONSTRAINT ck_world_fixture_activation_request_non_nil
        CHECK (activation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID)
);
CREATE UNIQUE INDEX uq_world_fixture_activation_one_commit_per_instance
    ON world_run_owned_fixture_activation_operation(canonical_game_instance_id);
CREATE TABLE world_run_owned_fixture_activation_manifest (
    transaction_id BIGINT NOT NULL,
    activation_request_id UUID NOT NULL,
    canonical_game_instance_id UUID NOT NULL,
    world_instance_id BIGINT NOT NULL,
    expected_lifecycle_epoch BIGINT NOT NULL,
    expected_row_version BIGINT NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    result_digest VARCHAR(71) NOT NULL,
    PRIMARY KEY (transaction_id, activation_request_id),
    UNIQUE (transaction_id, world_instance_id),
    FOREIGN KEY (activation_request_id, canonical_game_instance_id)
        REFERENCES world_run_owned_fixture_activation_operation(activation_request_id, canonical_game_instance_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE world_run_owned_fixture_tenant_manifest (
    transaction_id BIGINT NOT NULL,
    activation_request_id UUID NOT NULL REFERENCES world_run_owned_fixture_activation_operation(activation_request_id)
        DEFERRABLE INITIALLY DEFERRED,
    expected_old JSONB NOT NULL,
    expected_new JSONB NOT NULL,
    PRIMARY KEY (transaction_id, activation_request_id)
);

-- [jooq ignore start]
REVOKE ALL ON world_run_owned_fixture_activation_operation FROM PUBLIC;
REVOKE ALL ON world_run_owned_fixture_activation_manifest FROM PUBLIC;
REVOKE ALL ON world_run_owned_fixture_tenant_manifest FROM PUBLIC;

CREATE FUNCTION world_validate_run_owned_fixture_activation_operation()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    request JSONB;
    preparing JSONB;
    result JSONB;
    plan JSONB;
    association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    instance "${serviceSchema}".world_instance%ROWTYPE;
    activated "${serviceSchema}".world_instance%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Run-owned fixture activation evidence is immutable' USING ERRCODE = '55000';
    END IF;
    request := convert_from(NEW.request_bytes, 'UTF8')::JSONB;
    preparing := convert_from(NEW.preparing_evidence_bytes, 'UTF8')::JSONB;
    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    IF NEW.request_digest IS DISTINCT FROM ('sha256:' || encode(sha256(NEW.request_bytes), 'hex'))
        OR NEW.preparing_evidence_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(NEW.preparing_evidence_bytes), 'hex'))
        OR NEW.result_digest IS DISTINCT FROM ('sha256:' || encode(sha256(NEW.result_bytes), 'hex'))
        OR jsonb_typeof(request) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(request)) <> 2
        OR request->>'schema' IS DISTINCT FROM 'world-canonical-instance-fixture-activation-request/v1'
        OR jsonb_typeof(request->'request') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(request->'request')) <> 7
        OR request->'request'->>'activationRequestId' IS DISTINCT FROM NEW.activation_request_id::TEXT
        OR request->'request'->>'authorityKind' IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR request->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR request->'request'->>'expectedLifecycleEpoch' IS DISTINCT FROM '1'
        OR request->'request'->>'expectedRowVersion' IS DISTINCT FROM '0'
        OR request->'request'->>'fixturePolicySealEvidenceDigest' IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
        OR jsonb_typeof(preparing) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(preparing)) <> 14
        OR preparing->>'schema' IS DISTINCT FROM 'world-canonical-instance-fixture-preparing-evidence/v1'
        OR preparing->>'authorityKind' IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR preparing->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR preparing->>'fixturePolicySealEvidenceDigest' IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
        OR preparing->>'preparationInputDigest' IS DISTINCT FROM NEW.preparation_input_digest
        OR preparing->>'presetPlanDigest' IS DISTINCT FROM NEW.preset_plan_digest
        OR decode(preparing->>'requestBytesBase64', 'base64') IS DISTINCT FROM NEW.request_bytes
        OR jsonb_typeof(result) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(result)) <> 5
        OR result->>'schema' IS DISTINCT FROM 'world-canonical-instance-fixture-activation-result/v1'
        OR decode(result->>'requestBytesBase64', 'base64') IS DISTINCT FROM NEW.request_bytes
        OR result->>'lifecycleStatus' IS DISTINCT FROM 'ACTIVE'
        OR result->>'lifecycleEpoch' IS DISTINCT FROM '2'
        OR result->>'rowVersion' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Run-owned fixture activation operation bytes are incomplete or inconsistent'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT association
    FROM "${serviceSchema}".world_canonical_instance_association
    WHERE canonical_game_instance_id = NEW.canonical_game_instance_id FOR UPDATE;
    SELECT * INTO STRICT instance
    FROM "${serviceSchema}".world_instance
    WHERE id = association.world_instance_id FOR UPDATE;
    IF request->'request'->>'canonicalTenantId' IS DISTINCT FROM association.canonical_tenant_id::TEXT
        OR association.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR association.world_instance_id IS DISTINCT FROM NEW.world_instance_id
        OR association.fixture_provenance_digest IS DISTINCT FROM NEW.fixture_policy_seal_evidence_digest
        OR association.preparation_input_digest IS DISTINCT FROM NEW.preparation_input_digest
        OR association.control_plane_request_id IS DISTINCT FROM preparing->>'fixtureOperationId'
        OR NEW.world_instance_id::TEXT IS DISTINCT FROM preparing->>'worldInstanceId'
        OR association.intake_receipt_digest IS DISTINCT FROM preparing->>'sourceReceiptDigest'
        OR association.version_identity_operation_id::TEXT IS DISTINCT FROM preparing->>'versionIdentityOperationId'
        OR association.local_tenant_key::TEXT IS DISTINCT FROM preparing->>'worldPrivateTenantKey'
        OR association.private_game_instance_key::TEXT IS DISTINCT FROM preparing->>'worldPrivateGameInstanceKey'
        OR association.local_version_key::TEXT IS DISTINCT FROM preparing->>'worldPrivateVersionKey'
        OR instance.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR instance.canonical_game_instance_id IS DISTINCT FROM association.canonical_game_instance_id
        OR instance.tenant_id IS DISTINCT FROM association.local_tenant_key
        OR instance.game_instance_id IS DISTINCT FROM association.private_game_instance_key
        OR instance.version_id IS DISTINCT FROM association.local_version_key
        OR instance.status IS DISTINCT FROM 'PREPARING'
        OR instance.lifecycle_epoch IS DISTINCT FROM 1
        OR instance.row_version IS DISTINCT FROM 0
        OR instance.run_owned_fixture_preparing_lifecycle_epoch IS DISTINCT FROM 1
        OR 'sha256:' || instance.run_owned_fixture_binding_sha256 IS DISTINCT FROM association.preparation_input_digest THEN
        RAISE EXCEPTION 'Run-owned fixture activation lost its exact original World association or PREPARING row'
            USING ERRCODE = '23514';
    END IF;
    plan := NEW.preset_plan_json::JSONB;
    IF NEW.preset_plan_digest IS DISTINCT FROM
            ('sha256:' || encode(sha256(convert_to(NEW.preset_plan_json, 'UTF8')), 'hex'))
        OR NEW.preset_plan_json IS DISTINCT FROM (
            SELECT input_json FROM "${serviceSchema}".world_canonical_instance_preparation
            WHERE canonical_game_instance_id = NEW.canonical_game_instance_id)
        OR NEW.preset_plan_digest IS DISTINCT FROM preparing->>'presetPlanDigest'
        OR plan->>'lifecycleBindingDigest' IS DISTINCT FROM association.preparation_input_digest
        OR plan->>'fixturePolicyEvidenceDigest' IS DISTINCT FROM association.fixture_provenance_digest
        OR plan->>'sourceReceiptDigest' IS DISTINCT FROM association.intake_receipt_digest
        OR plan->>'canonicalVersionId' IS DISTINCT FROM association.canonical_version_id::TEXT
        OR plan->>'worldLocalTenantKey' IS DISTINCT FROM association.local_tenant_key::TEXT
        OR plan->>'worldLocalVersionKey' IS DISTINCT FROM association.local_version_key::TEXT THEN
        RAISE EXCEPTION 'Run-owned fixture activation preset plan differs from retained preparation evidence'
            USING ERRCODE = '23514';
    END IF;
    -- This owner rechecks the fixed preset, complete content and all six template/runtime mappings
    -- while the association and PREPARING row are locked and before the CAS is installed.
    PERFORM * FROM "${serviceSchema}".world_read_run_owned_fixed_preset(
        NEW.preset_plan_json, plan->>'expectedContentDigest', NEW.world_instance_id,
        association.private_game_instance_key);
    activated := instance;
    activated.status := 'ACTIVE';
    activated.lifecycle_epoch := 2;
    activated.row_version := 1;
    activated.failure_reason := NULL;
    activated.updated_at := CURRENT_TIMESTAMP;
    INSERT INTO "${serviceSchema}".world_run_owned_fixture_tenant_manifest
        (transaction_id, activation_request_id, expected_old, expected_new)
    VALUES (txid_current(), NEW.activation_request_id, to_jsonb(instance), to_jsonb(activated));
    INSERT INTO "${serviceSchema}".world_run_owned_fixture_activation_manifest(
        transaction_id, activation_request_id, canonical_game_instance_id, world_instance_id,
        expected_lifecycle_epoch, expected_row_version, request_digest, result_digest)
    VALUES (txid_current(), NEW.activation_request_id, NEW.canonical_game_instance_id,
        NEW.world_instance_id, NEW.expected_lifecycle_epoch, NEW.expected_row_version,
        NEW.request_digest, NEW.result_digest);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_validate_run_owned_fixture_activation_operation() FROM PUBLIC;
CREATE TRIGGER trg_world_run_owned_fixture_activation_operation
    BEFORE INSERT OR UPDATE OR DELETE ON world_run_owned_fixture_activation_operation
    FOR EACH ROW EXECUTE FUNCTION world_validate_run_owned_fixture_activation_operation();

CREATE FUNCTION world_reject_run_owned_fixture_activation_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'Run-owned fixture activation evidence is immutable and retained' USING ERRCODE = '55000';
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_reject_run_owned_fixture_activation_truncate() FROM PUBLIC;
CREATE TRIGGER trg_world_run_owned_fixture_activation_no_truncate
    BEFORE TRUNCATE ON world_run_owned_fixture_activation_operation
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_run_owned_fixture_activation_truncate();

CREATE FUNCTION world_require_run_owned_fixture_activation_commit()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_instance w
        WHERE w.id = NEW.world_instance_id
            AND w.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND w.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND w.status = 'ACTIVE'
            AND w.lifecycle_epoch = NEW.result_lifecycle_epoch
            AND w.row_version = NEW.result_row_version)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation_manifest m
            WHERE m.transaction_id = txid_current()
                AND m.activation_request_id = NEW.activation_request_id)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_tenant_manifest m
            WHERE m.transaction_id = txid_current()
                AND m.activation_request_id = NEW.activation_request_id) THEN
        RAISE EXCEPTION 'Run-owned fixture activation operation did not commit its exact ACTIVE CAS'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_require_run_owned_fixture_activation_commit() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_world_run_owned_fixture_activation_commit
    AFTER INSERT ON world_run_owned_fixture_activation_operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION world_require_run_owned_fixture_activation_commit();

-- A separate exact row-image manifest authorizes the reserved-tenant trigger independently of
-- trigger name ordering. It is one-use and must be consumed before the operation can commit.
CREATE FUNCTION world_consume_run_owned_fixture_tenant_manifest(
    actual_schema TEXT, actual_table TEXT, actual_operation TEXT,
    actual_old JSONB, actual_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE consumed UUID;
BEGIN
    IF actual_schema IS DISTINCT FROM '${serviceSchema}'
        OR actual_table IS DISTINCT FROM 'world_instance'
        OR actual_operation IS DISTINCT FROM 'UPDATE'
        OR actual_old IS NULL OR actual_new IS NULL THEN RETURN FALSE; END IF;
    DELETE FROM "${serviceSchema}".world_run_owned_fixture_tenant_manifest m
    USING "${serviceSchema}".world_run_owned_fixture_activation_operation o,
        "${serviceSchema}".world_canonical_instance_association a
    WHERE m.transaction_id = txid_current()
        AND m.expected_old = actual_old AND m.expected_new = actual_new
        AND o.activation_request_id = m.activation_request_id AND o.outcome = 'COMMITTED'
        AND o.world_instance_id = (actual_old->>'id')::BIGINT
        AND o.world_instance_id = (actual_new->>'id')::BIGINT
        AND o.canonical_game_instance_id::TEXT = actual_old->>'canonical_game_instance_id'
        AND o.canonical_game_instance_id::TEXT = actual_new->>'canonical_game_instance_id'
        AND a.canonical_game_instance_id = o.canonical_game_instance_id
        AND a.world_instance_id = o.world_instance_id
        AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        AND a.local_tenant_key = (actual_old->>'tenant_id')::BIGINT
        AND a.local_tenant_key = (actual_new->>'tenant_id')::BIGINT
        AND a.private_game_instance_key = (actual_old->>'game_instance_id')::BIGINT
        AND a.private_game_instance_key = (actual_new->>'game_instance_id')::BIGINT
        AND actual_old->>'status' = 'PREPARING' AND actual_new->>'status' = 'ACTIVE'
        AND actual_old->>'lifecycle_epoch' = '1' AND actual_new->>'lifecycle_epoch' = '2'
        AND actual_old->>'row_version' = '0' AND actual_new->>'row_version' = '1'
    RETURNING m.activation_request_id INTO consumed;
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_run_owned_fixture_tenant_manifest(TEXT,TEXT,TEXT,JSONB,JSONB) FROM PUBLIC;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$        RAISE EXCEPTION
            'World tenant key is reserved for a canonical authored source; no exact transaction execution manifest'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_claim_legacy_numeric_tenant_key()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact reserved-tenant execution denial guard';
    END IF;
    EXECUTE replace(original,anchor,$replacement$        IF "${serviceSchema}".world_consume_run_owned_fixture_tenant_manifest(
            TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP, old_row, new_row) THEN
            RETURN NEW;
        END IF;
$replacement$ || anchor);
END;
$migration$;

-- Replace only the canonical fixture blanket denial; preserve V1042.7 predecessor staging. Any other lifecycle transition on fixture rows
-- remains denied; the allowed transition consumes an exact transaction-local owner manifest.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        RAISE EXCEPTION 'Run-owned fixture World row mutations are disabled'
            USING ERRCODE = '23514';
    END IF;$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_activation_transition()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact canonical fixture activation denial guard';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        IF OLD.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
            OR NEW.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
            OR OLD.status IS DISTINCT FROM 'PREPARING'
            OR OLD.lifecycle_epoch IS DISTINCT FROM 1
            OR OLD.row_version IS DISTINCT FROM 0
            OR NEW.status IS DISTINCT FROM 'ACTIVE'
            OR NEW.lifecycle_epoch IS DISTINCT FROM 2
            OR NEW.row_version IS DISTINCT FROM 1
            OR (to_jsonb(NEW) - ARRAY['status','lifecycle_epoch','row_version','updated_at'])
                IS DISTINCT FROM
                (to_jsonb(OLD) - ARRAY['status','lifecycle_epoch','row_version','updated_at'])
            OR NOT EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation_manifest m
                JOIN "${serviceSchema}".world_run_owned_fixture_activation_operation o
                    ON o.activation_request_id = m.activation_request_id
                    AND o.canonical_game_instance_id = m.canonical_game_instance_id
                WHERE m.transaction_id = txid_current()
                    AND m.world_instance_id = OLD.id
                    AND m.canonical_game_instance_id = OLD.canonical_game_instance_id
                    AND m.expected_lifecycle_epoch = OLD.lifecycle_epoch
                    AND m.expected_row_version = OLD.row_version
                    AND o.outcome = 'COMMITTED'
                    AND o.request_digest = m.request_digest
                    AND o.result_digest = m.result_digest
                    AND o.result_lifecycle_epoch = NEW.lifecycle_epoch
                    AND o.result_row_version = NEW.row_version) THEN
            RAISE EXCEPTION 'Run-owned fixture activation requires its exact one-use owner CAS manifest'
                USING ERRCODE = '23514';
        END IF;
        DELETE FROM "${serviceSchema}".world_run_owned_fixture_activation_manifest
            WHERE transaction_id = txid_current()
                AND world_instance_id = OLD.id
                AND canonical_game_instance_id = OLD.canonical_game_instance_id;
        RETURN NEW;
    END IF;$replacement$);
    EXECUTE original;
END;
$migration$;

-- Permit the existing complete preset read owner to verify its retained topology after activation,
-- but only against the exact committed fixture operation and epoch transition.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$OR instance.status IS DISTINCT FROM 'PREPARING'
        OR instance.lifecycle_epoch IS DISTINCT FROM association.preparing_lifecycle_epoch
        OR instance.run_owned_fixture_preparing_lifecycle_epoch IS DISTINCT FROM association.preparing_lifecycle_epoch$anchor$;
    replacement TEXT := $replacement$OR NOT (
            (instance.status = 'PREPARING' AND instance.lifecycle_epoch = association.preparing_lifecycle_epoch
                AND instance.row_version = 0)
            OR (instance.status = 'ACTIVE' AND instance.lifecycle_epoch = 2 AND instance.row_version = 1
                AND EXISTS (
                    SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation_operation op
                    JOIN "${serviceSchema}".world_canonical_instance_preparation retained
                        ON retained.canonical_game_instance_id = association.canonical_game_instance_id
                    WHERE op.canonical_game_instance_id = association.canonical_game_instance_id
                        AND op.world_instance_id = association.world_instance_id
                        AND op.outcome = 'COMMITTED'
                        AND op.expected_lifecycle_epoch = 1 AND op.expected_row_version = 0
                        AND op.result_lifecycle_epoch = 2 AND op.result_row_version = 1
                        AND op.preparation_input_digest = association.preparation_input_digest
                        AND op.fixture_policy_seal_evidence_digest = association.fixture_provenance_digest
                        AND op.preset_plan_json = retained.input_json
                        AND op.preset_plan_digest = 'sha256:' || encode(
                            sha256(convert_to(retained.input_json, 'UTF8')), 'hex'))))
        OR instance.run_owned_fixture_preparing_lifecycle_epoch IS DISTINCT FROM association.preparing_lifecycle_epoch$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_verify_run_owned_fixed_preset_plan(jsonb,text,bigint,bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact fixed-preset PREPARING-only lifecycle predicate';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;

CREATE FUNCTION world_activate_run_owned_fixture(
    p_activation_request_id UUID, p_canonical_game_instance_id UUID,
    p_expected_lifecycle_epoch BIGINT, p_expected_row_version BIGINT,
    p_fixture_policy_seal_evidence_digest TEXT, p_request_bytes BYTEA,
    p_request_digest TEXT, p_preparing_evidence_bytes BYTEA,
    p_preparing_evidence_digest TEXT, p_preset_plan_json TEXT,
    p_expected_content_digest TEXT, p_result_digest TEXT, p_result_bytes BYTEA
) RETURNS TABLE(result_bytes BYTEA)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation "${serviceSchema}".world_run_owned_fixture_activation_operation%ROWTYPE;
    association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    instance "${serviceSchema}".world_instance%ROWTYPE;
    plan JSONB;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off'
        OR p_expected_lifecycle_epoch <> 1 OR p_expected_row_version <> 0 THEN
        RAISE EXCEPTION 'Fixture activation requires the exact writable PREPARING owner transaction'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT association FROM "${serviceSchema}".world_canonical_instance_association
        WHERE canonical_game_instance_id = p_canonical_game_instance_id FOR UPDATE;
    SELECT * INTO STRICT instance FROM "${serviceSchema}".world_instance
        WHERE id = association.world_instance_id FOR UPDATE;
    IF association.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR association.fixture_provenance_digest IS DISTINCT FROM p_fixture_policy_seal_evidence_digest
        OR instance.status IS DISTINCT FROM 'PREPARING'
        OR instance.lifecycle_epoch IS DISTINCT FROM 1
        OR instance.row_version IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'Fixture activation caller or World row differs from exact PREPARING association'
            USING ERRCODE = '23514';
    END IF;
    plan := p_preset_plan_json::JSONB;
    IF plan->>'expectedContentDigest' IS DISTINCT FROM p_expected_content_digest THEN
        RAISE EXCEPTION 'Fixture activation content digest differs from its sealed preset plan'
            USING ERRCODE = '23514';
    END IF;
    INSERT INTO "${serviceSchema}".world_run_owned_fixture_activation_operation(
        activation_request_id, canonical_game_instance_id, world_instance_id,
        expected_lifecycle_epoch, expected_row_version, outcome,
        request_bytes, request_digest, preparing_evidence_bytes, preparing_evidence_digest,
        preset_plan_digest, fixture_policy_seal_evidence_digest, preparation_input_digest,
        result_bytes, result_digest, result_lifecycle_epoch, result_row_version, preset_plan_json)
    VALUES (p_activation_request_id, p_canonical_game_instance_id, association.world_instance_id,
        p_expected_lifecycle_epoch, p_expected_row_version, 'COMMITTED',
        p_request_bytes, p_request_digest, p_preparing_evidence_bytes, p_preparing_evidence_digest,
        'sha256' || ':' || encode(sha256(convert_to(p_preset_plan_json,'UTF8')),'hex'),
        p_fixture_policy_seal_evidence_digest, association.preparation_input_digest,
        p_result_bytes, p_result_digest, 2, 1, p_preset_plan_json);
    UPDATE "${serviceSchema}".world_instance
    SET status='ACTIVE', lifecycle_epoch=2, row_version=1, failure_reason=NULL,
        updated_at=CURRENT_TIMESTAMP
    WHERE id=association.world_instance_id
        AND canonical_game_instance_id=p_canonical_game_instance_id
        AND canonical_authority_kind='RUN_OWNED_FIXTURE'
        AND status='PREPARING' AND lifecycle_epoch=1 AND row_version=0
    RETURNING * INTO instance;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Fixture activation CAS did not update the exact PREPARING row'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT operation FROM "${serviceSchema}".world_run_owned_fixture_activation_operation
        WHERE activation_request_id=p_activation_request_id
            AND canonical_game_instance_id=p_canonical_game_instance_id;
    result_bytes := operation.result_bytes;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION world_activate_run_owned_fixture(uuid,uuid,bigint,bigint,text,bytea,text,bytea,text,text,text,text,bytea) FROM PUBLIC;

-- Function arguments are deliberately one activation request plus its complete original owner
-- evidence; no generic lifecycle writer is introduced by this migration.
-- [jooq ignore stop]
