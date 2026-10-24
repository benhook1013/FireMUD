-- Extend the existing sealed-roster family; published preseeded migrations remain unchanged.
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD COLUMN canonical_player_created_source_identity BYTEA;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    DROP CONSTRAINT ck_entity_canonical_roster_policy;
ALTER TABLE entity_canonical_gameplay_roster_snapshots
    ADD CONSTRAINT ck_entity_canonical_roster_policy CHECK (
        (entry_policy = 'PRESEEDED_ONLY' AND canonical_player_created_source_identity IS NULL)
        OR (entry_policy = 'PLAYER_CREATED' AND playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'
            AND publication_evidence_format = 'CANONICAL_UUID_V2'
            AND canonical_player_created_source_identity IS NOT NULL
            AND octet_length(canonical_player_created_source_identity) BETWEEN 1 AND 4096));

ALTER TABLE entity_canonical_roster_admissions
    ADD COLUMN entry_policy VARCHAR(32) NOT NULL DEFAULT 'PRESEEDED_ONLY'
        CHECK (entry_policy IN ('PRESEEDED_ONLY', 'PLAYER_CREATED'));
ALTER TABLE entity_canonical_roster_original_selections
    ADD COLUMN entry_policy VARCHAR(32) NOT NULL DEFAULT 'PRESEEDED_ONLY'
        CHECK (entry_policy IN ('PRESEEDED_ONLY', 'PLAYER_CREATED'));
ALTER TABLE entity_canonical_roster_original_selections
    ADD COLUMN canonical_player_created_roster_receipt BYTEA;
ALTER TABLE entity_canonical_roster_original_selections
    ALTER COLUMN canonical_roster_receipt DROP NOT NULL;
ALTER TABLE entity_canonical_roster_original_selections
    ADD CONSTRAINT ck_entity_roster_original_policy_receipt CHECK (
        (entry_policy = 'PRESEEDED_ONLY' AND canonical_roster_receipt IS NOT NULL
            AND canonical_player_created_roster_receipt IS NULL)
        OR (entry_policy = 'PLAYER_CREATED' AND canonical_roster_receipt IS NULL
            AND canonical_player_created_roster_receipt IS NOT NULL
            AND octet_length(canonical_player_created_roster_receipt) BETWEEN 1 AND 67584));
ALTER TABLE entity_canonical_roster_retention_lifecycle
    ADD COLUMN entry_policy VARCHAR(32) NOT NULL DEFAULT 'PRESEEDED_ONLY'
        CHECK (entry_policy IN ('PRESEEDED_ONLY', 'PLAYER_CREATED'));
ALTER TABLE entity_canonical_roster_retirement_transfers
    ADD COLUMN entry_policy VARCHAR(32) NOT NULL DEFAULT 'PRESEEDED_ONLY'
        CHECK (entry_policy IN ('PRESEEDED_ONLY', 'PLAYER_CREATED'));

-- Replace only the old byte-budget checks. All existing lifecycle/state/identity guards remain.
/* [jooq ignore start] */
DO $$
DECLARE selected RECORD;
BEGIN
    FOR selected IN
        SELECT c.conname, c.conrelid::regclass AS relation
        FROM pg_constraint c
        WHERE c.contype = 'c' AND (
            (c.conrelid = 'entity_canonical_roster_retention_lifecycle'::regclass
                AND pg_get_constraintdef(c.oid) LIKE '%octet_length(owner_retirement_proof)%')
            OR (c.conrelid = 'entity_canonical_roster_admissions'::regclass
                AND pg_get_constraintdef(c.oid) LIKE '%octet_length(recovery_acknowledgement)%')
            OR (c.conrelid = 'entity_canonical_roster_retirement_transfers'::regclass
                AND (pg_get_constraintdef(c.oid) LIKE '%octet_length(observed_retirement)%'
                    OR pg_get_constraintdef(c.oid) LIKE '%octet_length(acknowledgement_request)%')))
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', selected.relation, selected.conname);
    END LOOP;
END;
$$;
/* [jooq ignore stop] */
ALTER TABLE entity_canonical_roster_retention_lifecycle
    ADD CONSTRAINT ck_entity_roster_lifecycle_policy_proof CHECK (
        (lifecycle_state = 'PROTECTED' AND retirement_observed_at IS NULL)
        OR (lifecycle_state IN ('RETIRED', 'DISPOSING') AND owner_target_fence_uuid IS NOT NULL
            AND owner_retirement_uuid IS NOT NULL AND owner_retirement_proof_digest ~ '^[0-9a-f]{64}$'
            AND octet_length(owner_retirement_proof) BETWEEN 1
                AND CASE WHEN entry_policy = 'PLAYER_CREATED' THEN 203423744 ELSE 100000 END
            AND retirement_observed_at IS NOT NULL AND minimum_retention_millis > 0));
