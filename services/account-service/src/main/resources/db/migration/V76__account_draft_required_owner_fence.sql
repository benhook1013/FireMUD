-- Forward-only Account Draft required-owner/readback closure. Preserve every original binding and readback byte.
-- Shared byte-frame helpers are used by this Account owner only; no publication tables or authority are installed.
-- Source keys are shared with current Account issuer evidence. Widen without rewriting stored values.
-- PostgreSQL and the H2 schema simulator assign different names to V76's inline constraints.
-- jOOQ omits only these name-dependent swaps from H2 simulation; Flyway executes all swaps below.
-- [jooq ignore start]
ALTER TABLE account_draft_authorization_sources
    DROP CONSTRAINT account_draft_authorization_sources_source_key_fkey;
ALTER TABLE account_draft_authorization_changed_scopes
    DROP CONSTRAINT account_draft_authorization_changed_scopes_source_key_fkey;
-- [jooq ignore stop]
-- [jooq ignore start]
ALTER TABLE account_draft_authorization_source_locks
    DROP CONSTRAINT account_draft_authorization_source_locks_source_key_check;
-- [jooq ignore stop]

ALTER TABLE account_draft_authorization_source_locks
    ALTER COLUMN source_key TYPE VARCHAR(2048);
ALTER TABLE account_draft_authorization_sources
    ALTER COLUMN source_key TYPE VARCHAR(2048);
ALTER TABLE account_draft_authorization_changed_scopes
    ALTER COLUMN source_key TYPE VARCHAR(2048);

-- [jooq ignore start]
ALTER TABLE account_draft_authorization_source_locks
    ADD CONSTRAINT account_draft_authorization_source_locks_source_key_check
        CHECK (length(source_key) > 0 AND octet_length(source_key) <= 2048);
-- [jooq ignore stop]
-- [jooq ignore start]
ALTER TABLE account_draft_authorization_sources
    ADD CONSTRAINT account_draft_authorization_sources_source_key_fkey
        FOREIGN KEY (source_key) REFERENCES account_draft_authorization_source_locks(source_key);
ALTER TABLE account_draft_authorization_changed_scopes
    ADD CONSTRAINT account_draft_authorization_changed_scopes_source_key_fkey
        FOREIGN KEY (source_key) REFERENCES account_draft_authorization_source_locks(source_key);
-- [jooq ignore stop]

-- [jooq ignore start]
ALTER TABLE account_draft_authorization_owner_readbacks
    DROP CONSTRAINT account_draft_authorization_owner_readbacks_owner_check;
ALTER TABLE account_draft_authorization_owner_readbacks
    ADD CONSTRAINT account_draft_authorization_owner_readbacks_owner_check
        CHECK (owner IN ('GAME_DESIGN', 'WORLD', 'ENTITY', 'GAME_LOGIC', 'AUTOMATION'));
-- [jooq ignore stop]

-- [jooq ignore start]
CREATE FUNCTION account_publication_authorization_source_sort_key(value TEXT)
RETURNS INTEGER[] LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    units INTEGER[] := ARRAY[]::INTEGER[];
    codepoint INTEGER;
    character_index INTEGER;
BEGIN
    FOR character_index IN 1..char_length(value) LOOP
        codepoint := ascii(substring(value FROM character_index FOR 1));
        IF codepoint > 65535 THEN
            units := array_append(units, 55296 + ((codepoint - 65536) / 1024));
            units := array_append(units, 56320 + ((codepoint - 65536) % 1024));
        ELSE
            units := array_append(units, codepoint);
        END IF;
    END LOOP;
    RETURN units;
END;
$$;

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

-- Forward-only structural validation. Retained bindings and owner evidence are never rewritten.
CREATE FUNCTION account_draft_authorization_required_owners(encoded BYTEA)
RETURNS TEXT[] LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    frames BYTEA[] := ARRAY[]::BYTEA[];
    position_value INTEGER := 1;
    parsed BYTEA;
    schema_value TEXT;
    source_count INTEGER;
    owner_start INTEGER;
    owners TEXT[] := ARRAY[]::TEXT[];
    derived TEXT[];
    complete JSONB;
    declared TEXT[];
    revision_owners TEXT[];
    affected_owners TEXT[];
    allowed TEXT[] := ARRAY['WORLD_MANAGEMENT', 'ENTITY_MANAGEMENT', 'GAME_LOGIC',
        'AUTOMATION_SCRIPTING', 'GAME_DESIGN_CONTROL_PLANE'];
    i INTEGER;
