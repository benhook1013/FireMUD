-- Internal structural storage for authenticated publication owner readbacks. A valid row is not
-- itself proof that either owner authenticated its producer; only the unregistered Account
-- reconciler may supply owner provenance through the concrete mTLS clients.
CREATE TABLE account_publication_authorization_owner_results (
    operation_id UUID NOT NULL REFERENCES account_publication_authorization_fences(operation_id),
    owner VARCHAR(16) NOT NULL CHECK (owner IN ('GAME_DESIGN', 'WORLD')),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PUBLISHED', 'NO_PUBLICATION')),
    operation_bytes BYTEA NOT NULL CHECK (octet_length(operation_bytes) > 0),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, owner)
);

-- [jooq ignore start]
CREATE FUNCTION account_publication_authorization_read_frame(
    encoded BYTEA,
    position_value INTEGER,
    OUT frame_value BYTEA,
    OUT next_position INTEGER)
RETURNS RECORD LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    frame_length BIGINT;
    encoded_length INTEGER := octet_length(encoded);
BEGIN
    IF position_value < 1 OR position_value > encoded_length - 3
        OR get_byte(encoded, position_value - 1) >= 128 THEN
        RAISE EXCEPTION 'Publication terminal byte frame is truncated or oversized'
            USING ERRCODE = '23514';
    END IF;
    frame_length := get_byte(encoded, position_value - 1)::BIGINT * 16777216
        + get_byte(encoded, position_value)::BIGINT * 65536
        + get_byte(encoded, position_value + 1)::BIGINT * 256
        + get_byte(encoded, position_value + 2)::BIGINT;
    IF frame_length > encoded_length - position_value - 3 THEN
        RAISE EXCEPTION 'Publication terminal byte frame is incomplete'
            USING ERRCODE = '23514';
    END IF;
    frame_value := substring(encoded FROM position_value + 4 FOR frame_length::INTEGER);
    next_position := position_value + 4 + frame_length::INTEGER;
    RETURN;
END;
$$;

CREATE FUNCTION account_publication_authorization_owner_result_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fence account_publication_authorization_fences%ROWTYPE;
    key_value TEXT;
    operation_prefix BYTEA;
    terminal_prefix BYTEA;
    parsed_frame BYTEA;
    next_position INTEGER;
    epoch_frame BYTEA;
    epoch_text TEXT;
    other_outcome TEXT;
    other_operation BYTEA;
    other_terminal BYTEA;
    tail BYTEA;
    terminal_tail_length INTEGER;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Publication owner results are immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO fence FROM account_publication_authorization_fences
        WHERE operation_id = NEW.operation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original publication authorization is absent'
            USING ERRCODE = '23514';
    END IF;
    -- Match the existing Java source-key order before taking the operation lock.
    FOR key_value IN SELECT source.source_key
        FROM account_publication_authorization_sources source
        WHERE source.operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(source.source_key) LOOP
        PERFORM 1 FROM account_draft_authorization_source_locks lock_row
            WHERE lock_row.source_key = key_value FOR UPDATE;
    END LOOP;
    SELECT * INTO fence FROM account_publication_authorization_fences
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF fence.ordering = 'RESERVED' THEN
        RAISE EXCEPTION 'Publication reservation cannot record terminal owner results'
            USING ERRCODE = '23514';
    END IF;
    IF fence.ordering = 'REVOKE_ORDER' AND NEW.outcome <> 'NO_PUBLICATION' THEN
        RAISE EXCEPTION 'Revocation order permits only no-publication owner results'
            USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM account_publication_authorization_sources
        WHERE operation_id = NEW.operation_id)
        <> jsonb_array_length(fence.source_vector) THEN
        RAISE EXCEPTION 'Complete original publication source capture is required'
            USING ERRCODE = '23514';
    END IF;

    operation_prefix := account_tenant_creation_authorization_frame(
            convert_to('game-design-publication-operation/v1', 'UTF8'))
        || account_tenant_creation_authorization_frame(fence.binding);
    IF substring(NEW.operation_bytes FROM 1 FOR octet_length(operation_prefix))
        IS DISTINCT FROM operation_prefix THEN
        RAISE EXCEPTION 'Owner operation must contain the complete original Account binding'
            USING ERRCODE = '23514';
    END IF;
    SELECT decoded_frame.frame_value, decoded_frame.next_position
        INTO parsed_frame, next_position
        FROM account_publication_authorization_read_frame(
            NEW.operation_bytes, octet_length(operation_prefix) + 1) AS decoded_frame;
    IF octet_length(parsed_frame) = 0 OR next_position <> octet_length(NEW.operation_bytes) + 1 THEN
        RAISE EXCEPTION 'Owner operation must retain one complete World publication checkpoint'
            USING ERRCODE = '23514';
    END IF;

    terminal_prefix := account_tenant_creation_authorization_frame(
            convert_to('game-design-publication-terminal/v1', 'UTF8'))
        || account_tenant_creation_authorization_frame(NEW.operation_bytes)
        || account_tenant_creation_authorization_frame(convert_to(NEW.outcome, 'UTF8'));
    IF NEW.outcome = 'NO_PUBLICATION' THEN
        IF NEW.terminal_bytes IS DISTINCT FROM terminal_prefix THEN
            RAISE EXCEPTION 'No-publication terminal bytes must bind the complete original operation'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF substring(NEW.terminal_bytes FROM 1 FOR octet_length(terminal_prefix))
            IS DISTINCT FROM terminal_prefix THEN
            RAISE EXCEPTION 'Published terminal bytes must bind the complete original operation'
                USING ERRCODE = '23514';
        END IF;
        tail := substring(NEW.terminal_bytes FROM octet_length(terminal_prefix) + 1);
        SELECT decoded_frame.frame_value, decoded_frame.next_position
            INTO parsed_frame, next_position
            FROM account_publication_authorization_read_frame(tail, 1) AS decoded_frame;
        IF octet_length(parsed_frame) = 0 THEN
            RAISE EXCEPTION 'Published terminal requires complete release evidence bytes'
                USING ERRCODE = '23514';
        END IF;
        SELECT decoded_frame.frame_value, decoded_frame.next_position
            INTO epoch_frame, terminal_tail_length
            FROM account_publication_authorization_read_frame(tail, next_position) AS decoded_frame;
        IF terminal_tail_length <> octet_length(tail) + 1 THEN
            RAISE EXCEPTION 'Published terminal has trailing or incomplete evidence bytes'
                USING ERRCODE = '23514';
        END IF;
        epoch_text := convert_from(epoch_frame, 'UTF8');
        IF epoch_text !~ '^[1-9][0-9]*$' OR char_length(epoch_text) > 19
            OR (char_length(epoch_text) = 19 AND epoch_text > '9223372036854775807') THEN
            RAISE EXCEPTION 'Published terminal requires canonical positive publication epoch'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    SELECT outcome, operation_bytes, terminal_bytes
        INTO other_outcome, other_operation, other_terminal
        FROM account_publication_authorization_owner_results
        WHERE operation_id = NEW.operation_id AND owner <> NEW.owner;
    IF FOUND AND (other_outcome IS DISTINCT FROM NEW.outcome
        OR other_operation IS DISTINCT FROM NEW.operation_bytes
        OR other_terminal IS DISTINCT FROM NEW.terminal_bytes) THEN
        RAISE EXCEPTION 'Publication owners must retain byte-identical terminal evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_publication_authorization_owner_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Publication owner results cannot be truncated'
        USING ERRCODE = '23514';
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION account_publication_authorization_source_transition_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    key_value TEXT;
    publication_id UUID;
    publication_ordering TEXT;
    result_count INTEGER;
    outcome_count INTEGER;
    operation_count INTEGER;
    terminal_count INTEGER;