ALTER TABLE entity_canonical_roster_admissions
    ADD CONSTRAINT ck_entity_roster_admission_policy_acknowledgement CHECK (
        recovery_acknowledgement IS NULL OR octet_length(recovery_acknowledgement) BETWEEN 1
            AND CASE WHEN entry_policy = 'PLAYER_CREATED' THEN 203423744 ELSE 131072 END);
ALTER TABLE entity_canonical_roster_retirement_transfers
    ADD CONSTRAINT ck_entity_roster_transfer_policy_proof CHECK (
        observed_retirement IS NULL OR octet_length(observed_retirement) BETWEEN 1
            AND CASE WHEN entry_policy = 'PLAYER_CREATED' THEN 203423744 ELSE 131072 END);
ALTER TABLE entity_canonical_roster_retirement_transfers
    ADD CONSTRAINT ck_entity_roster_transfer_policy_request CHECK (
        acknowledgement_request IS NULL OR octet_length(acknowledgement_request) BETWEEN 1
            AND CASE WHEN entry_policy = 'PLAYER_CREATED' THEN 204472320 ELSE 131072 END);

/* [jooq ignore start] */
CREATE FUNCTION guard_entity_roster_created_source_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.canonical_player_created_source_identity IS DISTINCT FROM OLD.canonical_player_created_source_identity THEN
        RAISE EXCEPTION 'sealed roster source identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_created_source_immutable
    BEFORE UPDATE ON entity_canonical_gameplay_roster_snapshots
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_created_source_identity();

CREATE FUNCTION guard_entity_roster_policy_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE snapshot_policy VARCHAR(32); admission_policy VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.entry_policy IS DISTINCT FROM OLD.entry_policy THEN
        RAISE EXCEPTION 'roster policy identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'entity_canonical_roster_retirement_transfers' THEN
        IF TG_OP = 'INSERT' THEN
            SELECT entry_policy INTO admission_policy FROM entity_canonical_roster_admissions
                WHERE operation_uuid = NEW.operation_uuid AND admission_uuid = NEW.admission_uuid;
            IF admission_policy IS DISTINCT FROM NEW.entry_policy THEN
                RAISE EXCEPTION 'retirement transfer changed admission policy' USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSIF NEW.snapshot_uuid IS NOT NULL THEN
        SELECT entry_policy INTO snapshot_policy FROM entity_canonical_gameplay_roster_snapshots
            WHERE snapshot_uuid = NEW.snapshot_uuid;
        IF snapshot_policy IS DISTINCT FROM NEW.entry_policy THEN
            RAISE EXCEPTION 'roster lineage changed sealed snapshot policy' USING ERRCODE = '23514';
        END IF;
        IF TG_TABLE_NAME = 'entity_canonical_roster_original_selections' AND NEW.entry_policy = 'PLAYER_CREATED' THEN
            SELECT entry_policy INTO admission_policy FROM entity_canonical_roster_admissions
                WHERE operation_uuid = NEW.operation_uuid AND admission_state = 'OPEN';
            IF admission_policy IS DISTINCT FROM NEW.entry_policy THEN
                RAISE EXCEPTION 'created original requires its existing admission nonce' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_admission_policy
    BEFORE INSERT OR UPDATE ON entity_canonical_roster_admissions
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_policy_identity();
CREATE TRIGGER trg_entity_roster_original_policy
    BEFORE INSERT OR UPDATE ON entity_canonical_roster_original_selections
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_policy_identity();
CREATE TRIGGER trg_entity_roster_lifecycle_policy
    BEFORE INSERT OR UPDATE ON entity_canonical_roster_retention_lifecycle
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_policy_identity();
CREATE TRIGGER trg_entity_roster_transfer_policy
    BEFORE INSERT OR UPDATE ON entity_canonical_roster_retirement_transfers
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_policy_identity();

CREATE FUNCTION guard_entity_roster_actor_policy()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE snapshot_policy VARCHAR(32); actor_policy VARCHAR(32);
BEGIN
    SELECT entry_policy INTO snapshot_policy FROM entity_canonical_gameplay_roster_snapshots
        WHERE snapshot_uuid = NEW.snapshot_uuid;
    SELECT actor_entry_policy INTO actor_policy FROM characters WHERE character_uuid = NEW.character_uuid;
    IF (snapshot_policy = 'PLAYER_CREATED' AND actor_policy IS DISTINCT FROM 'PLAYER_CREATED')
        OR (snapshot_policy = 'PRESEEDED_ONLY' AND actor_policy IS NOT NULL) THEN
        RAISE EXCEPTION 'roster actor changed published policy branch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_actor_policy
    BEFORE INSERT ON entity_canonical_gameplay_roster_snapshot_actors
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_actor_policy();
/* [jooq ignore stop] */
