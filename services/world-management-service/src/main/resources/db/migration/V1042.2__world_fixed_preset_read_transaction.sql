-- Keep committed fixed-preset readback valid inside its writable materialization transaction and
-- in its supported independent read transactions. Preserve the V1041 function identity, grants,
-- security mode and all other body logic by replacing only the exact isolation guard.
-- [jooq ignore start]
DO $migration$
DECLARE
    read_function REGPROCEDURE :=
        '"${serviceSchema}".world_read_run_owned_fixed_preset(text,text,bigint,bigint)'::REGPROCEDURE;
    original_definition TEXT;
    isolation_guard_anchor TEXT := $anchor$    IF current_setting('transaction_read_only') <> 'on'
        AND current_setting('transaction_isolation') NOT IN ('read committed','repeatable read','serializable') THEN
        RAISE EXCEPTION 'Run-owned fixed-preset readback requires an owner read transaction'
            USING ERRCODE = '23514';
    END IF;$anchor$;
    isolation_guard_replacement TEXT := $replacement$    IF NOT (
        (current_setting('transaction_read_only') = 'on'
            AND current_setting('transaction_isolation') IN ('repeatable read','serializable'))
        OR (current_setting('transaction_read_only') = 'off'
            AND current_setting('transaction_isolation') = 'read committed')
    ) THEN
        RAISE EXCEPTION 'Run-owned fixed-preset readback requires an owner read transaction'
            USING ERRCODE = '23514';
    END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef(read_function) INTO STRICT original_definition;
    IF (length(original_definition)
            - length(replace(original_definition, isolation_guard_anchor, '')))
            / length(isolation_guard_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V1041 fixed-preset read transaction guard';
    END IF;
    EXECUTE replace(original_definition, isolation_guard_anchor, isolation_guard_replacement);
END;
$migration$;
-- [jooq ignore stop]
