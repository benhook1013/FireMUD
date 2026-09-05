-- [jooq ignore start]
ALTER TABLE game_instances
    VALIDATE CONSTRAINT game_instances_unpinned_script_pin_metadata_coherent;
-- [jooq ignore stop]
