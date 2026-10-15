-- [jooq ignore start]
CREATE TRIGGER trg_world_reject_character_location_delete
    BEFORE DELETE ON character_location
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_reject_character_location_truncate
    BEFORE TRUNCATE ON character_location
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();
-- [jooq ignore stop]
