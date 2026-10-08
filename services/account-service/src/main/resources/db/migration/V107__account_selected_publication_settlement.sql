-- Internal persistence after authenticated exact reads from BOTH publication owners. Decoding
-- these bytes does not authenticate either remote owner and is not an external write surface.
CREATE TABLE account_selected_publication_settlements (
    operation_id UUID PRIMARY KEY REFERENCES account_selected_publication_authorizations(operation_id),
    fence_id UUID NOT NULL,
    account_binding BYTEA NOT NULL CHECK (octet_length(account_binding) BETWEEN 1 AND 1048576),
    publication_operation BYTEA NOT NULL CHECK (octet_length(publication_operation) BETWEEN 1 AND 8388608),
    game_design_outcome VARCHAR(32) NOT NULL CHECK (game_design_outcome IN ('PUBLISHED', 'NO_PUBLICATION')),
    game_design_terminal BYTEA NOT NULL CHECK (octet_length(game_design_terminal) BETWEEN 1 AND 16777216),
    world_outcome VARCHAR(16) NOT NULL CHECK (world_outcome IN ('PUBLISHED', 'ABORTED')),
    world_terminal BYTEA NOT NULL CHECK (world_terminal = game_design_terminal),
    receipt BYTEA NOT NULL CHECK (octet_length(receipt) BETWEEN 1 AND 50331648),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((game_design_outcome = 'PUBLISHED' AND world_outcome = 'PUBLISHED')
        OR (game_design_outcome = 'NO_PUBLICATION' AND world_outcome = 'ABORTED'))
);

-- [jooq ignore start]
CREATE FUNCTION account_selected_publication_settlement_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_selected_publication_authorizations%ROWTYPE;
    issuer account_control_ui_issuance_operations%ROWTYPE;
    source_key_value TEXT;
    p INTEGER;
    parsed BYTEA;
    world_bytes BYTEA;
    world JSONB;
    selection JSONB;
    input_bytes BYTEA;
    terminal_frames BYTEA[] := ARRAY[]::BYTEA[];
    receipt_frames BYTEA[] := ARRAY[]::BYTEA[];
    i INTEGER;
