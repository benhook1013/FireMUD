-- Restore installed V27.2 stable same-tenant legacy UPDATE behavior immediately after V1009.
-- Canonical mutations and DELETE retain their exact guarded branches before this fastpath.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    INSERT INTO "${serviceSchema}".world_authored_source_tenant_key_reservation AS reservation$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_claim_legacy_numeric_tenant_key()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V1009 legacy reservation claim anchor';
    END IF;
    original := replace(original, anchor, $replacement$    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF TG_OP = 'UPDATE' AND OLD.tenant_id = NEW.tenant_id
        AND EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation retained
            WHERE retained.tenant_key = NEW.tenant_id AND retained.claim_kind = 'LEGACY_NUMERIC') THEN
        RETURN NEW;
    END IF;
    INSERT INTO "${serviceSchema}".world_authored_source_tenant_key_reservation AS reservation$replacement$);
    EXECUTE original;
END;
$migration$;

-- [jooq ignore stop]
