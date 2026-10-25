-- Distinct PLAYER_CREATED original receipts; published PRESEEDED_ONLY columns keep their bounds.
-- The owner sets entry_policy from its closed original request; SQL never mints source authority.
ALTER TABLE game_session_canonical_roster_selection
    ADD COLUMN entry_policy VARCHAR(16) NOT NULL DEFAULT 'PRESEEDED_ONLY',
    ADD COLUMN canonical_player_created_roster_receipt BYTEA
        CHECK (octet_length(canonical_player_created_roster_receipt) BETWEEN 1 AND 67584),
    ADD COLUMN canonical_selected_player_created_character_receipt BYTEA
        CHECK (octet_length(canonical_selected_player_created_character_receipt) BETWEEN 1 AND 202375168);

-- [jooq ignore start]
-- Replace only the two applied cross-column checks that assume a single roster column. Locate
-- their exact referenced column sets instead of depending on generated constraint names.
DO $$
DECLARE
    constraint_name TEXT;
    replaced INTEGER := 0;
BEGIN
    FOR constraint_name IN
        SELECT c.conname FROM pg_constraint c
        WHERE c.conrelid = 'game_session_canonical_roster_selection'::regclass AND c.contype = 'c'
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM pg_attribute a WHERE a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey))
              IN (ARRAY['canonical_roster_receipt','selected_character_uuid','selection_input']::text[],
                  ARRAY['canonical_roster_receipt','receipt_recorded_at']::text[])
    LOOP
        EXECUTE format('ALTER TABLE game_session_canonical_roster_selection DROP CONSTRAINT %I', constraint_name);
        replaced := replaced + 1;
    END LOOP;
    IF replaced <> 2 THEN RAISE EXCEPTION 'expected exact predecessor roster constraints'; END IF;
END;
$$;
-- [jooq ignore stop]

ALTER TABLE game_session_canonical_roster_selection
    ADD CONSTRAINT gs_roster_reference_policy_receipts CHECK (
        (entry_policy = 'PRESEEDED_ONLY' AND canonical_player_created_roster_receipt IS NULL
            AND canonical_selected_player_created_character_receipt IS NULL)
        OR (entry_policy = 'PLAYER_CREATED' AND canonical_roster_receipt IS NULL
            AND canonical_selected_assignment_receipt IS NULL)),
    ADD CONSTRAINT gs_roster_reference_selected_menu CHECK (selected_character_uuid IS NULL
        OR (selection_input IS NOT NULL AND (canonical_roster_receipt IS NOT NULL
            OR canonical_player_created_roster_receipt IS NOT NULL))),
    ADD CONSTRAINT gs_roster_reference_created_selected_actor CHECK (
        canonical_selected_player_created_character_receipt IS NULL OR selected_character_uuid IS NOT NULL),
    ADD CONSTRAINT gs_roster_reference_receipt_clock CHECK (
        (canonical_roster_receipt IS NOT NULL OR canonical_player_created_roster_receipt IS NOT NULL)
        = (receipt_recorded_at IS NOT NULL));

-- [jooq ignore start]


