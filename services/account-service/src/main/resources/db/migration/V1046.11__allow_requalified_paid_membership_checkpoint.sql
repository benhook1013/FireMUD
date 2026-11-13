-- A paid acquisition may requalify the current caller after an ordinary membership transition
-- invalidated older caller-bound authority. Preserve that transition as immutable history while
-- the new checkpoint records its own non-invalidating result at the already-advanced generation.
-- [jooq ignore start]
DO $migration$
DECLARE
    definition TEXT;
    previous_clause TEXT;
    next_clause TEXT;
BEGIN
    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) || '.account_membership_pair_authority_update_guard()')::regprocedure)
        INTO STRICT definition;

    previous_clause := $previous$
            OR OLD.last_transition_invalidated
$previous$;
    next_clause := '';
    IF (length(definition) - length(replace(definition, previous_clause, '')))
        / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected one prior-transition invalidation guard in membership pair authority update';
    END IF;
    EXECUTE replace(definition, previous_clause, next_clause);

    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) || '.account_paid_membership_checkpoint_consistency()')::regprocedure)
        INTO STRICT definition;

    previous_clause := $previous$
        OR prior->'callerBoundAuthorityInvalidated' IS DISTINCT FROM 'false'::jsonb
$previous$;
    next_clause := $next$
        OR (prior->'callerBoundAuthorityInvalidated' IS DISTINCT FROM 'false'::jsonb
            AND prior->'callerBoundAuthorityInvalidated' IS DISTINCT FROM 'true'::jsonb)$next$;
    IF (length(definition) - length(replace(definition, previous_clause, '')))
        / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected one prior-event invalidation guard in paid membership checkpoint consistency';
    END IF;
    EXECUTE replace(definition, previous_clause, next_clause);

    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) || '.account_paid_membership_checkpoint_predecessor()')::regprocedure)
        INTO STRICT definition;

    previous_clause := $previous$
            AND pair.membership_exists AND pair.last_event_sequence > 0
$previous$;
    next_clause := $next$
            AND pair.membership_exists AND pair.last_event_sequence > 0
            AND convert_from(prior.payload, 'UTF8')::JSONB->'callerBoundAuthorityInvalidated'
                = to_jsonb(pair.last_transition_invalidated)
$next$;
    IF (length(definition) - length(replace(definition, previous_clause, '')))
        / length(previous_clause) <> 1 THEN
        RAISE EXCEPTION 'Expected one exact membership predecessor in paid membership checkpoint guard';
    END IF;
    EXECUTE replace(definition, previous_clause, next_clause);
END;
$migration$;
-- [jooq ignore stop]
