-- V1050 retains the final-CAS/receipt and current RUNNING guards. Carry the Human completion
-- lower bound after that complete definition without rewriting any applied predecessor.
-- [jooq ignore start]
DO $migration$
DECLARE
    definition text;
    original text := 'completion.completed_running_row_version = runtime.row_version';
    replacement text := 'completion.completed_running_row_version <= runtime.row_version';
BEGIN
    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) ||
            '.require_running_runtime_for_lease_bound_binding_decision()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact final Human runtime completion comparison';
    END IF;
    EXECUTE replace(definition, original, replacement);
END;
$migration$;
-- [jooq ignore stop]