CREATE OR REPLACE FUNCTION guard_game_session_canonical_roster_target_fence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.fence_state = 'RETIRED'
        AND OLD.retired_at + OLD.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()
        AND NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_selection s WHERE s.target_fence_uuid = OLD.target_fence_uuid) THEN
        RETURN OLD;
    END IF;
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'roster target fence tombstone remains required'  USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.fence_state <> 'ACTIVE' THEN
            RAISE EXCEPTION 'roster target fence must begin active' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.target_fence_uuid, NEW.target_binding_digest, NEW.target_binding, NEW.owner_fence,
           NEW.minimum_retention_millis, NEW.replay_horizon_millis)
        IS DISTINCT FROM ROW(OLD.target_fence_uuid, OLD.target_binding_digest, OLD.target_binding, OLD.owner_fence,
           OLD.minimum_retention_millis, OLD.replay_horizon_millis)
        OR OLD.fence_state = 'RETIRED'
        OR NEW.fence_state <> 'RETIRED'
        OR EXISTS (SELECT 1 FROM game_session_canonical_roster_selection s
            WHERE s.target_fence_uuid = OLD.target_fence_uuid
              AND (s.terminal_at IS NULL
                  OR s.terminal_at + OLD.minimum_retention_millis * interval '1 millisecond' > clock_timestamp()
                  OR ((s.roster_read_started AND s.canonical_roster_receipt IS NULL AND s.canonical_player_created_roster_receipt IS NULL
                       OR s.selected_character_uuid IS NOT NULL AND s.canonical_selected_assignment_receipt IS NULL AND s.canonical_selected_player_created_character_receipt IS NULL)
                      AND NOT (s.recovery_uuid IS NOT NULL AND s.recovery_receipt IS NOT NULL AND s.terminal_outcome = 'DENIED')))) THEN
        RAISE EXCEPTION 'roster target retirement requires closed references and owner-local horizons' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_game_session_canonical_roster_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND EXISTS (SELECT 1 FROM game_session_canonical_roster_target_fences f
        WHERE f.target_fence_uuid = OLD.target_fence_uuid AND f.fence_state = 'RETIRED'
          AND f.retired_at + f.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp()) THEN
        RETURN OLD;
    END IF;
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'roster original selection dependencies remain protected'  USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM game_session_canonical_roster_target_fences
        WHERE target_fence_uuid = NEW.target_fence_uuid AND owner_fence = NEW.owner_fence
          AND fence_state = 'ACTIVE' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'roster target fence is sealed against new and recovered references' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.canonical_roster_receipt IS NOT NULL OR NEW.canonical_player_created_roster_receipt IS NOT NULL OR NEW.receipt_recorded_at IS NOT NULL
           OR NEW.selection_input IS NOT NULL OR NEW.selected_character_uuid IS NOT NULL
           OR NEW.canonical_selected_assignment_receipt IS NOT NULL OR NEW.canonical_selected_player_created_character_receipt IS NOT NULL OR NEW.roster_read_started
           OR NEW.terminal_outcome IS NOT NULL OR NEW.recovery_uuid IS NOT NULL
           OR NEW.recovery_started_at IS NOT NULL OR NEW.recovery_receipt IS NOT NULL THEN
            RAISE EXCEPTION 'roster selection must reserve before owner read' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.recovery_uuid IS NULL AND NEW.recovery_uuid IS NOT NULL THEN
        IF OLD.terminal_outcome IS NOT NULL OR NOT EXISTS (
            SELECT 1 FROM game_session_canonical_roster_target_fences f
            WHERE f.target_fence_uuid = OLD.target_fence_uuid
              AND OLD.recorded_at + f.replay_horizon_millis * interval '1 millisecond' <= clock_timestamp())
            OR ROW(NEW.selection_input, NEW.selected_character_uuid, NEW.canonical_selected_assignment_receipt, NEW.canonical_selected_player_created_character_receipt,
                   NEW.roster_read_started, NEW.canonical_roster_receipt, NEW.canonical_player_created_roster_receipt, NEW.receipt_recorded_at, NEW.terminal_outcome, NEW.terminal_at, NEW.recovery_receipt)
                IS DISTINCT FROM ROW(OLD.selection_input, OLD.selected_character_uuid, OLD.canonical_selected_assignment_receipt, OLD.canonical_selected_player_created_character_receipt,
                   OLD.roster_read_started, OLD.canonical_roster_receipt, OLD.canonical_player_created_roster_receipt, OLD.receipt_recorded_at, OLD.terminal_outcome, OLD.terminal_at, OLD.recovery_receipt) THEN
            RAISE EXCEPTION 'roster recovery must fence the unchanged original after its stored replay horizon' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF (OLD.recovery_uuid IS NOT NULL AND (
        ROW(NEW.recovery_uuid, NEW.recovery_started_at, NEW.selection_input, NEW.selected_character_uuid,
            NEW.canonical_selected_assignment_receipt, NEW.canonical_selected_player_created_character_receipt, NEW.roster_read_started)
          IS DISTINCT FROM ROW(OLD.recovery_uuid, OLD.recovery_started_at, OLD.selection_input, OLD.selected_character_uuid,
            OLD.canonical_selected_assignment_receipt, OLD.canonical_selected_player_created_character_receipt, OLD.roster_read_started)
        OR (NEW IS DISTINCT FROM OLD AND (NEW.recovery_receipt IS NULL OR NEW.terminal_outcome IS DISTINCT FROM 'DENIED'))))
        OR (OLD.recovery_receipt IS NOT NULL AND NEW.recovery_receipt IS DISTINCT FROM OLD.recovery_receipt)
        OR (OLD.recovery_uuid IS NULL AND NEW.recovery_uuid IS NULL
            AND ROW(NEW.recovery_started_at, NEW.recovery_receipt) IS DISTINCT FROM ROW(OLD.recovery_started_at, OLD.recovery_receipt)) THEN
        RAISE EXCEPTION 'roster recovery fence and positive closure cannot be replaced' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.operation_uuid, NEW.original_request, NEW.owner_fence, NEW.recorded_at, NEW.target_fence_uuid, NEW.entry_policy)
        IS DISTINCT FROM ROW(OLD.operation_uuid, OLD.original_request, OLD.owner_fence, OLD.recorded_at, OLD.target_fence_uuid, OLD.entry_policy)
        OR (OLD.roster_read_started AND NOT NEW.roster_read_started)
        OR (OLD.terminal_outcome IS NOT NULL
            AND ROW(NEW.terminal_outcome, NEW.terminal_at) IS DISTINCT FROM ROW(OLD.terminal_outcome, OLD.terminal_at))
        OR (OLD.terminal_outcome IS NOT NULL AND NEW IS DISTINCT FROM OLD)
        OR (OLD.selection_input IS NOT NULL AND NEW.selection_input IS DISTINCT FROM OLD.selection_input)
        OR (OLD.selection_input IS NULL AND (OLD.canonical_roster_receipt IS NOT NULL OR OLD.canonical_player_created_roster_receipt IS NOT NULL) AND NEW.selection_input IS NOT NULL)
        OR (OLD.selected_character_uuid IS NOT NULL AND NEW.selected_character_uuid IS DISTINCT FROM OLD.selected_character_uuid)
        OR (OLD.canonical_selected_assignment_receipt IS NOT NULL
            AND NEW.canonical_selected_assignment_receipt IS DISTINCT FROM OLD.canonical_selected_assignment_receipt)
        OR (OLD.canonical_selected_player_created_character_receipt IS NOT NULL
            AND NEW.canonical_selected_player_created_character_receipt IS DISTINCT FROM OLD.canonical_selected_player_created_character_receipt)
        OR (OLD.canonical_player_created_roster_receipt IS NOT NULL
            AND ROW(NEW.canonical_player_created_roster_receipt, NEW.receipt_recorded_at)
                IS DISTINCT FROM ROW(OLD.canonical_player_created_roster_receipt, OLD.receipt_recorded_at))
        OR (OLD.canonical_roster_receipt IS NOT NULL
            AND ROW(NEW.canonical_roster_receipt, NEW.receipt_recorded_at)
                IS DISTINCT FROM ROW(OLD.canonical_roster_receipt, OLD.receipt_recorded_at)) THEN
        RAISE EXCEPTION 'original roster selection cannot be replaced or reopened' USING ERRCODE = '23514';
    END IF;
    IF OLD.terminal_outcome IS NULL AND NEW.terminal_outcome IS NOT NULL
        AND NOT (NEW.recovery_uuid IS NOT NULL AND NEW.recovery_receipt IS NOT NULL AND NEW.terminal_outcome = 'DENIED')
        AND NOT (NEW.terminal_outcome = 'DENIED' AND NOT NEW.roster_read_started
            AND NEW.canonical_roster_receipt IS NULL AND NEW.canonical_player_created_roster_receipt IS NULL
            AND NEW.selected_character_uuid IS NULL AND NEW.recovery_uuid IS NULL)
        AND (NEW.selection_input IS NULL
            OR (NEW.roster_read_started AND NEW.canonical_roster_receipt IS NULL AND NEW.canonical_player_created_roster_receipt IS NULL)
            OR (NEW.selected_character_uuid IS NOT NULL AND NEW.canonical_selected_assignment_receipt IS NULL AND NEW.canonical_selected_player_created_character_receipt IS NULL)
            OR (NEW.terminal_outcome = 'SELECTED' AND NEW.canonical_selected_assignment_receipt IS NULL AND NEW.canonical_selected_player_created_character_receipt IS NULL)
            OR (NEW.terminal_outcome = 'MENU' AND (NEW.selected_character_uuid IS NOT NULL
                OR (NEW.canonical_roster_receipt IS NULL AND NEW.canonical_player_created_roster_receipt IS NULL)))) THEN
        RAISE EXCEPTION 'roster terminal outcome requires its exact policy receipts or positive recovery' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION freeze_game_session_roster_retirement_inventory()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO game_session_canonical_roster_retirement_transfers
        (operation_uuid, original_request, target_fence_uuid, retirement_uuid, minimum_retention_millis)
    SELECT s.operation_uuid, s.original_request, NEW.target_fence_uuid, NEW.retirement_uuid, NEW.minimum_retention_millis
    FROM game_session_canonical_roster_selection s
    WHERE s.target_fence_uuid = NEW.target_fence_uuid
      AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.canonical_player_created_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION gs_roster_retirement_observations_complete(fence UUID)
