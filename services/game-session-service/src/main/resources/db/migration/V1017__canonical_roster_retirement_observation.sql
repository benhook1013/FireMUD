-- Durable Entity observation inventory. Receipts deliberately survive original/fence removal.
CREATE TABLE game_session_canonical_roster_retirement_transfers (
    operation_uuid UUID PRIMARY KEY CHECK (operation_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    original_request BYTEA NOT NULL CHECK (octet_length(original_request) BETWEEN 1 AND 4096),
    target_fence_uuid UUID NOT NULL CHECK (target_fence_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    retirement_uuid UUID NOT NULL CHECK (retirement_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    minimum_retention_millis BIGINT NOT NULL CHECK (minimum_retention_millis > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    acknowledgement_uuid UUID UNIQUE CHECK (acknowledgement_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_digest VARCHAR(64) CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    observation_uuid UUID UNIQUE CHECK (observation_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    admission_uuid UUID CHECK (admission_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    snapshot_uuid UUID CHECK (snapshot_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    snapshot_digest VARCHAR(64) CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    entity_principal TEXT,
    effective_minimum_millis BIGINT,
    received_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    target_purged_at TIMESTAMPTZ,
    CHECK ((snapshot_uuid IS NULL) = (snapshot_digest IS NULL)),
    CHECK ((acknowledgement_uuid IS NULL AND request_digest IS NULL AND observation_uuid IS NULL
            AND admission_uuid IS NULL AND snapshot_uuid IS NULL AND entity_principal IS NULL
            AND effective_minimum_millis IS NULL AND received_at IS NULL)
        OR (acknowledgement_uuid IS NOT NULL AND request_digest IS NOT NULL AND observation_uuid IS NOT NULL
            AND admission_uuid IS NOT NULL AND entity_principal IS NOT NULL AND received_at IS NOT NULL
            AND effective_minimum_millis IS NOT NULL AND effective_minimum_millis >= minimum_retention_millis)),
    CHECK (confirmed_at IS NULL OR acknowledgement_uuid IS NOT NULL),
    CHECK (target_purged_at IS NULL OR acknowledgement_uuid IS NOT NULL)
);
CREATE INDEX idx_gs_roster_retirement_transfer_target
    ON game_session_canonical_roster_retirement_transfers(target_fence_uuid);

-- [jooq ignore start]
CREATE FUNCTION freeze_game_session_roster_retirement_inventory()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO game_session_canonical_roster_retirement_transfers
        (operation_uuid, original_request, target_fence_uuid, retirement_uuid, minimum_retention_millis)
    SELECT s.operation_uuid, s.original_request, NEW.target_fence_uuid, NEW.retirement_uuid, NEW.minimum_retention_millis
    FROM game_session_canonical_roster_selection s
    WHERE s.target_fence_uuid = NEW.target_fence_uuid
      AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL);
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_roster_retirement_inventory
    AFTER UPDATE OF fence_state ON game_session_canonical_roster_target_fences
    FOR EACH ROW WHEN (OLD.fence_state = 'ACTIVE' AND NEW.fence_state = 'RETIRED')
    EXECUTE FUNCTION freeze_game_session_roster_retirement_inventory();

CREATE FUNCTION gs_roster_retirement_observations_complete(fence UUID)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM game_session_canonical_roster_selection s
        JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid)
        LEFT JOIN game_session_canonical_roster_retirement_transfers t
          ON t.operation_uuid = s.operation_uuid AND t.original_request = s.original_request
          AND t.target_fence_uuid = f.target_fence_uuid AND t.retirement_uuid = f.retirement_uuid
          AND t.minimum_retention_millis = f.minimum_retention_millis
        WHERE s.target_fence_uuid = fence
          AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL)
          AND t.acknowledgement_uuid IS NULL)
      AND NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_retirement_transfers t
          WHERE t.target_fence_uuid = fence AND t.acknowledgement_uuid IS NULL);
$$;

CREATE FUNCTION guard_game_session_roster_retirement_transfer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'roster retirement transfers remain protected' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.confirmed_at IS NULL OR OLD.target_purged_at IS NULL
            OR GREATEST(OLD.confirmed_at, OLD.target_purged_at)
                + OLD.effective_minimum_millis * interval '1 millisecond' > clock_timestamp()
            OR EXISTS (SELECT 1 FROM game_session_canonical_roster_target_fences f WHERE f.target_fence_uuid = OLD.target_fence_uuid)
            OR EXISTS (SELECT 1 FROM game_session_canonical_roster_selection s WHERE s.operation_uuid = OLD.operation_uuid OR s.target_fence_uuid = OLD.target_fence_uuid) THEN
            RAISE EXCEPTION 'roster retirement receipt confirmation and horizons required' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.recorded_at := clock_timestamp();
        IF NEW.acknowledgement_uuid IS NOT NULL OR NEW.confirmed_at IS NOT NULL OR NEW.target_purged_at IS NOT NULL
            OR NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_selection s
                JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid)
                WHERE s.operation_uuid = NEW.operation_uuid AND s.original_request = NEW.original_request
                  AND f.target_fence_uuid = NEW.target_fence_uuid AND f.retirement_uuid = NEW.retirement_uuid
                  AND f.minimum_retention_millis = NEW.minimum_retention_millis AND f.fence_state = 'RETIRED'
                  AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL)) THEN
            RAISE EXCEPTION 'exact retired original inventory required' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.operation_uuid, NEW.original_request, NEW.target_fence_uuid, NEW.retirement_uuid, NEW.minimum_retention_millis, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.operation_uuid, OLD.original_request, OLD.target_fence_uuid, OLD.retirement_uuid, OLD.minimum_retention_millis, OLD.recorded_at)
        OR (OLD.acknowledgement_uuid IS NOT NULL AND ROW(NEW.acknowledgement_uuid, NEW.request_digest, NEW.observation_uuid,
            NEW.admission_uuid, NEW.snapshot_uuid, NEW.snapshot_digest, NEW.entity_principal, NEW.effective_minimum_millis, NEW.received_at)
            IS DISTINCT FROM ROW(OLD.acknowledgement_uuid, OLD.request_digest, OLD.observation_uuid,
            OLD.admission_uuid, OLD.snapshot_uuid, OLD.snapshot_digest, OLD.entity_principal, OLD.effective_minimum_millis, OLD.received_at))
        OR (OLD.confirmed_at IS NOT NULL AND NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at)
        OR (OLD.target_purged_at IS NOT NULL AND NEW.target_purged_at IS DISTINCT FROM OLD.target_purged_at) THEN
        RAISE EXCEPTION 'roster retirement receipt identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.target_purged_at IS NULL AND NEW.target_purged_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM game_session_canonical_roster_target_fences f WHERE f.target_fence_uuid = OLD.target_fence_uuid
          AND f.fence_state = 'RETIRED' AND f.retired_at + f.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()
          AND gs_roster_retirement_observations_complete(f.target_fence_uuid)) THEN
        RAISE EXCEPTION 'all exact observations and stored target horizon required' USING ERRCODE = '23514';
    END IF;
    IF OLD.acknowledgement_uuid IS NULL AND NEW.acknowledgement_uuid IS NOT NULL THEN
        NEW.received_at := clock_timestamp();
    END IF;
    IF OLD.confirmed_at IS NULL AND NEW.confirmed_at IS NOT NULL THEN
        NEW.confirmed_at := clock_timestamp();
    END IF;
    IF OLD.target_purged_at IS NULL AND NEW.target_purged_at IS NOT NULL THEN
        NEW.target_purged_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_roster_retirement_transfer_guard BEFORE INSERT OR UPDATE OR DELETE
    ON game_session_canonical_roster_retirement_transfers FOR EACH ROW
    EXECUTE FUNCTION guard_game_session_roster_retirement_transfer();
