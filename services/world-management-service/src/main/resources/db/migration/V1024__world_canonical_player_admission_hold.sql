-- Player admission is distinct from initial-pointer opening. No release producer exists yet:
-- every retained row stays HELD, even after its diagnostic lease deadline.
CREATE TABLE world_canonical_player_admission_hold (
    hold_id UUID PRIMARY KEY,
    hold_fence UUID NOT NULL UNIQUE,
    lease_id UUID NOT NULL UNIQUE,
    attempt_id UUID NOT NULL UNIQUE,
    lease_sha256 VARCHAR(64) NOT NULL CHECK (lease_sha256 ~ '^[0-9a-f]{64}$'),
    lease_bytes BYTEA NOT NULL CHECK (octet_length(lease_bytes) BETWEEN 1 AND 65536),
    canonical_game_instance_id UUID NOT NULL
        REFERENCES world_canonical_instance_association(canonical_game_instance_id) ON DELETE RESTRICT,
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE RESTRICT,
    active_lifecycle_epoch BIGINT NOT NULL CHECK (active_lifecycle_epoch > 0),
    active_row_version BIGINT NOT NULL CHECK (active_row_version >= 0),
    world_evidence_bytes BYTEA NOT NULL CHECK (octet_length(world_evidence_bytes) > 0),
    diagnostic_expires_at_millis BIGINT NOT NULL CHECK (diagnostic_expires_at_millis > 0),
    hold_state VARCHAR(16) NOT NULL DEFAULT 'HELD' CHECK (hold_state = 'HELD'),
    -- Future terminalization must retain both exact independently authenticated owner results.
    -- NULL means missing proof, never definitive absence. This migration authorizes neither.
    account_terminal_evidence_bytes BYTEA CHECK (account_terminal_evidence_bytes IS NULL),
    game_session_completion_evidence_bytes BYTEA CHECK (game_session_completion_evidence_bytes IS NULL),
    acquisition_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_world_player_admission_hold_lifecycle
    ON world_canonical_player_admission_hold(world_instance_id, hold_state);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_player_admission_hold FROM PUBLIC;

CREATE FUNCTION world_validate_player_admission_hold()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    instance "${serviceSchema}".world_instance%ROWTYPE;
    association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    preparation "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
    lease JSONB;
    scope JSONB;
    evidence JSONB;
    selector JSONB;
    evaluated BIGINT;
    deadline BIGINT;
    now_millis BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World player-admission hold is retained; authenticated dual-owner release is unavailable'
            USING ERRCODE = '55000';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off'
        OR NEW.acquisition_transaction_id IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'World player-admission hold requires its writable READ COMMITTED owner transaction'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO instance FROM "${serviceSchema}".world_instance
        WHERE id = NEW.world_instance_id FOR UPDATE;
    IF NOT FOUND OR instance.status IS DISTINCT FROM 'ACTIVE'
        OR instance.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
        OR instance.lifecycle_epoch IS DISTINCT FROM NEW.active_lifecycle_epoch
        OR instance.row_version IS DISTINCT FROM NEW.active_row_version THEN
        RAISE EXCEPTION 'World player-admission hold requires the exact current ACTIVE lifecycle tuple'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO association FROM "${serviceSchema}".world_canonical_instance_association
        WHERE canonical_game_instance_id = NEW.canonical_game_instance_id;
    IF NOT FOUND OR association.world_instance_id IS DISTINCT FROM instance.id
        OR association.local_tenant_key IS DISTINCT FROM instance.tenant_id
        OR association.private_game_instance_key IS DISTINCT FROM instance.game_instance_id
        OR association.canonical_tenant_id IS DISTINCT FROM instance.canonical_tenant_id
        OR association.canonical_target_namespace IS DISTINCT FROM instance.canonical_target_namespace
        OR association.canonical_world_slug IS DISTINCT FROM instance.canonical_world_slug
        OR association.playable_state_namespace_id IS DISTINCT FROM instance.playable_state_namespace_id
        OR association.playable_state_scope IS DISTINCT FROM instance.playable_state_scope
        OR association.public_production IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'World player-admission hold has no exact canonical owner association'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO preparation FROM "${serviceSchema}".world_canonical_instance_preparation
        WHERE canonical_game_instance_id = NEW.canonical_game_instance_id;
    IF NOT FOUND OR preparation.world_instance_id IS DISTINCT FROM instance.id
        OR preparation.storage_status IS DISTINCT FROM 'MATERIALIZED_UNVERIFIED'
        OR preparation.graph_sha256 IS DISTINCT FROM encode(sha256(preparation.graph_bytes), 'hex')
        OR preparation.input_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(convert_to(preparation.input_json,'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'World player-admission hold requires retained canonical materialization'
            USING ERRCODE = '23514';
    END IF;
    lease := convert_from(NEW.lease_bytes, 'UTF8')::JSONB;
    scope := lease->'bindingScope';
    evidence := convert_from(NEW.world_evidence_bytes, 'UTF8')::JSONB;
    selector := evidence->'request';
    evaluated := (lease->>'evaluatedAt')::BIGINT;
    deadline := (lease->>'expiresAt')::BIGINT;
    now_millis := floor(extract(epoch FROM clock_timestamp()) * 1000)::BIGINT;
    IF lease->>'schema' IS DISTINCT FROM 'account-gameplay-admission-lease-evidence/v1'
        OR lease->>'schemaVersion' IS DISTINCT FROM '1'
        OR lease->>'mode' IS DISTINCT FROM 'PUBLIC_PRODUCTION'
        OR lease->>'targetNamespace' IS DISTINCT FROM association.canonical_target_namespace
        OR lease->>'callerWorkload' IS DISTINCT FROM
            'spiffe://firemud/ns/' || association.canonical_target_namespace || '/sa/game-session-service'
        OR lease->>'leaseId' IS DISTINCT FROM NEW.lease_id::TEXT
        OR lease->>'requestId' IS DISTINCT FROM NEW.attempt_id::TEXT
        OR NEW.lease_sha256 IS DISTINCT FROM encode(sha256(NEW.lease_bytes), 'hex')
        OR lease->'membershipBaseline'->>'membershipLifecycleState' IS DISTINCT FROM 'ACTIVE'
        OR evaluated IS NULL OR deadline IS NULL OR evaluated <= 0
        OR evaluated > now_millis OR deadline <= now_millis
        OR deadline <= evaluated OR deadline - evaluated > 15000
        OR NEW.diagnostic_expires_at_millis IS DISTINCT FROM deadline
        OR scope->>'tenantId' IS DISTINCT FROM association.canonical_tenant_id::TEXT
        OR scope->>'worldSlug' IS DISTINCT FROM association.canonical_world_slug
        OR scope->>'gameInstanceId' IS DISTINCT FROM association.canonical_game_instance_id::TEXT
        OR scope->>'playableStateNamespaceId' IS DISTINCT FROM association.playable_state_namespace_id::TEXT
        OR scope->>'playableStateScope' IS DISTINCT FROM association.playable_state_scope
        OR evidence->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR evidence->>'lifecycleStatus' IS DISTINCT FROM 'ACTIVE'
        OR evidence->>'lifecycleEpoch' IS DISTINCT FROM instance.lifecycle_epoch::TEXT
        OR evidence->>'rowVersion' IS DISTINCT FROM instance.row_version::TEXT
        OR evidence->>'captureId' IS DISTINCT FROM preparation.capture_id::TEXT
        OR evidence->>'graphSha256' IS DISTINCT FROM preparation.graph_sha256
        OR evidence->>'preparationInputDigest' IS DISTINCT FROM preparation.input_digest
        OR selector->>'schemaVersion' IS DISTINCT FROM '1'
        OR selector->>'readRequestId' IS DISTINCT FROM NEW.attempt_id::TEXT
        OR selector->>'targetNamespace' IS DISTINCT FROM association.canonical_target_namespace
        OR selector->>'canonicalTenantId' IS DISTINCT FROM association.canonical_tenant_id::TEXT
        OR selector->>'worldSlug' IS DISTINCT FROM association.canonical_world_slug
        OR selector->>'canonicalGameInstanceId' IS DISTINCT FROM association.canonical_game_instance_id::TEXT
        OR selector->>'playableStateNamespaceId' IS DISTINCT FROM association.playable_state_namespace_id::TEXT
        OR selector->>'playableStateScope' IS DISTINCT FROM association.playable_state_scope
        OR selector->'publicProduction' IS DISTINCT FROM 'true'::JSONB
        OR selector->>'controlPlaneRequestId' IS DISTINCT FROM association.control_plane_request_id
        OR selector->>'canonicalVersionId' IS DISTINCT FROM association.canonical_version_id::TEXT
        OR selector->>'expectedDescriptorRequestDigest' IS DISTINCT FROM association.descriptor_request_digest
        OR selector->>'expectedDescriptorResultDigest' IS DISTINCT FROM association.descriptor_result_digest
        OR selector->>'expectedReleaseAttestationDigest' IS DISTINCT FROM association.release_attestation_digest THEN
        RAISE EXCEPTION 'World player-admission hold differs from the original lease or exact owner evidence'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.hold_id::TEXT !~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        OR NEW.hold_fence::TEXT !~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'World player-admission hold requires opaque identities' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_validate_player_admission_hold() FROM PUBLIC;
CREATE TRIGGER trg_world_player_admission_hold_retained
    BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_player_admission_hold
    FOR EACH ROW EXECUTE FUNCTION world_validate_player_admission_hold();

CREATE FUNCTION world_reject_player_admission_hold_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'World player-admission holds and original evidence are retained' USING ERRCODE='55000';
END;
$$;
REVOKE ALL ON FUNCTION world_reject_player_admission_hold_truncate() FROM PUBLIC;
CREATE TRIGGER trg_world_player_admission_hold_no_truncate
    BEFORE TRUNCATE ON world_canonical_player_admission_hold
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_player_admission_hold_truncate();

-- Preserve V35 and all subsequent exact execution-manifest extensions. The one exception is an
-- exact same-value World row UPDATE in the transaction that inserted this bound ACTIVE hold.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$        RAISE EXCEPTION
            'World tenant key is reserved for a canonical authored source; no exact transaction execution manifest'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_claim_legacy_numeric_tenant_key()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one reserved-tenant execution denial guard';
    END IF;
    original := replace(original,anchor,$replacement$        IF TG_TABLE_SCHEMA = '${serviceSchema}' AND TG_TABLE_NAME = 'world_instance'
            AND TG_OP = 'UPDATE' AND old_row = new_row
            AND old_row->>'status' = 'ACTIVE'
            AND EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_player_admission_hold h
                WHERE h.world_instance_id = (old_row->>'id')::BIGINT
                    AND h.canonical_game_instance_id::TEXT = old_row->>'canonical_game_instance_id'
                    AND h.active_lifecycle_epoch = (old_row->>'lifecycle_epoch')::BIGINT
                    AND h.active_row_version = (old_row->>'row_version')::BIGINT
                    AND h.hold_state = 'HELD' AND h.acquisition_transaction_id = txid_current()) THEN
            RETURN NEW;
        END IF;
$replacement$ || anchor);
    EXECUTE original;
END;
$migration$;

CREATE FUNCTION world_version_player_admission_lifecycle_tuple()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    -- FOR UPDATE alone cannot refresh an older REPEATABLE READ/SERIALIZABLE snapshot. This exact
    -- no-op UPDATE makes its later lifecycle UPDATE fail serialization rather than miss the hold.
    UPDATE "${serviceSchema}".world_instance SET row_version = row_version
        WHERE id = NEW.world_instance_id AND canonical_game_instance_id = NEW.canonical_game_instance_id
            AND status = 'ACTIVE' AND lifecycle_epoch = NEW.active_lifecycle_epoch
            AND row_version = NEW.active_row_version;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'World player-admission hold lost its exact ACTIVE tuple' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_version_player_admission_lifecycle_tuple() FROM PUBLIC;
CREATE TRIGGER trg_world_player_admission_hold_tuple_version
    AFTER INSERT ON world_canonical_player_admission_hold
    FOR EACH ROW EXECUTE FUNCTION world_version_player_admission_lifecycle_tuple();

CREATE FUNCTION world_guard_player_admission_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    -- UPDATE/DELETE owns the same tuple lock used by hold creation. For old MVCC snapshots the
    -- hold's tuple UPDATE supplies PostgreSQL's serialization error before a stale change commits.
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_player_admission_hold h
        WHERE h.world_instance_id = OLD.id AND h.hold_state = 'HELD') THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'World lifecycle is fenced by a retained player-admission hold' USING ERRCODE='23514';
        END IF;
        IF to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW) THEN
            RAISE EXCEPTION 'World lifecycle is fenced by a retained player-admission hold' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_guard_player_admission_lifecycle() FROM PUBLIC;
CREATE TRIGGER aaa_world_player_admission_lifecycle
    BEFORE UPDATE OR DELETE ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_guard_player_admission_lifecycle();
-- Existing V34's unconditional World instance TRUNCATE guard remains in force.
-- [jooq ignore stop]
