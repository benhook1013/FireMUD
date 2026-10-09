-- Forward-only exact Game Logic terminal settlement. Original orders/participation stay immutable.
CREATE TABLE account_game_logic_intake_settlements (
    operation_id UUID PRIMARY KEY REFERENCES account_game_logic_intake_authorizations(operation_id),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('RETAINED', 'ABORTED')),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 16777216),
    terminal_digest VARCHAR(71) NOT NULL CHECK (terminal_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt_bytes BYTEA NOT NULL CHECK (octet_length(receipt_bytes) BETWEEN 1 AND 16778240),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- [jooq ignore start]
CREATE FUNCTION account_game_logic_intake_settlement_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_game_logic_intake_authorizations%ROWTYPE;
    p INTEGER := 1;
    q INTEGER := 1;
    parsed BYTEA;
    operation_bytes BYTEA;
    selected_source BYTEA;
    manifest BYTEA;
    expected_source BYTEA;
    marker TEXT;
BEGIN
    -- Same sorted original source locks as Account's producer/source writers.
    SELECT * INTO STRICT original FROM account_game_logic_intake_authorizations WHERE operation_id = NEW.operation_id;
    PERFORM l.source_key FROM account_draft_authorization_source_locks l
        JOIN account_game_logic_intake_sources s ON s.source_key = l.source_key
        WHERE s.operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(l.source_key) FOR UPDATE OF l;
    PERFORM operation_id FROM account_game_logic_intake_authorizations WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF NEW.terminal_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.terminal_bytes), 'hex')
        OR NEW.receipt_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.receipt_bytes), 'hex') THEN
        RAISE EXCEPTION 'Changed intake settlement digest' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF convert_from(parsed, 'UTF8') <> 'account-game-logic-intake-settlement/v1' THEN
        RAISE EXCEPTION 'Invalid intake settlement schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF parsed IS DISTINCT FROM NEW.terminal_bytes OR p <> octet_length(NEW.receipt_bytes) + 1 THEN
        RAISE EXCEPTION 'Changed original settlement terminal' USING ERRCODE = '23514';
    END IF;

    p := 1;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
    IF convert_from(parsed, 'UTF8') <> 'game-logic-gameplay-rule-intake-terminal/v1' THEN
        RAISE EXCEPTION 'Invalid Game Logic terminal schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.outcome THEN
        RAISE EXCEPTION 'Changed Game Logic terminal outcome' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO operation_bytes, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
    SELECT frame_value, next_position INTO parsed, q FROM account_publication_authorization_read_frame(operation_bytes, q);
    IF convert_from(parsed, 'UTF8') <> 'game-logic-gameplay-rule-intake-operation/v1' THEN
        RAISE EXCEPTION 'Invalid Game Logic operation schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, q FROM account_publication_authorization_read_frame(operation_bytes, q);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.target_namespace THEN
        RAISE EXCEPTION 'Changed Game Logic operation namespace' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, q FROM account_publication_authorization_read_frame(operation_bytes, q);
    IF parsed IS DISTINCT FROM original.binding OR q <> octet_length(operation_bytes) + 1 THEN
        RAISE EXCEPTION 'Changed original Game Logic operation authorization' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
    IF parsed IS DISTINCT FROM original.binding THEN
        RAISE EXCEPTION 'Changed Game Logic terminal authorization' USING ERRCODE = '23514';
    END IF;
    -- Original binding: schema, four UUID frames, then the exact complete selected source.
    q := 1;
    FOR i IN 1..5 LOOP
        SELECT frame_value, next_position INTO parsed, q FROM account_publication_authorization_read_frame(original.binding, q);
    END LOOP;
    SELECT frame_value, next_position INTO expected_source, q FROM account_publication_authorization_read_frame(original.binding, q);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
    IF NEW.outcome = 'RETAINED' THEN
        IF marker <> 'PRESENT' THEN RAISE EXCEPTION 'Retained terminal missing source' USING ERRCODE = '23514'; END IF;
        SELECT frame_value, next_position INTO selected_source, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
        IF marker <> 'PRESENT' THEN RAISE EXCEPTION 'Retained terminal missing manifest' USING ERRCODE = '23514'; END IF;
        SELECT frame_value, next_position INTO manifest, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
        IF selected_source IS DISTINCT FROM expected_source
            OR manifest IS DISTINCT FROM convert_to(convert_from(expected_source, 'UTF8')::JSONB->>'manifestJson', 'UTF8') THEN
            RAISE EXCEPTION 'Changed retained source or manifest' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF marker <> 'ABSENT' THEN RAISE EXCEPTION 'Aborted terminal claims retained source' USING ERRCODE = '23514'; END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p FROM account_publication_authorization_read_frame(NEW.terminal_bytes, p);
        IF marker <> 'ABSENT' THEN RAISE EXCEPTION 'Aborted terminal claims manifest' USING ERRCODE = '23514'; END IF;
    END IF;
    IF p <> octet_length(NEW.terminal_bytes) + 1 THEN
        RAISE EXCEPTION 'Trailing Game Logic terminal bytes' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_game_logic_intake_settlement_validate
    BEFORE INSERT ON account_game_logic_intake_settlements FOR EACH ROW
    EXECUTE FUNCTION account_game_logic_intake_settlement_guard();
CREATE TRIGGER account_game_logic_intake_settlement_immutable
    BEFORE UPDATE OR DELETE ON account_game_logic_intake_settlements FOR EACH ROW
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_settlement_no_truncate
    BEFORE TRUNCATE ON account_game_logic_intake_settlements FOR EACH STATEMENT
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();

CREATE FUNCTION account_game_logic_intake_is_settled(operation_value UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM account_game_logic_intake_settlements WHERE operation_id = operation_value);
$$;

-- Release only this exact receipt's participation; preserve every other writer/disclosure guard.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$FROM account_game_logic_intake_sources s WHERE s.source_key = ANY(source_keys)$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one existing intake source-writer guard';
    END IF;
    EXECUTE replace(original, anchor, anchor || ' AND NOT account_game_logic_intake_is_settled(s.operation_id)');
END;
$migration$;
-- [jooq ignore stop]
