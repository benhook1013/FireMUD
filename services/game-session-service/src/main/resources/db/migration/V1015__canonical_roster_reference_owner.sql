-- Exact durable roster selection/reference owner. All runtime registration remains closed.
CREATE TABLE game_session_canonical_roster_target_fences (
    target_fence_uuid UUID PRIMARY KEY,
    target_binding_digest VARCHAR(64) NOT NULL UNIQUE CHECK (target_binding_digest ~ '^[0-9a-f]{64}$'),
    target_binding BYTEA NOT NULL CHECK (octet_length(target_binding) BETWEEN 1 AND 4096),
    owner_fence BIGINT NOT NULL DEFAULT 1 CHECK (owner_fence = 1),
    fence_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (fence_state IN ('ACTIVE', 'RETIRED')),
    minimum_retention_millis BIGINT NOT NULL CHECK (minimum_retention_millis > 0),
    replay_horizon_millis BIGINT NOT NULL CHECK (replay_horizon_millis > 0 AND replay_horizon_millis <= minimum_retention_millis),
    retirement_uuid UUID,
    superseding_pointer_digest VARCHAR(64),
    superseding_pointer_version BIGINT,
    superseding_catalog_revision BIGINT,
    superseding_world_epoch BIGINT,
    retired_at TIMESTAMPTZ,
    UNIQUE (target_fence_uuid, owner_fence),
    CHECK ((fence_state = 'ACTIVE' AND retirement_uuid IS NULL AND retired_at IS NULL
            AND superseding_pointer_digest IS NULL AND superseding_pointer_version IS NULL
            AND superseding_catalog_revision IS NULL AND superseding_world_epoch IS NULL)
        OR (fence_state = 'RETIRED' AND retirement_uuid IS NOT NULL AND retired_at IS NOT NULL
            AND superseding_pointer_digest ~ '^[0-9a-f]{64}$' AND superseding_pointer_version > 0
            AND superseding_catalog_revision > 0 AND superseding_world_epoch > 0))
);

CREATE TABLE game_session_canonical_roster_selection (
    operation_uuid UUID PRIMARY KEY CHECK (operation_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    target_fence_uuid UUID NOT NULL,
    original_request BYTEA NOT NULL CHECK (octet_length(original_request) BETWEEN 1 AND 4096),
    owner_fence BIGINT NOT NULL DEFAULT 1 CHECK (owner_fence = 1),
    selection_input BYTEA CHECK (octet_length(selection_input) BETWEEN 1 AND 1024),
    selected_character_uuid UUID CHECK (selected_character_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    canonical_selected_assignment_receipt BYTEA CHECK (octet_length(canonical_selected_assignment_receipt) BETWEEN 1 AND 8192),
    CHECK (selected_character_uuid IS NULL OR (selection_input IS NOT NULL AND canonical_roster_receipt IS NOT NULL)),
    CHECK (canonical_selected_assignment_receipt IS NULL OR selected_character_uuid IS NOT NULL),
    canonical_roster_receipt BYTEA CHECK (octet_length(canonical_roster_receipt) BETWEEN 1 AND 65536),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    receipt_recorded_at TIMESTAMPTZ,
    roster_read_started BOOLEAN NOT NULL DEFAULT FALSE,
    terminal_outcome VARCHAR(16) CHECK (terminal_outcome IN ('MENU', 'SELECTED', 'DENIED')),
    terminal_at TIMESTAMPTZ,
    CHECK ((terminal_outcome IS NULL) = (terminal_at IS NULL)),
    FOREIGN KEY (target_fence_uuid, owner_fence) REFERENCES game_session_canonical_roster_target_fences (target_fence_uuid, owner_fence),
    CHECK ((canonical_roster_receipt IS NULL) = (receipt_recorded_at IS NULL))
);

CREATE INDEX idx_gs_roster_selection_target_fence ON game_session_canonical_roster_selection(target_fence_uuid, terminal_at);

-- [jooq ignore start]
CREATE FUNCTION guard_game_session_canonical_roster_target_fence()
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
                  OR (s.roster_read_started AND s.canonical_roster_receipt IS NULL)
                  OR (s.selected_character_uuid IS NOT NULL AND s.canonical_selected_assignment_receipt IS NULL))) THEN
        RAISE EXCEPTION 'roster target retirement requires closed references and owner-local horizons' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_canonical_roster_target_fence_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_roster_target_fences
    FOR EACH ROW EXECUTE FUNCTION guard_game_session_canonical_roster_target_fence();
CREATE TRIGGER gs_canonical_roster_target_fence_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_roster_target_fences
    FOR EACH STATEMENT EXECUTE FUNCTION guard_game_session_canonical_roster_target_fence();
CREATE FUNCTION guard_game_session_canonical_roster_selection()
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
        IF NEW.canonical_roster_receipt IS NOT NULL OR NEW.receipt_recorded_at IS NOT NULL
           OR NEW.selection_input IS NOT NULL OR NEW.selected_character_uuid IS NOT NULL
           OR NEW.canonical_selected_assignment_receipt IS NOT NULL OR NEW.roster_read_started
           OR NEW.terminal_outcome IS NOT NULL THEN
            RAISE EXCEPTION 'roster selection must reserve before owner read' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.operation_uuid, NEW.original_request, NEW.owner_fence, NEW.recorded_at, NEW.target_fence_uuid)
        IS DISTINCT FROM ROW(OLD.operation_uuid, OLD.original_request, OLD.owner_fence, OLD.recorded_at, OLD.target_fence_uuid)
        OR (OLD.roster_read_started AND NOT NEW.roster_read_started)
        OR (OLD.terminal_outcome IS NOT NULL
            AND ROW(NEW.terminal_outcome, NEW.terminal_at) IS DISTINCT FROM ROW(OLD.terminal_outcome, OLD.terminal_at))
        OR (OLD.terminal_outcome IS NOT NULL AND NEW IS DISTINCT FROM OLD)
        OR (OLD.selection_input IS NOT NULL AND NEW.selection_input IS DISTINCT FROM OLD.selection_input)
        OR (OLD.selection_input IS NULL AND OLD.canonical_roster_receipt IS NOT NULL AND NEW.selection_input IS NOT NULL)
        OR (OLD.selected_character_uuid IS NOT NULL AND NEW.selected_character_uuid IS DISTINCT FROM OLD.selected_character_uuid)
        OR (OLD.canonical_selected_assignment_receipt IS NOT NULL
            AND NEW.canonical_selected_assignment_receipt IS DISTINCT FROM OLD.canonical_selected_assignment_receipt)
        OR (OLD.canonical_roster_receipt IS NOT NULL
            AND ROW(NEW.canonical_roster_receipt, NEW.receipt_recorded_at)
                IS DISTINCT FROM ROW(OLD.canonical_roster_receipt, OLD.receipt_recorded_at)) THEN
        RAISE EXCEPTION 'original roster selection cannot be replaced or reopened' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_canonical_roster_selection_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_roster_selection
    FOR EACH ROW EXECUTE FUNCTION guard_game_session_canonical_roster_selection();
CREATE TRIGGER gs_canonical_roster_selection_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_roster_selection
    FOR EACH STATEMENT EXECUTE FUNCTION guard_game_session_canonical_roster_selection();
-- [jooq ignore stop]
