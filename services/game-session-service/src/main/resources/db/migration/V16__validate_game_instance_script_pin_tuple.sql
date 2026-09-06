-- [jooq ignore start]
ALTER TABLE game_instances
    VALIDATE CONSTRAINT game_instances_script_pin_tuple_coherent;
-- [jooq ignore stop]
