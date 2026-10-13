-- Root 4b059ade (2026-10-14 20:18) approved this forward repair in the guarded graph owner stage.
-- Restore installed V27.2 disjoint legacy UPDATE behavior after the V1004 component guard.
-- Canonical mutations and DELETE retain the exact guarded branch before this fastpath.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    INSERT INTO "${serviceSchema}".world_authored_source_tenant_key_reservation AS reservation$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_claim_legacy_numeric_tenant_key()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V1004 legacy reservation claim anchor';
    END IF;
    original := replace(original, anchor, $replacement$    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
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