RETURNS BOOLEAN LANGUAGE SQL AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM game_session_canonical_roster_selection s
        JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid)
        LEFT JOIN game_session_canonical_roster_retirement_transfers t
          ON t.operation_uuid = s.operation_uuid AND t.original_request = s.original_request
          AND t.target_fence_uuid = f.target_fence_uuid AND t.retirement_uuid = f.retirement_uuid
          AND t.minimum_retention_millis = f.minimum_retention_millis
        WHERE s.target_fence_uuid = fence
          AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.canonical_player_created_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL)
          AND t.acknowledgement_uuid IS NULL)
      AND NOT EXISTS (SELECT 1 FROM game_session_canonical_roster_retirement_transfers t
          WHERE t.target_fence_uuid = fence AND t.acknowledgement_uuid IS NULL);
$$;

CREATE OR REPLACE FUNCTION guard_game_session_roster_retirement_transfer()
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
                  AND (s.roster_read_started OR s.canonical_roster_receipt IS NOT NULL OR s.canonical_player_created_roster_receipt IS NOT NULL OR s.recovery_receipt IS NOT NULL)) THEN
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

-- ACK envelopes keep Common's policy-specific 128 KiB / 195 MiB bounds and bounded
-- confirmation overhead. This compact table retains the exact request hash, not a truncated proof.
-- Existing fence/selection deletion and transfer-confirmation/truncation guards remain installed.
-- [jooq ignore stop]
