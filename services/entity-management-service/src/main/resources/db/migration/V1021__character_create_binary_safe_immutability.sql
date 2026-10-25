-- Preserve exact immutable intent bytes without converting the full terminal to JSON.
/* [jooq ignore start] */
CREATE OR REPLACE FUNCTION protect_entity_character_create_operation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    immutable_new entity_character_create_operations%ROWTYPE;
BEGIN
    immutable_new := NEW;
    immutable_new.status := OLD.status;
    immutable_new.terminal_bytes := OLD.terminal_bytes;
    immutable_new.character_uuid := OLD.character_uuid;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('COMMITTED', 'ABORTED')
        OR immutable_new IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'CHARACTER_CREATE intent and terminal are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
/* [jooq ignore stop] */