BEGIN
    WHILE position_value <= octet_length(encoded) LOOP
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(encoded, position_value);
        IF octet_length(parsed) = 0 THEN
            RAISE EXCEPTION 'Draft authorization contains an empty frame' USING ERRCODE = '23514';
        END IF;
        frames := array_append(frames, parsed);
    END LOOP;
    schema_value := convert_from(frames[1], 'UTF8');
    IF schema_value NOT IN ('account-draft-authorization-fence/v1', 'account-draft-authorization-fence/v2')
        OR cardinality(frames) < 18
        OR convert_from(frames[10], 'UTF8') <> 'DRAFT'
        OR convert_from(frames[11], 'UTF8') !~ '^(0|[1-9][0-9]*)$'
        OR convert_from(frames[15], 'UTF8') !~ '^[1-9][0-9]*$' THEN
        RAISE EXCEPTION 'Invalid original Draft authorization framing' USING ERRCODE = '23514';
    END IF;
    source_count := convert_from(frames[15], 'UTF8')::INTEGER;
    owner_start := 16 + source_count;
    IF owner_start > cardinality(frames) THEN
        RAISE EXCEPTION 'Incomplete original Draft source capture' USING ERRCODE = '23514';
    END IF;
    IF schema_value = 'account-draft-authorization-fence/v1' THEN
        IF cardinality(frames) <> owner_start + 1
            OR convert_from(frames[owner_start], 'UTF8') <> 'GAME_DESIGN'
            OR convert_from(frames[owner_start + 1], 'UTF8') <> 'WORLD' THEN
            RAISE EXCEPTION 'V1 original owner set is immutable' USING ERRCODE = '23514';
        END IF;
        RETURN ARRAY['GAME_DESIGN', 'WORLD'];
    END IF;
    IF convert_from(frames[owner_start], 'UTF8') !~ '^[1-5]$'
        OR cardinality(frames) <> owner_start + convert_from(frames[owner_start], 'UTF8')::INTEGER THEN
        RAISE EXCEPTION 'Incomplete or trailing V2 owner vector' USING ERRCODE = '23514';
    END IF;
    FOR i IN owner_start + 1 .. cardinality(frames) LOOP
        owners := array_append(owners, convert_from(frames[i], 'UTF8'));
    END LOOP;
    complete := convert_from(frames[12], 'UTF8')::JSONB;
    IF frames[12] IS DISTINCT FROM frames[13]
        OR convert_from(frames[14], 'UTF8') IS DISTINCT FROM 'sha256:' || encode(sha256(frames[12]), 'hex')
        OR complete->>'schemaVersion' IS DISTINCT FROM '1'
        OR complete->>'requestId' IS DISTINCT FROM convert_from(frames[3], 'UTF8')
        OR complete->>'commitId' IS DISTINCT FROM convert_from(frames[4], 'UTF8')
        OR complete->>'canonicalTenantId' IS DISTINCT FROM convert_from(frames[7], 'UTF8')
        OR complete->>'canonicalVersionId' IS DISTINCT FROM convert_from(frames[8], 'UTF8')
        OR complete->>'baseCommitId' IS DISTINCT FROM convert_from(frames[9], 'UTF8')
        OR jsonb_typeof(complete->'requiredOwners') IS DISTINCT FROM 'array'
        OR jsonb_typeof(complete->'revisions') IS DISTINCT FROM 'array'
        OR jsonb_typeof(complete->'affectedUnits') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'V2 requires the complete original Game Design binding' USING ERRCODE = '23514';
    END IF;
    SELECT array_agg(value ORDER BY ordinal) INTO declared
        FROM jsonb_array_elements_text(complete->'requiredOwners') WITH ORDINALITY entry(value, ordinal);
    SELECT array_agg(DISTINCT value->>'owner' ORDER BY value->>'owner') INTO revision_owners
        FROM jsonb_array_elements(complete->'revisions');
    SELECT array_agg(DISTINCT value->>'owner' ORDER BY value->>'owner') INTO affected_owners
        FROM jsonb_array_elements(complete->'affectedUnits');
    IF declared IS NULL OR revision_owners IS NULL OR affected_owners IS NULL
        OR NOT declared <@ allowed OR NOT revision_owners <@ allowed OR NOT affected_owners <@ allowed
        OR array_position(revision_owners, NULL) IS NOT NULL
        OR array_position(affected_owners, NULL) IS NOT NULL
        OR declared IS DISTINCT FROM (SELECT array_agg(label ORDER BY ordinal)
            FROM unnest(allowed) WITH ORDINALITY entry(label, ordinal) WHERE label = ANY(revision_owners))
        OR revision_owners IS DISTINCT FROM affected_owners THEN
        RAISE EXCEPTION 'Required owners differ from complete revision and affected-owner coverage'
            USING ERRCODE = '23514';
    END IF;
    SELECT array_agg(label ORDER BY ordinal) INTO derived
        FROM unnest(ARRAY['GAME_DESIGN', 'WORLD', 'ENTITY', 'GAME_LOGIC', 'AUTOMATION'])
            WITH ORDINALITY entry(label, ordinal)
        WHERE label = 'GAME_DESIGN' OR label = ANY(SELECT CASE value
            WHEN 'WORLD_MANAGEMENT' THEN 'WORLD' WHEN 'ENTITY_MANAGEMENT' THEN 'ENTITY'
            WHEN 'GAME_LOGIC' THEN 'GAME_LOGIC' WHEN 'AUTOMATION_SCRIPTING' THEN 'AUTOMATION'
            WHEN 'GAME_DESIGN_CONTROL_PLANE' THEN 'GAME_DESIGN' END FROM unnest(declared) value);
    IF owners IS DISTINCT FROM derived THEN
        RAISE EXCEPTION 'V2 owner vector differs from complete original binding' USING ERRCODE = '23514';
    END IF;
    RETURN owners;
