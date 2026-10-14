-- V1040 already closes every World-row update for the run-owned fixture authority. Correct the
-- diagnostic to describe that full storage boundary without replacing the function or changing
-- its security, grants, identity, or predicates. The separate retained-binding lifecycle guard
-- keeps its narrower and accurate lifecycle diagnostic.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        RAISE EXCEPTION 'Run-owned fixture World lifecycle transitions are disabled'
            USING ERRCODE = '23514';
    END IF;$anchor$;
    replacement TEXT := $replacement$IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        RAISE EXCEPTION 'Run-owned fixture World row mutations are disabled'
            USING ERRCODE = '23514';
    END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_activation_transition()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one run-owned fixture World row-mutation diagnostic anchor';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;
-- [jooq ignore stop]
