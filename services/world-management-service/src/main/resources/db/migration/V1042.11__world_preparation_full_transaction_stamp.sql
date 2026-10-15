-- Preserve the exact V35 manifest row image before adding a server-owned top-level full-XID
-- stamp. The later BEFORE INSERT trigger runs after the existing exact-row validator.
-- PostgreSQL xid8 has no corresponding H2 DDL-interpreter domain; native PostgreSQL tests
-- qualify this internal stamp together with the trigger and selector guard.
-- [jooq ignore start]
ALTER TABLE world_canonical_instance_preparation
    ADD COLUMN preparation_inserted_full_xid xid8;

CREATE FUNCTION world_stamp_preparation_insert_full_xid()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    NEW.preparation_inserted_full_xid := pg_current_xact_id();
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_stamp_preparation_insert_full_xid() FROM PUBLIC;

CREATE TRIGGER trg_world_preparation_full_xid_stamp
    BEFORE INSERT ON world_canonical_instance_preparation
    FOR EACH ROW EXECUTE FUNCTION world_stamp_preparation_insert_full_xid();

-- Fail closed if the exact manifest validator is absent, disabled, has changed shape, or could
-- run after the stamp. Also reject any additional BEFORE INSERT row trigger on this table.
DO $migration$
DECLARE
    preparation_table REGCLASS := '"${serviceSchema}".world_canonical_instance_preparation'::REGCLASS;
    validator_trigger RECORD;
    stamp_trigger RECORD;
    before_insert_row_trigger_count BIGINT;
BEGIN
    SELECT t.* INTO STRICT validator_trigger
    FROM pg_catalog.pg_trigger t
    WHERE t.tgrelid = preparation_table
        AND t.tgname = 'trg_world_canonical_instance_preparation_exact_insert';
    IF validator_trigger.tgisinternal
        OR validator_trigger.tgenabled NOT IN ('O', 'A')
        OR (validator_trigger.tgtype & 7) <> 7
        OR validator_trigger.tgfoid <>
            '"${serviceSchema}".world_validate_canonical_preparation_row()'::REGPROCEDURE::OID THEN
        RAISE EXCEPTION 'Canonical preparation exact-row validator must remain enabled BEFORE INSERT FOR EACH ROW';
    END IF;

    SELECT t.* INTO STRICT stamp_trigger
    FROM pg_catalog.pg_trigger t
    WHERE t.tgrelid = preparation_table
        AND t.tgname = 'trg_world_preparation_full_xid_stamp';
    IF stamp_trigger.tgisinternal
        OR stamp_trigger.tgenabled NOT IN ('O', 'A')
        OR (stamp_trigger.tgtype & 7) <> 7
        OR stamp_trigger.tgfoid <>
            '"${serviceSchema}".world_stamp_preparation_insert_full_xid()'::REGPROCEDURE::OID THEN
        RAISE EXCEPTION 'Preparation full-XID stamp must remain enabled BEFORE INSERT FOR EACH ROW';
    END IF;

    SELECT count(*) INTO before_insert_row_trigger_count
    FROM pg_catalog.pg_trigger t
    WHERE t.tgrelid = preparation_table
        AND (t.tgtype & 7) = 7;
    IF before_insert_row_trigger_count <> 2 THEN
        RAISE EXCEPTION 'Unexpected BEFORE INSERT row trigger changes canonical preparation ordering';
    END IF;

    IF NOT (validator_trigger.tgname COLLATE "C" < stamp_trigger.tgname COLLATE "C") THEN
        RAISE EXCEPTION 'Canonical preparation exact-row validator must run before the full-XID stamp';
    END IF;
END;
$migration$;

-- Replace only the legacy tuple-xmin qualification. Keep the original guard function identity,
-- security attributes, grants, and all other predicates intact; NULL historical stamps fail closed.
DO $migration$
DECLARE
    original TEXT;
    xmin_anchor TEXT := $anchor$            AND p.xmin::TEXT=(txid_current() % 4294967296)::TEXT$anchor$;
    full_xid_predicate TEXT := $replacement$            AND p.preparation_inserted_full_xid=pg_current_xact_id()$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_preparation_start_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,xmin_anchor,'')))/length(xmin_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one preparation start-location xmin guard';
    END IF;
    EXECUTE replace(original, xmin_anchor, full_xid_predicate);
END;
$migration$;
-- [jooq ignore stop]
