-- Durable non-authoritative scheduling after the unchanged published PLAYER_CREATED schema.
ALTER TABLE game_session_canonical_roster_selection
    ADD COLUMN recovery_last_attempt_at TIMESTAMPTZ;

/* [jooq ignore start] */
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
           OR NEW.recovery_started_at IS NOT NULL OR NEW.recovery_last_attempt_at IS NOT NULL OR NEW.recovery_receipt IS NOT NULL THEN
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
        OR (NEW IS DISTINCT FROM OLD AND NOT (
            (NEW.recovery_receipt IS NOT NULL AND NEW.terminal_outcome = 'DENIED')
            OR (OLD.terminal_outcome IS NULL AND NEW.terminal_outcome IS NULL
                AND NEW.recovery_last_attempt_at IS DISTINCT FROM OLD.recovery_last_attempt_at
                AND NEW.recovery_last_attempt_at IS NOT NULL
                AND (OLD.recovery_last_attempt_at IS NULL
                     OR NEW.recovery_last_attempt_at > OLD.recovery_last_attempt_at)
                AND (to_jsonb(NEW) - 'recovery_last_attempt_at')
                    IS NOT DISTINCT FROM (to_jsonb(OLD) - 'recovery_last_attempt_at'))))
        OR (NEW.recovery_last_attempt_at IS DISTINCT FROM OLD.recovery_last_attempt_at
            AND NOT (OLD.terminal_outcome IS NULL AND NEW.terminal_outcome IS NULL
                AND NEW.recovery_last_attempt_at IS NOT NULL
                AND (OLD.recovery_last_attempt_at IS NULL
                     OR NEW.recovery_last_attempt_at > OLD.recovery_last_attempt_at)
                AND (to_jsonb(NEW) - 'recovery_last_attempt_at')
                    IS NOT DISTINCT FROM (to_jsonb(OLD) - 'recovery_last_attempt_at')))))
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

/* [jooq ignore stop] */

-- Historical markers stay NULL until a pending retry is actually selected; each attempt is
-- timestamped before RPC dispatch. NULLS FIRST gives unattempted legacy markers priority.

ALTER TABLE game_session_canonical_roster_selection
    ADD CONSTRAINT ck_gs_roster_recovery_attempt_marker
    CHECK (recovery_last_attempt_at IS NULL OR recovery_uuid IS NOT NULL);

CREATE INDEX idx_gs_roster_selection_recovery_schedule
    ON game_session_canonical_roster_selection
       (target_fence_uuid, recovery_last_attempt_at ASC NULLS FIRST, operation_uuid)
    WHERE terminal_outcome IS NULL;
