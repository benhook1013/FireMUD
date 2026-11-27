-- The run-owned fixture realm admits only preseeded characters, matching the canonical PLAY chain
-- (Account entitlement and Game Session admission accept PRESEEDED_ONLY). Pre-v1 fixture rows are
-- throwaway; no retained row is rewritten.
-- [jooq ignore start]
DO $migration$
DECLARE
    guard_name TEXT;
    current_guard TEXT;
    anchor TEXT := $anchor$'characterCreationPolicy', 'ALLOW_NEW'$anchor$;
BEGIN
    FOREACH guard_name IN ARRAY ARRAY[
        'guard_published_realm_policy_set()',
        'guard_published_policy_inventory_v2()'] LOOP
        SELECT pg_get_functiondef(guard_name::REGPROCEDURE) INTO STRICT current_guard;
        IF (length(current_guard)-length(replace(current_guard,anchor,'')))/length(anchor) <> 1 THEN
            RAISE EXCEPTION 'Expected exactly one fixture creation policy in %', guard_name;
        END IF;
        EXECUTE replace(current_guard, anchor,
            $replacement$'characterCreationPolicy', 'PRESEEDED_ONLY'$replacement$);
    END LOOP;
END;
$migration$;
-- [jooq ignore stop]