BEGIN
    -- Same canonical source-before-operation order as source writers. Never wait backwards on
    -- issuance, whose producers take it before sources. Nothing here performs a remote read.
    FOR source_key_value IN SELECT source_key FROM account_selected_publication_sources
        WHERE operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(source_key) LOOP
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE;
    END LOOP;
    SELECT * INTO STRICT original FROM account_selected_publication_authorizations
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = original.issuance_operation_id FOR UPDATE NOWAIT;
    IF NEW.producer_xid <> txid_current() OR NEW.fence_id <> original.fence_id
        OR NEW.account_binding <> original.binding OR issuer.status <> 'COMMITTED'
        OR issuer.source_payload <> original.source_payload
        OR issuer.bundle_payload <> original.issuance_bundle THEN
        RAISE EXCEPTION 'Settlement differs from exact original committed Account order' USING ERRCODE = '23514';
    END IF;
    p := 1;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.publication_operation, p);
    IF parsed <> convert_to('game-design-publication-operation/v1', 'UTF8') THEN
        RAISE EXCEPTION 'Invalid publication operation schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.publication_operation, p);
    IF parsed <> original.binding THEN
        RAISE EXCEPTION 'Substituted original Account order' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO world_bytes, p FROM account_publication_authorization_read_frame(NEW.publication_operation, p);
    IF p <> octet_length(NEW.publication_operation) + 1 OR octet_length(world_bytes) = 0 THEN
        RAISE EXCEPTION 'Incomplete publication operation' USING ERRCODE = '23514';
    END IF;
    world := convert_from(world_bytes, 'UTF8')::JSONB;
    -- Retrieve the original immutable selection, without recapturing current authority.
    p := 1;
    FOR i IN 1..4 LOOP
        SELECT frame_value, next_position INTO input_bytes, p FROM account_publication_authorization_read_frame(original.binding, p);
    END LOOP;
    p := 1;
    FOR i IN 1..3 LOOP
        SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(input_bytes, p);
    END LOOP;
    selection := convert_from(parsed, 'UTF8')::JSONB;
    IF world->>'schema' IS DISTINCT FROM 'world-published-start-location-evidence/v1'
        OR world->'request'->>'canonicalTenantId' IS DISTINCT FROM original.tenant_uuid::TEXT
        OR world->'request'->>'canonicalVersionId' IS DISTINCT FROM selection->'intent'->>'canonicalVersionId'
        OR world->'request'->>'publicationRequestId' IS DISTINCT FROM original.publish_request_id
        OR world->'request'->>'versionStateEpoch' IS DISTINCT FROM selection->'intent'->>'expectedVersionStateEpoch'
        OR world->'request'->>'appliedCommitId' IS DISTINCT FROM selection->'intent'->>'selectedCommitId'
        OR 'sha256:' || (world->'request'->>'requestDigest') IS DISTINCT FROM
            'sha256:' || encode(sha256(parsed), 'hex')
        OR (original.world_evidence IS NOT NULL AND original.world_evidence <> world_bytes) THEN
        RAISE EXCEPTION 'Publication World evidence differs from original selected order' USING ERRCODE = '23514';
    END IF;
    p := 1;
    WHILE p <= octet_length(NEW.game_design_terminal) LOOP
        SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.game_design_terminal, p);
        terminal_frames := array_append(terminal_frames, parsed);
        IF cardinality(terminal_frames) > 5 THEN
            RAISE EXCEPTION 'Trailing publication terminal fields' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF terminal_frames[1] IS DISTINCT FROM convert_to('game-design-publication-terminal/v1', 'UTF8')
        OR terminal_frames[2] IS DISTINCT FROM NEW.publication_operation
        OR terminal_frames[3] IS DISTINCT FROM convert_to(NEW.game_design_outcome, 'UTF8')
        OR cardinality(terminal_frames) <> (CASE WHEN NEW.game_design_outcome = 'PUBLISHED' THEN 5 ELSE 3 END) THEN
        RAISE EXCEPTION 'Changed complete publication terminal relationship' USING ERRCODE = '23514';
    END IF;
    IF NEW.game_design_outcome = 'PUBLISHED' THEN
        IF convert_from(terminal_frames[5], 'UTF8') !~ '^[1-9][0-9]*$'
            OR convert_from(terminal_frames[5], 'UTF8')::BIGINT <>
                (world->'request'->>'versionStateEpoch')::BIGINT + 1 THEN
            RAISE EXCEPTION 'Changed committed publication epoch' USING ERRCODE = '23514';
        END IF;
        -- Game Design owns release semantics. Its canonical codec and the authenticated owner
        -- read validate the opaque complete content; Account binds all its exact bytes here.
        IF octet_length(terminal_frames[4]) = 0 THEN
            RAISE EXCEPTION 'Missing complete published release content' USING ERRCODE = '23514';
        END IF;
    END IF;
    p := 1;
    WHILE p <= octet_length(NEW.receipt) LOOP
        SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.receipt, p);
        receipt_frames := array_append(receipt_frames, parsed);
        IF cardinality(receipt_frames) > 6 THEN
            RAISE EXCEPTION 'Trailing settlement receipt' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF receipt_frames IS DISTINCT FROM ARRAY[convert_to('account-selected-publication-settlement/v1', 'UTF8'),
        NEW.account_binding, NEW.publication_operation, NEW.game_design_terminal,
        convert_to(NEW.world_outcome, 'UTF8'), NEW.world_terminal] THEN
        RAISE EXCEPTION 'Changed exact settlement receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_selected_publication_settlement_insert BEFORE INSERT
    ON account_selected_publication_settlements FOR EACH ROW
    EXECUTE FUNCTION account_selected_publication_settlement_guard();
CREATE TRIGGER account_selected_publication_settlement_immutable BEFORE UPDATE OR DELETE
    ON account_selected_publication_settlements FOR EACH ROW
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_publication_settlement_no_truncate BEFORE TRUNCATE
    ON account_selected_publication_settlements FOR EACH STATEMENT
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();

-- No old authorization/source row is rewritten or qualified. Only receipts committed through
-- the insert guard settle their exact original order; every other order remains pending.
CREATE FUNCTION account_selected_publication_is_settled(operation_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT EXISTS (SELECT 1 FROM account_selected_publication_settlements s
        JOIN account_selected_publication_authorizations a ON a.operation_id = s.operation_id
        WHERE s.operation_id = operation_value AND s.account_binding = a.binding AND s.fence_id = a.fence_id);
$$;

-- Keep every V103 Draft/order/source/terms guard and change only publication pending detection.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$        RAISE EXCEPTION 'Distinct selected-publication operation remains pending' USING ERRCODE = '55P03';$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V103 pending publication guard';
    END IF;
    EXECUTE replace(original, anchor,
        '        IF NOT account_selected_publication_is_settled(operation_value) THEN' || chr(10)
        || anchor || chr(10) || '        END IF;');
END;
$migration$;
-- [jooq ignore stop]
