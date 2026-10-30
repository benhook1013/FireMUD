-- A retirement receipt is created only as pending intent, before physical absence is proven.
-- [jooq ignore start]
DO $migration$
DECLARE
    definition text;
    anchor text := 'IF TG_OP = ''INSERT'' THEN';
    replacement text := $replacement$IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'PENDING_PHYSICAL_PROOF'
           OR NEW.exact_absence_readback_field_set_bytes IS NOT NULL
           OR NEW.exact_absence_readback_field_set_digest IS NOT NULL
           OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Issuer retirement INSERT must begin pending without an absence proof'
                USING ERRCODE = '23514';
        END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        format('%I.guard_canonical_issuer_binding_local_retirement()', current_schema())::regprocedure)
      INTO STRICT definition;
    IF (length(definition) - length(replace(definition, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected one issuer retirement INSERT branch anchor';
    END IF;
    EXECUTE replace(definition, anchor, replacement);
END;
$migration$;
-- [jooq ignore stop]