CREATE TRIGGER gs_roster_retirement_transfer_no_truncate BEFORE TRUNCATE
    ON game_session_canonical_roster_retirement_transfers FOR EACH STATEMENT
    EXECUTE FUNCTION guard_game_session_roster_retirement_transfer();

CREATE FUNCTION guard_game_session_roster_observed_deletion()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Serialize direct SQL with the same fence used by first ACK and owner cleanup.
    PERFORM 1 FROM game_session_canonical_roster_target_fences
        WHERE target_fence_uuid = OLD.target_fence_uuid FOR UPDATE;
    IF NOT gs_roster_retirement_observations_complete(OLD.target_fence_uuid)
        OR EXISTS (SELECT 1 FROM game_session_canonical_roster_retirement_transfers t
            WHERE t.target_fence_uuid = OLD.target_fence_uuid AND t.target_purged_at IS NULL) THEN
        RAISE EXCEPTION 'durable exact Entity retirement observations required' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER gs_roster_selection_observation_delete BEFORE DELETE ON game_session_canonical_roster_selection
    FOR EACH ROW EXECUTE FUNCTION guard_game_session_roster_observed_deletion();
CREATE TRIGGER gs_roster_fence_observation_delete BEFORE DELETE ON game_session_canonical_roster_target_fences
    FOR EACH ROW EXECUTE FUNCTION guard_game_session_roster_observed_deletion();
-- Inventory any retained retired rows present when this forward migration is applied.
INSERT INTO game_session_canonical_roster_retirement_transfers
    (operation_uuid, original_request, target_fence_uuid, retirement_uuid, minimum_retention_millis)
SELECT s.operation_uuid, s.original_request, f.target_fence_uuid, f.retirement_uuid, f.minimum_retention_millis
FROM game_session_canonical_roster_selection s JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid)
WHERE f.fence_state = 'RETIRED'
  AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL);
-- [jooq ignore stop]