END;
$$;

CREATE FUNCTION account_draft_authorization_valid_readback(
    encoded BYTEA, original BYTEA, operation UUID, commit_value UUID, fence_value UUID,
    owner_value TEXT, outcome_value TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    position_value INTEGER := 1;
    parsed BYTEA;
    values_array BYTEA[] := ARRAY[]::BYTEA[];
    original_position INTEGER := 1;
    original_frames BYTEA[] := ARRAY[]::BYTEA[];
BEGIN
    WHILE position_value <= octet_length(encoded) LOOP
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(encoded, position_value);
        values_array := array_append(values_array, parsed);
    END LOOP;
    -- The original decoder validates its complete owner vector and trailing-free framing.
    IF NOT owner_value = ANY(account_draft_authorization_required_owners(original)) THEN
        RETURN FALSE;
    END IF;
    FOR i IN 1..14 LOOP
        SELECT frame_value, next_position INTO parsed, original_position
            FROM account_publication_authorization_read_frame(original, original_position);
        original_frames := array_append(original_frames, parsed);
    END LOOP;
    RETURN cardinality(values_array) = 9 AND octet_length(values_array[9]) > 0
        AND convert_from(values_array[1], 'UTF8') = replace(convert_from(original_frames[1], 'UTF8'),
            'account-draft-authorization-fence/', 'account-draft-owner-readback/')
        AND convert_from(values_array[2], 'UTF8') = owner_value
        AND convert_from(values_array[3], 'UTF8') = outcome_value
        AND values_array[4] = original_frames[2]
        AND convert_from(values_array[4], 'UTF8') = operation::TEXT
        AND values_array[5] = original_frames[4]
        AND convert_from(values_array[5], 'UTF8') = commit_value::TEXT
        AND values_array[6] = original_frames[5]
        AND convert_from(values_array[6], 'UTF8') = fence_value::TEXT
        AND values_array[7] = original_frames[14] AND values_array[8] = original;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE FUNCTION account_draft_authorization_complete_sources(operation UUID, original BYTEA)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    position_value INTEGER := 1;
    parsed BYTEA;
    source_count INTEGER;
    source_bytes BYTEA;
    source_position INTEGER;
    source_kind TEXT;
    source_scope TEXT;
    source_key_value TEXT;
    seen TEXT[] := ARRAY[]::TEXT[];
BEGIN
    FOR i IN 1..15 LOOP
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(original, position_value);
    END LOOP;
    source_count := convert_from(parsed, 'UTF8')::INTEGER;
    IF source_count < 1 OR source_count <> (SELECT count(*)
        FROM account_draft_authorization_sources WHERE operation_id = operation) THEN
        RETURN FALSE;
    END IF;
    FOR i IN 1..source_count LOOP
        SELECT frame_value, next_position INTO source_bytes, position_value
            FROM account_publication_authorization_read_frame(original, position_value);
        SELECT frame_value, next_position INTO parsed, source_position
            FROM account_publication_authorization_read_frame(source_bytes, 1);
        IF convert_from(parsed, 'UTF8') <> 'account-draft-source-evidence/v1' THEN RETURN FALSE; END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_kind, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_scope, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        source_key_value := source_kind || ':' || source_scope;
        IF source_key_value = ANY(seen) OR NOT EXISTS (
            SELECT 1 FROM account_draft_authorization_sources
            WHERE operation_id = operation AND source_key = source_key_value
                AND source_evidence = source_bytes) THEN
            RETURN FALSE;
        END IF;
        seen := array_append(seen, source_key_value);
    END LOOP;
    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE FUNCTION account_draft_authorization_owner_readback_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fence account_draft_authorization_fences%ROWTYPE;
    key_value TEXT;
BEGIN
    FOR key_value IN SELECT source_key FROM account_draft_authorization_sources
        WHERE operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(source_key) LOOP
        PERFORM 1 FROM account_draft_authorization_source_locks WHERE source_key = key_value FOR UPDATE;
    END LOOP;
    SELECT * INTO fence FROM account_draft_authorization_fences WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF fence.ordering IS NULL OR fence.ordering = 'RESERVED'
        OR NOT account_draft_authorization_complete_sources(fence.operation_id, fence.binding)
        OR NOT account_draft_authorization_valid_readback(NEW.readback, fence.binding,
            fence.operation_id, fence.commit_id, fence.fence_id, NEW.owner, NEW.outcome) THEN
        RAISE EXCEPTION 'Owner result must exactly bind one original required owner'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Replace only the Draft readback insert-phase trigger; other independent guards remain intact.
DROP TRIGGER account_draft_authorization_readback_insert_phase ON account_draft_authorization_owner_readbacks;
CREATE TRIGGER account_draft_authorization_readback_insert_phase
    BEFORE INSERT ON account_draft_authorization_owner_readbacks
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_owner_readback_guard();

CREATE FUNCTION account_draft_authorization_is_settled(operation UUID)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    fence account_draft_authorization_fences%ROWTYPE;
    owners TEXT[];
    actual TEXT[];
    outcomes INTEGER;
BEGIN
    SELECT * INTO fence FROM account_draft_authorization_fences WHERE operation_id = operation;
    IF NOT FOUND OR fence.ordering = 'RESERVED' THEN RETURN FALSE; END IF;
    IF NOT account_draft_authorization_complete_sources(fence.operation_id, fence.binding) THEN RETURN FALSE; END IF;
    owners := account_draft_authorization_required_owners(fence.binding);
    SELECT array_agg(owner ORDER BY array_position(owners, owner)), count(DISTINCT outcome)
        INTO actual, outcomes FROM account_draft_authorization_owner_readbacks WHERE operation_id = operation;
    RETURN actual IS NOT DISTINCT FROM owners AND outcomes = 1
        AND NOT EXISTS (SELECT 1 FROM account_draft_authorization_owner_readbacks r
            WHERE r.operation_id = operation AND (
                NOT account_draft_authorization_valid_readback(r.readback, fence.binding,
                    fence.operation_id, fence.commit_id, fence.fence_id, r.owner, r.outcome)
                OR (fence.ordering = 'REVOKE_ORDER' AND r.outcome <> 'DEFINITIVELY_ABORTED')));
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE OR REPLACE FUNCTION account_draft_authorization_source_change_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    key_value TEXT;
    operation UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Draft source change cannot be deleted'; END IF;
    IF OLD.status <> 'WAITING' OR NEW.status NOT IN ('SOURCE_COMMITTED', 'SOURCE_ABORTED')
        OR NEW.change_id IS DISTINCT FROM OLD.change_id OR NEW.binding IS DISTINCT FROM OLD.binding
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR (NEW.status = 'SOURCE_COMMITTED' AND (NEW.committed_at IS NULL OR NEW.aborted_at IS NOT NULL OR NEW.abort_reason IS NOT NULL))
        OR (NEW.status = 'SOURCE_ABORTED' AND (NEW.committed_at IS NOT NULL OR NEW.aborted_at IS NULL
            OR NEW.abort_reason IS NULL OR NEW.abort_reason NOT IN ('EXPIRED', 'DEFINITIVE_ABORT'))) THEN
        RAISE EXCEPTION 'Draft source change result is immutable' USING ERRCODE = '23514';
    END IF;
    FOR key_value IN SELECT source_key FROM account_draft_authorization_changed_scopes
        WHERE change_id = OLD.change_id ORDER BY account_publication_authorization_source_sort_key(source_key) LOOP
        PERFORM 1 FROM account_draft_authorization_source_locks WHERE source_key = key_value FOR UPDATE;
    END LOOP;
    FOR operation IN SELECT DISTINCT source.operation_id
        FROM account_draft_authorization_changed_scopes changed
        JOIN account_draft_authorization_sources source USING (source_key)
        WHERE changed.change_id = OLD.change_id ORDER BY source.operation_id LOOP
        PERFORM 1 FROM account_draft_authorization_fences WHERE operation_id = operation FOR UPDATE;
        IF NOT account_draft_authorization_is_settled(operation) THEN
            RAISE EXCEPTION 'Every original required owner must have one uniform exact terminal outcome'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
