-- Canonical identity belongs to Game Session. Retained numeric rows remain explicitly unmapped.
ALTER TABLE game_instances
    ADD COLUMN game_instance_uuid uuid;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_game_instance_uuid_non_nil
        CHECK (
            game_instance_uuid IS NULL
            OR game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::uuid
        );

ALTER TABLE game_instances
    ADD CONSTRAINT uq_game_instances_game_instance_uuid UNIQUE (game_instance_uuid);

-- [jooq ignore start]
CREATE FUNCTION enforce_game_instance_uuid_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.game_instance_uuid IS DISTINCT FROM OLD.game_instance_uuid THEN
        RAISE EXCEPTION 'Game Session game_instance_uuid identity is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'game_instances_game_instance_uuid_immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER game_instances_game_instance_uuid_immutable
    BEFORE UPDATE OF game_instance_uuid ON game_instances
    FOR EACH ROW
    EXECUTE FUNCTION enforce_game_instance_uuid_immutable();
-- [jooq ignore stop]
