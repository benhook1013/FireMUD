-- Admit both canonical original Account fence versions for selected releases.
-- Keep the complete binary binding and all exact selector/readback checks unchanged.
-- [jooq ignore start]
DO $migration$
DECLARE
    evidence_function REGPROCEDURE := '"${serviceSchema}".enforce_published_world_start_location_evidence()'::REGPROCEDURE;
    original_definition TEXT;
    schema_anchor TEXT := $anchor$        OR convert_from(published_world_selector_frame(account_bytes, 0),'UTF8') IS DISTINCT FROM 'account-draft-authorization-fence/v1'$anchor$;
    schema_replacement TEXT := $replacement$        OR (convert_from(published_world_selector_frame(account_bytes, 0),'UTF8')
                IS DISTINCT FROM 'account-draft-authorization-fence/v1'
            AND convert_from(published_world_selector_frame(account_bytes, 0),'UTF8')
                IS DISTINCT FROM 'account-draft-authorization-fence/v2')$replacement$;
BEGIN
    SELECT pg_get_functiondef(evidence_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, schema_anchor, '')))
            / length(schema_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V69 original Account schema guard';
    END IF;
    original_definition := replace(original_definition, schema_anchor, schema_replacement);
    EXECUTE original_definition;
END;
$migration$;
-- [jooq ignore stop]
