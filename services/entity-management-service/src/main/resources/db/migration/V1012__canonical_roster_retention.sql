-- Exact Entity roster-family lifecycle and conditional disposal. Published V1008-V1011 remain
-- unchanged; forward guard replacements preserve all non-disposal immutability and provenance.
CREATE TABLE entity_canonical_roster_retention_lifecycle (
    snapshot_uuid UUID PRIMARY KEY REFERENCES entity_canonical_gameplay_roster_snapshots (snapshot_uuid) DEFERRABLE INITIALLY DEFERRED,
    snapshot_digest VARCHAR(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'PROTECTED' CHECK (lifecycle_state IN ('PROTECTED', 'RETIRED', 'DISPOSING')),
    local_epoch BIGINT NOT NULL DEFAULT 1 CHECK (local_epoch > 0),
    owner_target_fence_uuid UUID,
    owner_retirement_uuid UUID,
    owner_retirement_proof_digest VARCHAR(64),
    owner_retirement_proof BYTEA,
    retirement_observed_at TIMESTAMPTZ,
    minimum_retention_millis BIGINT,
    CHECK ((lifecycle_state = 'PROTECTED' AND retirement_observed_at IS NULL)
        OR (lifecycle_state IN ('RETIRED', 'DISPOSING') AND owner_target_fence_uuid IS NOT NULL
            AND owner_retirement_uuid IS NOT NULL AND owner_retirement_proof_digest ~ '^[0-9a-f]{64}$'
            AND octet_length(owner_retirement_proof) BETWEEN 1 AND 100000
            AND retirement_observed_at IS NOT NULL AND minimum_retention_millis > 0)),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (snapshot_uuid, snapshot_digest)
);

CREATE TABLE entity_canonical_roster_retention_references (
    reference_uuid UUID PRIMARY KEY CHECK (reference_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    acquire_operation_uuid UUID NOT NULL UNIQUE CHECK (acquire_operation_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    snapshot_uuid UUID NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    reference_kind VARCHAR(24) NOT NULL CHECK (reference_kind IN ('ORIGINAL_SELECTION', 'RECOVERY', 'HOLD')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (snapshot_uuid, snapshot_digest)
        REFERENCES entity_canonical_roster_retention_lifecycle (snapshot_uuid, snapshot_digest),
    UNIQUE (reference_uuid, snapshot_uuid, snapshot_digest)
);

CREATE TABLE entity_canonical_roster_retention_releases (
    release_operation_uuid UUID PRIMARY KEY CHECK (release_operation_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    reference_uuid UUID NOT NULL UNIQUE,
    snapshot_uuid UUID NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (reference_uuid, snapshot_uuid, snapshot_digest)
        REFERENCES entity_canonical_roster_retention_references (reference_uuid, snapshot_uuid, snapshot_digest) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_entity_canonical_roster_retention_reference_snapshot
    ON entity_canonical_roster_retention_references (snapshot_uuid, reference_kind);

CREATE TABLE entity_canonical_roster_original_selections (
    operation_uuid UUID PRIMARY KEY,
    owner_request BYTEA NOT NULL CHECK (octet_length(owner_request) BETWEEN 1 AND 4096),
    owner_fence BIGINT NOT NULL CHECK (owner_fence > 0),
    owner_target_fence_uuid UUID NOT NULL,
    snapshot_uuid UUID NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    canonical_roster_receipt BYTEA NOT NULL CHECK (octet_length(canonical_roster_receipt) BETWEEN 1 AND 65536),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (operation_uuid, snapshot_uuid, snapshot_digest)
        REFERENCES entity_canonical_roster_retention_references (reference_uuid, snapshot_uuid, snapshot_digest) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE entity_canonical_roster_admissions (
    operation_uuid UUID PRIMARY KEY,
    admission_uuid UUID NOT NULL UNIQUE,
    request_binding BYTEA NOT NULL CHECK (octet_length(request_binding) BETWEEN 1 AND 4096),
    admission_state VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (admission_state IN ('OPEN', 'CLOSED', 'EXPIRED', 'DISPOSED')),
    snapshot_uuid UUID,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    disposed_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    minimum_retention_millis BIGINT,
    recovery_uuid UUID,
    recovery_request BYTEA CHECK (octet_length(recovery_request) BETWEEN 1 AND 8192),
    recovery_response BYTEA CHECK (octet_length(recovery_response) BETWEEN 1 AND 131072),
    recovery_owner_request BYTEA CHECK (octet_length(recovery_owner_request) BETWEEN 1 AND 4096),
    recovery_owner_fence BIGINT CHECK (recovery_owner_fence > 0),
    recovery_target_fence_uuid UUID,
    recovery_acknowledgement BYTEA CHECK (octet_length(recovery_acknowledgement) BETWEEN 1 AND 131072),
    recovery_acknowledgement_state VARCHAR(16) CHECK (recovery_acknowledgement_state IN ('DENIED', 'RETIRED')),
    recovery_acknowledgement_uuid UUID,
    recovery_acknowledgement_owner_fence BIGINT,
    recovery_acknowledgement_target_fence_uuid UUID,
    CHECK ((recovery_uuid IS NULL AND recovery_request IS NULL AND recovery_response IS NULL
        AND recovery_owner_request IS NULL AND recovery_owner_fence IS NULL AND recovery_target_fence_uuid IS NULL AND closed_at IS NULL)
        OR (recovery_uuid IS NOT NULL AND recovery_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
        AND recovery_request IS NOT NULL AND recovery_response IS NOT NULL AND recovery_owner_request IS NOT NULL
        AND recovery_owner_fence IS NOT NULL AND recovery_target_fence_uuid IS NOT NULL AND closed_at IS NOT NULL
        AND admission_state IN ('CLOSED', 'DISPOSED'))),
    CHECK ((recovery_acknowledgement IS NULL AND recovery_acknowledgement_state IS NULL
        AND recovery_acknowledgement_uuid IS NULL AND recovery_acknowledgement_owner_fence IS NULL
        AND recovery_acknowledgement_target_fence_uuid IS NULL)
        OR (recovery_acknowledgement IS NOT NULL AND recovery_acknowledgement_state IS NOT NULL
        AND recovery_acknowledgement_uuid IS NOT NULL AND recovery_acknowledgement_owner_fence IS NOT NULL
        AND recovery_acknowledgement_target_fence_uuid IS NOT NULL AND admission_state = 'CLOSED' AND snapshot_uuid IS NULL
        AND recovery_acknowledgement_uuid = recovery_uuid AND recovery_acknowledgement_owner_fence = recovery_owner_fence
        AND recovery_acknowledgement_target_fence_uuid = recovery_target_fence_uuid)),
    CHECK ((admission_state = 'OPEN' AND disposed_at IS NULL AND minimum_retention_millis IS NULL)
        OR (admission_state = 'CLOSED' AND recovery_uuid IS NOT NULL AND closed_at IS NOT NULL AND disposed_at IS NULL AND minimum_retention_millis IS NOT NULL AND minimum_retention_millis > 0)
        OR (admission_state = 'EXPIRED' AND snapshot_uuid IS NULL AND disposed_at IS NULL AND closed_at IS NULL AND minimum_retention_millis IS NOT NULL AND minimum_retention_millis > 0)
        OR (admission_state = 'DISPOSED' AND snapshot_uuid IS NOT NULL AND disposed_at IS NOT NULL AND minimum_retention_millis IS NOT NULL AND minimum_retention_millis > 0))
);

CREATE TABLE entity_canonical_roster_disposal_receipts (
    snapshot_uuid UUID PRIMARY KEY,
    snapshot_digest VARCHAR(64) NOT NULL,
    owner_target_fence_uuid UUID NOT NULL,
    owner_retirement_uuid UUID NOT NULL,
    owner_retirement_proof_digest VARCHAR(64) NOT NULL,
    local_epoch BIGINT NOT NULL,
    disposed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    minimum_retention_millis BIGINT NOT NULL CHECK (minimum_retention_millis > 0)
);

/* [jooq ignore start] */
CREATE FUNCTION guard_entity_canonical_roster_retention_lifecycle()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM entity_canonical_gameplay_roster_snapshots
    WHERE snapshot_uuid = NEW.snapshot_uuid AND snapshot_digest = NEW.snapshot_digest
      AND construction_state = 'SEALED'
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'roster retention requires exact sealed snapshot' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_entity_canonical_roster_retention_reference()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE allowed_hold BOOLEAN; replayed BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'entity_canonical_roster_retention_references' THEN
        allowed_hold := NEW.reference_kind = 'HOLD';
        SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_references WHERE reference_uuid = NEW.reference_uuid) INTO replayed;
    ELSE
        SELECT TRUE INTO allowed_hold FROM entity_canonical_roster_retention_references
            WHERE reference_uuid = NEW.reference_uuid;
        SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_releases WHERE reference_uuid = NEW.reference_uuid) INTO replayed;
    END IF;
    -- Acquisitions and releases serialize on the same owner-local lifecycle row. This is also
    -- the required lock boundary for future authenticated retirement/disposal integration.
    PERFORM 1 FROM entity_canonical_roster_retention_lifecycle
    WHERE snapshot_uuid = NEW.snapshot_uuid AND snapshot_digest = NEW.snapshot_digest
      AND (lifecycle_state = 'PROTECTED' OR (lifecycle_state = 'RETIRED' AND (allowed_hold OR replayed)))
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'roster retention lifecycle unavailable' USING ERRCODE = '23514';
    END IF;
    IF replayed THEN RETURN NEW; END IF;
    UPDATE entity_canonical_roster_retention_lifecycle SET local_epoch = local_epoch + 1,
        retirement_observed_at = CASE WHEN lifecycle_state = 'RETIRED' THEN clock_timestamp() ELSE retirement_observed_at END
        WHERE snapshot_uuid = NEW.snapshot_uuid;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_entity_roster_retention_lifecycle_insert
    BEFORE INSERT ON entity_canonical_roster_retention_lifecycle
    FOR EACH ROW EXECUTE FUNCTION guard_entity_canonical_roster_retention_lifecycle();
CREATE TRIGGER trg_entity_roster_retention_reference_insert
    BEFORE INSERT ON entity_canonical_roster_retention_references
    FOR EACH ROW EXECUTE FUNCTION guard_entity_canonical_roster_retention_reference();
CREATE TRIGGER trg_entity_roster_retention_release_insert
    BEFORE INSERT ON entity_canonical_roster_retention_releases
    FOR EACH ROW EXECUTE FUNCTION guard_entity_canonical_roster_retention_reference();

CREATE FUNCTION guard_entity_roster_lifecycle_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_state = 'DISPOSING' AND EXISTS (
            SELECT 1 FROM entity_canonical_roster_disposal_receipts d WHERE d.snapshot_uuid = OLD.snapshot_uuid
                AND d.snapshot_digest = OLD.snapshot_digest AND d.local_epoch = OLD.local_epoch
                AND d.owner_retirement_proof_digest = OLD.owner_retirement_proof_digest) THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'roster lifecycle disposal proof unavailable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.snapshot_uuid, NEW.snapshot_digest, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.snapshot_uuid, OLD.snapshot_digest, OLD.recorded_at)
        OR NEW.local_epoch <> OLD.local_epoch + 1
        OR OLD.lifecycle_state = 'DISPOSING'
        OR (OLD.lifecycle_state = 'RETIRED' AND NEW.lifecycle_state NOT IN ('RETIRED', 'DISPOSING'))
        OR (OLD.lifecycle_state = 'PROTECTED' AND NEW.lifecycle_state NOT IN ('PROTECTED', 'RETIRED'))
        OR (OLD.lifecycle_state = 'RETIRED' AND ROW(NEW.owner_target_fence_uuid, NEW.owner_retirement_uuid,
            NEW.owner_retirement_proof_digest, NEW.owner_retirement_proof, NEW.minimum_retention_millis)
            IS DISTINCT FROM ROW(OLD.owner_target_fence_uuid, OLD.owner_retirement_uuid,
            OLD.owner_retirement_proof_digest, OLD.owner_retirement_proof, OLD.minimum_retention_millis))
        OR (OLD.lifecycle_state = 'RETIRED' AND (NEW.retirement_observed_at < OLD.retirement_observed_at OR NEW.retirement_observed_at > clock_timestamp()))
        OR (NEW.lifecycle_state = 'DISPOSING' AND (OLD.retirement_observed_at + OLD.minimum_retention_millis * interval '1 millisecond' > clock_timestamp()
            OR EXISTS (SELECT 1 FROM entity_canonical_roster_retention_references r WHERE r.snapshot_uuid = OLD.snapshot_uuid
                AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_releases x WHERE x.reference_uuid = r.reference_uuid)))) THEN
        RAISE EXCEPTION 'roster lifecycle epoch or disposal precondition mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_retention_lifecycle_immutable
    BEFORE UPDATE OR DELETE ON entity_canonical_roster_retention_lifecycle
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_lifecycle_mutation();
CREATE FUNCTION entity_roster_disposal_authorized(snapshot_id UUID)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle l
        JOIN entity_canonical_roster_disposal_receipts d USING (snapshot_uuid, snapshot_digest)
        WHERE l.snapshot_uuid = snapshot_id AND l.lifecycle_state = 'DISPOSING'
          AND l.local_epoch = d.local_epoch AND l.owner_retirement_proof_digest = d.owner_retirement_proof_digest
          AND l.owner_target_fence_uuid = d.owner_target_fence_uuid AND l.owner_retirement_uuid = d.owner_retirement_uuid
          AND l.retirement_observed_at + l.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()
          AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_references r WHERE r.snapshot_uuid = snapshot_id
              AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_releases x WHERE x.reference_uuid = r.reference_uuid)))
$$;
CREATE FUNCTION guard_entity_roster_protected_child()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND entity_roster_disposal_authorized(OLD.snapshot_uuid) THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'roster reference history remains protected' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_entity_roster_retention_reference_immutable
    BEFORE UPDATE OR DELETE ON entity_canonical_roster_retention_references
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_protected_child();
CREATE TRIGGER trg_entity_roster_retention_release_immutable
    BEFORE UPDATE OR DELETE ON entity_canonical_roster_retention_releases
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_protected_child();
CREATE TRIGGER trg_entity_roster_retention_lifecycle_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_retention_lifecycle
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE TRIGGER trg_entity_roster_retention_reference_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_retention_references
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE TRIGGER trg_entity_roster_retention_release_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_retention_releases
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE TRIGGER trg_entity_roster_original_selection_immutable
    BEFORE UPDATE OR DELETE ON entity_canonical_roster_original_selections
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_protected_child();
CREATE TRIGGER trg_entity_roster_original_selection_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_original_selections
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE OR REPLACE FUNCTION protect_entity_canonical_gameplay_roster_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    actor_count INTEGER;
    minimum_position SMALLINT;
    maximum_position SMALLINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF entity_roster_disposal_authorized(OLD.snapshot_uuid) THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'canonical gameplay roster snapshots cannot be removed'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.construction_state <> 'BUILDING'
       OR NEW.construction_state <> 'SEALED'
       OR (NEW.snapshot_uuid, NEW.snapshot_digest, NEW.canonical_account_uuid, NEW.tenant_uuid,
           NEW.realm_uuid, NEW.world_slug, NEW.realm_slug, NEW.game_instance_uuid,
           NEW.catalog_revision, NEW.published_version_id, NEW.published_version_number,
           NEW.canonical_version_uuid, NEW.publication_evidence_format,
           NEW.pointer_version, NEW.active_world_epoch,
           NEW.published_policy_digest, NEW.published_release_bundle_ref,
           NEW.admission_pointer_snapshot_digest, NEW.published_owner_proof_digest,
           NEW.playable_state_namespace_uuid, NEW.playable_state_scope, NEW.entry_policy,
           NEW.roster_count, NEW.created_at)
          IS DISTINCT FROM
          (OLD.snapshot_uuid, OLD.snapshot_digest, OLD.canonical_account_uuid, OLD.tenant_uuid,
           OLD.realm_uuid, OLD.world_slug, OLD.realm_slug, OLD.game_instance_uuid,
           OLD.catalog_revision, OLD.published_version_id, OLD.published_version_number,
           OLD.canonical_version_uuid, OLD.publication_evidence_format,
           OLD.pointer_version, OLD.active_world_epoch,
           OLD.published_policy_digest, OLD.published_release_bundle_ref,
           OLD.admission_pointer_snapshot_digest, OLD.published_owner_proof_digest,
           OLD.playable_state_namespace_uuid, OLD.playable_state_scope, OLD.entry_policy,
           OLD.roster_count, OLD.created_at) THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot is immutable'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*), min(ordinal_position), max(ordinal_position)
    INTO actor_count, minimum_position, maximum_position
    FROM entity_canonical_gameplay_roster_snapshot_actors
    WHERE snapshot_uuid = OLD.snapshot_uuid;
    IF actor_count <> OLD.roster_count
       OR (actor_count = 0 AND (minimum_position IS NOT NULL OR maximum_position IS NOT NULL))
       OR (actor_count > 0 AND (minimum_position <> 0 OR maximum_position <> actor_count - 1)) THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot is incomplete'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION protect_entity_canonical_gameplay_roster_snapshot_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    snapshot_row entity_canonical_gameplay_roster_snapshots%ROWTYPE;
    actor_row characters%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' AND entity_roster_disposal_authorized(OLD.snapshot_uuid) THEN RETURN OLD; END IF;
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot actors are immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO snapshot_row
    FROM entity_canonical_gameplay_roster_snapshots
    WHERE snapshot_uuid = NEW.snapshot_uuid
    FOR UPDATE;
    IF NOT FOUND OR snapshot_row.construction_state <> 'BUILDING' THEN
        RAISE EXCEPTION 'canonical gameplay roster snapshot is not being constructed'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO actor_row
    FROM characters
    WHERE character_uuid = NEW.character_uuid
    FOR SHARE;
    IF NOT FOUND
       OR actor_row.actor_identity_status <> 'OWNER_RESOLVED'
       OR actor_row.actor_identity_quarantine_reason IS NOT NULL
       OR actor_row.account_uuid <> snapshot_row.canonical_account_uuid
       OR actor_row.tenant_uuid <> snapshot_row.tenant_uuid
       OR actor_row.playable_state_namespace_id <> snapshot_row.playable_state_namespace_uuid
       OR actor_row.playable_state_scope <> snapshot_row.playable_state_scope
       OR actor_row.name <> NEW.display_name
       OR NEW.actor_kind <> 'PLAYER' THEN
        RAISE EXCEPTION 'canonical gameplay roster actor provenance mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_entity_roster_admission()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.admission_state <> 'OPEN' THEN
            RAISE EXCEPTION 'roster admission must originate open' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.admission_state = 'DISPOSED'
           AND OLD.disposed_at + OLD.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() THEN RETURN OLD; END IF;
        IF OLD.snapshot_uuid IS NULL AND NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = OLD.operation_uuid) THEN
            IF OLD.admission_state = 'EXPIRED'
               AND OLD.recorded_at + OLD.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() THEN RETURN OLD; END IF;
            IF OLD.admission_state = 'CLOSED' AND OLD.recovery_acknowledgement IS NOT NULL
               AND OLD.recovery_acknowledgement_state IN ('DENIED', 'RETIRED')
               AND OLD.recovery_acknowledgement_uuid = OLD.recovery_uuid
               AND OLD.recovery_acknowledgement_owner_fence = OLD.recovery_owner_fence
               AND OLD.recovery_acknowledgement_target_fence_uuid = OLD.recovery_target_fence_uuid
               AND OLD.closed_at + OLD.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() THEN RETURN OLD; END IF;
        END IF;
        RAISE EXCEPTION 'roster admission remains protected' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.operation_uuid, NEW.admission_uuid, NEW.request_binding, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.operation_uuid, OLD.admission_uuid, OLD.request_binding, OLD.recorded_at)
        OR OLD.admission_state IN ('DISPOSED', 'EXPIRED')
        OR (OLD.snapshot_uuid IS NOT NULL AND NEW.snapshot_uuid IS DISTINCT FROM OLD.snapshot_uuid)
        OR (OLD.admission_state = 'CLOSED' AND (
            NEW.admission_state NOT IN ('CLOSED', 'DISPOSED') OR NEW.snapshot_uuid IS DISTINCT FROM OLD.snapshot_uuid
            OR (NEW.admission_state = 'CLOSED' AND NEW.minimum_retention_millis IS DISTINCT FROM OLD.minimum_retention_millis)
            OR (NEW.admission_state = 'DISPOSED' AND NEW.minimum_retention_millis < OLD.minimum_retention_millis)
            OR ROW(NEW.closed_at, NEW.recovery_uuid, NEW.recovery_request, NEW.recovery_response,
                NEW.recovery_owner_request, NEW.recovery_owner_fence, NEW.recovery_target_fence_uuid)
            IS DISTINCT FROM ROW(OLD.closed_at, OLD.recovery_uuid, OLD.recovery_request, OLD.recovery_response,
                OLD.recovery_owner_request, OLD.recovery_owner_fence, OLD.recovery_target_fence_uuid)))
        OR (OLD.recovery_acknowledgement IS NOT NULL AND ROW(NEW.recovery_acknowledgement, NEW.recovery_acknowledgement_state,
            NEW.recovery_acknowledgement_uuid, NEW.recovery_acknowledgement_owner_fence, NEW.recovery_acknowledgement_target_fence_uuid)
            IS DISTINCT FROM ROW(OLD.recovery_acknowledgement, OLD.recovery_acknowledgement_state,
            OLD.recovery_acknowledgement_uuid, OLD.recovery_acknowledgement_owner_fence, OLD.recovery_acknowledgement_target_fence_uuid))
        OR (NEW.admission_state = 'CLOSED' AND OLD.admission_state = 'OPEN' AND (
            NEW.snapshot_uuid IS DISTINCT FROM OLD.snapshot_uuid OR NEW.closed_at IS NULL OR NEW.closed_at > clock_timestamp()))
        OR (NEW.admission_state = 'EXPIRED' AND (OLD.admission_state <> 'OPEN' OR OLD.snapshot_uuid IS NOT NULL
            OR NEW.snapshot_uuid IS NOT NULL OR NEW.minimum_retention_millis IS NULL OR NEW.minimum_retention_millis <= 0
            OR OLD.recorded_at + NEW.minimum_retention_millis * interval '1 millisecond' > clock_timestamp()
            OR EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = OLD.operation_uuid)))
        OR (NEW.admission_state = 'DISPOSED' AND (NOT entity_roster_disposal_authorized(NEW.snapshot_uuid)
            OR NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_lifecycle l
                WHERE l.snapshot_uuid = NEW.snapshot_uuid AND NEW.minimum_retention_millis >= l.minimum_retention_millis))) THEN
        RAISE EXCEPTION 'roster admission nonce or disposal proof mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_admission_guard
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_roster_admissions
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_admission();
CREATE TRIGGER trg_entity_roster_admission_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_admissions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE FUNCTION guard_entity_roster_disposal_receipt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.disposed_at + OLD.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.snapshot_uuid = OLD.snapshot_uuid)
        AND NOT EXISTS (SELECT 1 FROM entity_canonical_gameplay_roster_snapshots s WHERE s.snapshot_uuid = OLD.snapshot_uuid) THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'roster disposal receipt remains protected' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_entity_roster_disposal_receipt_immutable
    BEFORE UPDATE OR DELETE ON entity_canonical_roster_disposal_receipts
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_disposal_receipt();
CREATE TRIGGER trg_entity_roster_disposal_receipt_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_disposal_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
/* [jooq ignore stop] */
