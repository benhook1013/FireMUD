-- Treat a child transaction's tuple xmin as belonging to its top-level transaction when
-- qualifying publication and fixture rows. Keep the full-XID anti-late-attachment check, but
-- make its stamp server-owned so callers cannot supply a forged value.
-- [jooq ignore start]
ALTER TABLE world_design_publication_fence_attempt
    ADD COLUMN attempt_inserted_full_xid xid8;

CREATE FUNCTION world_stamp_publication_attempt_full_xid()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    server_full_xid xid8 := pg_current_xact_id();
BEGIN
    NEW.attempt_created_full_xid := server_full_xid;
    NEW.attempt_inserted_full_xid := server_full_xid;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_stamp_publication_attempt_full_xid() FROM PUBLIC;

CREATE TRIGGER trg_world_publication_attempt_full_xid_stamp
    BEFORE INSERT ON world_design_publication_fence_attempt
    FOR EACH ROW EXECUTE FUNCTION world_stamp_publication_attempt_full_xid();

DO $migration$
DECLARE
    original TEXT;
    xmin_anchor TEXT := $anchor$
        AND a.xmin::TEXT = (txid_current() % 4294967296)::TEXT$anchor$;
    inserted_xid_predicate TEXT := $replacement$
        AND a.attempt_inserted_full_xid = pg_current_xact_id()$replacement$;
    created_xid_anchor TEXT := $anchor$        AND a.attempt_created_full_xid = pg_current_xact_id()$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_selected_publication_account_binding()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,xmin_anchor,'')))/length(xmin_anchor) <> 1
        OR (length(original)-length(replace(original,created_xid_anchor,'')))/length(created_xid_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one selected-publication Account xmin guard';
    END IF;
    EXECUTE replace(original, xmin_anchor, inserted_xid_predicate);

    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_selected_publication_artifact_inventory()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,xmin_anchor,'')))/length(xmin_anchor) <> 1
        OR (length(original)-length(replace(original,created_xid_anchor,'')))/length(created_xid_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one selected-publication inventory xmin guard';
    END IF;
    EXECUTE replace(original, xmin_anchor, inserted_xid_predicate);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    xmin_anchor TEXT := $anchor$
                    AND retained.xmin::TEXT = (txid_current() % 4294967296)::TEXT$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_retain_preparation_start_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,xmin_anchor,'')))/length(xmin_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one fixture preparation retainer xmin guard';
    END IF;
    EXECUTE replace(original, xmin_anchor, '');
END;
$migration$;
-- [jooq ignore stop]