BEGIN
    -- Serialize direct SQL source changes with every publication that references a changed scope.
    FOR key_value IN SELECT changed.source_key FROM account_draft_authorization_changed_scopes changed
        WHERE changed.change_id = OLD.change_id
        ORDER BY account_publication_authorization_source_sort_key(changed.source_key) LOOP
        PERFORM 1 FROM account_draft_authorization_source_locks lock_row
            WHERE lock_row.source_key = key_value FOR UPDATE;
    END LOOP;
    FOR publication_id IN
        SELECT DISTINCT publication.operation_id
        FROM account_draft_authorization_changed_scopes changed
        JOIN account_publication_authorization_sources publication USING (source_key)
        WHERE changed.change_id = OLD.change_id
        ORDER BY publication.operation_id LOOP
        SELECT ordering INTO publication_ordering
            FROM account_publication_authorization_fences
            WHERE operation_id = publication_id FOR UPDATE;
        SELECT count(*), count(DISTINCT outcome),
            count(DISTINCT encode(operation_bytes, 'hex')),
            count(DISTINCT encode(terminal_bytes, 'hex'))
            INTO result_count, outcome_count, operation_count, terminal_count
            FROM account_publication_authorization_owner_results
            WHERE operation_id = publication_id;
        IF publication_ordering = 'RESERVED' OR result_count <> 2
            OR outcome_count <> 1 OR operation_count <> 1 OR terminal_count <> 1
            OR (publication_ordering = 'REVOKE_ORDER' AND EXISTS (
                SELECT 1 FROM account_publication_authorization_owner_results
                WHERE operation_id = publication_id AND outcome <> 'NO_PUBLICATION')) THEN
            RAISE EXCEPTION 'Publication-specific matching owner terminal evidence is required'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_publication_authorization_owner_result_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_publication_authorization_owner_results
    FOR EACH ROW EXECUTE FUNCTION account_publication_authorization_owner_result_guard();
CREATE TRIGGER account_publication_authorization_owner_results_no_truncate
    BEFORE TRUNCATE ON account_publication_authorization_owner_results
    FOR EACH STATEMENT EXECUTE FUNCTION account_publication_authorization_owner_no_truncate();
-- [jooq ignore stop]
