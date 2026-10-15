-- Fail closed on malformed World terminal affected-unit evidence without rewriting retained rows.
-- V1010 remains immutable; preserve the installed trigger function identity and security settings.
-- Validate expectedEpoch through ->> so numeric JSON counters and canonical decimal strings both remain valid.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    affected_unit_anchor TEXT := $anchor$    FOR unit IN SELECT value FROM jsonb_array_elements(NEW.affected_units_json) LOOP
        IF unit->>'owner' <> 'WORLD_MANAGEMENT' THEN
            RAISE EXCEPTION 'World terminal affected-unit vector contains a foreign owner'
                USING ERRCODE = '23514';
        END IF;
        expected_epoch := (unit->>'expectedEpoch')::BIGINT;$anchor$;
    affected_unit_replacement TEXT := $replacement$    FOR unit IN SELECT value FROM jsonb_array_elements(NEW.affected_units_json) LOOP
        IF unit->>'owner' IS DISTINCT FROM 'WORLD_MANAGEMENT' THEN
            RAISE EXCEPTION 'World terminal affected-unit vector contains a foreign owner'
                USING ERRCODE = '23514';
        END IF;
        IF unit->>'scopeType' IS NULL
            OR unit->>'scopeType' NOT IN (
                'AGGREGATE', 'REGION_SUBTREE', 'ZONE_SUBTREE', 'NEW_EMPTY_REGION'
            ) THEN
            RAISE EXCEPTION 'World terminal affected-unit vector contains an unknown scope type'
                USING ERRCODE = '23514';
        END IF;
        IF unit->>'expectedEpoch' IS NULL
            OR unit->>'expectedEpoch' !~ '^(0|[1-9][0-9]*)$'
            OR length(unit->>'expectedEpoch') > 19
            OR (length(unit->>'expectedEpoch') = 19
                AND (unit->>'expectedEpoch') COLLATE "C" > '9223372036854775807' COLLATE "C") THEN
            RAISE EXCEPTION 'World terminal affected-unit epoch must be a canonical nonnegative BIGINT'
                USING ERRCODE = '23514';
        END IF;
        expected_epoch := (unit->>'expectedEpoch')::BIGINT;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_draft_terminal_outcome_guard()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,affected_unit_anchor,'')))/length(affected_unit_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one World terminal affected-unit validation anchor';
    END IF;
    EXECUTE replace(original, affected_unit_anchor, affected_unit_replacement);
END;
$migration$;
-- [jooq ignore stop]
