-- Require both authored room rows and their runtime mappings in fixed-preset readback.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    room_match_start TEXT := $anchor$OR NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room r ON r.id = m.private_row_key$anchor$;
    room_match_start_replacement TEXT := $replacement$OR (SELECT count(*)
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room r ON r.id = m.private_row_key$replacement$;
    room_match_end TEXT := $anchor$AND ri.zone_instance_id = zi.runtime_row_id))
        OR (SELECT count(*) FROM "${serviceSchema}".world_authored_topology_identity m
            JOIN "${serviceSchema}".room_exit e$anchor$;
    room_match_end_replacement TEXT := $replacement$AND ri.zone_instance_id = zi.runtime_row_id)) <> 2
        OR (SELECT count(*) FROM "${serviceSchema}".world_authored_topology_identity m
            JOIN "${serviceSchema}".room_exit e$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_read_run_owned_fixed_preset(text,text,bigint,bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,room_match_start,'')))/length(room_match_start) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one fixed-preset any-room readback predicate start';
    END IF;
    IF (length(original)-length(replace(original,room_match_end,'')))/length(room_match_end) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one fixed-preset room readback predicate end';
    END IF;
    EXECUTE replace(
        replace(original, room_match_start, room_match_start_replacement),
        room_match_end,
        room_match_end_replacement);
END;
$migration$;
-- [jooq ignore stop]
