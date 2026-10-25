-- New canonical creation stores the authored generic core. Legacy RPG values remain untouched.
ALTER TABLE characters ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN playable_state_key DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN level DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN experience DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN strength DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN agility DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN intelligence DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN stamina DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN health DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN mana DROP NOT NULL;
ALTER TABLE characters ALTER COLUMN body_layout_key DROP NOT NULL;
ALTER TABLE characters ADD COLUMN creation_target_namespace VARCHAR(63);
ALTER TABLE characters ADD COLUMN creation_operation_id UUID;
ALTER TABLE characters ADD COLUMN actor_entry_policy VARCHAR(32);
ALTER TABLE characters ADD COLUMN authored_initial_core_json TEXT;
ALTER TABLE characters ADD COLUMN creation_descriptor_json TEXT;
ALTER TABLE characters ADD COLUMN creation_descriptor_digest VARCHAR(71);

CREATE TABLE entity_character_create_roster_guards (
    tenant_uuid UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    account_uuid UUID NOT NULL,
    PRIMARY KEY (tenant_uuid, playable_state_namespace_id, account_uuid),
    FOREIGN KEY (tenant_uuid, playable_state_namespace_id)
        REFERENCES entity_playable_state_namespace_scopes (tenant_uuid, playable_state_namespace_id)
);

CREATE TABLE entity_character_create_operations (
    target_namespace VARCHAR(63) NOT NULL,
    operation_id UUID NOT NULL,
    create_character_request_id UUID NOT NULL,
    mutation_digest VARCHAR(64) NOT NULL,
    tenant_uuid UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(40) NOT NULL,
    account_uuid UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    original_request BYTEA NOT NULL CHECK (octet_length(original_request) BETWEEN 1 AND 16384),
    original_account BYTEA NOT NULL CHECK (octet_length(original_account) BETWEEN 1 AND 37748736),
    original_account_protection BYTEA NOT NULL CHECK (octet_length(original_account_protection) BETWEEN 1 AND 41943040),
    original_game_session_protection BYTEA NOT NULL CHECK (octet_length(original_game_session_protection) BETWEEN 1 AND 50331648),
    original_world_protection BYTEA NOT NULL CHECK (octet_length(original_world_protection) BETWEEN 1 AND 50331648),
    entity_source_receipt BYTEA NOT NULL CHECK (octet_length(entity_source_receipt) BETWEEN 1 AND 83886080),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    terminal_bytes BYTEA,
    character_uuid UUID,
    PRIMARY KEY (target_namespace, operation_id),
    UNIQUE (tenant_uuid, playable_state_namespace_id, account_uuid, create_character_request_id),
    UNIQUE (character_uuid),
    FOREIGN KEY (target_namespace, tenant_uuid, canonical_version_id)
        REFERENCES entity_empty_selected_source_association
            (target_namespace, canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (tenant_uuid, playable_state_namespace_id, playable_state_scope)
        REFERENCES entity_playable_state_namespace_scopes
            (tenant_uuid, playable_state_namespace_id, playable_state_scope),
    CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND create_character_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK ((status = 'PENDING' AND terminal_bytes IS NULL AND character_uuid IS NULL)
        OR (status = 'COMMITTED' AND terminal_bytes IS NOT NULL
            AND octet_length(terminal_bytes) BETWEEN 1 AND 201326592 AND character_uuid IS NOT NULL)
        OR (status = 'ABORTED' AND terminal_bytes IS NOT NULL
            AND octet_length(terminal_bytes) BETWEEN 1 AND 201326592 AND character_uuid IS NULL))
);

/* [jooq ignore start] */
ALTER TABLE characters ADD CONSTRAINT fk_character_creation_operation
    FOREIGN KEY (creation_target_namespace, creation_operation_id)
    REFERENCES entity_character_create_operations (target_namespace, operation_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE entity_character_create_operations ADD CONSTRAINT fk_creation_terminal_character
    FOREIGN KEY (character_uuid) REFERENCES characters (character_uuid)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE characters ADD CONSTRAINT ck_character_created_core CHECK (
    (actor_entry_policy IS NULL AND creation_target_namespace IS NULL
        AND creation_operation_id IS NULL AND authored_initial_core_json IS NULL
        AND creation_descriptor_json IS NULL AND creation_descriptor_digest IS NULL
        AND account_id IS NOT NULL AND playable_state_key IS NOT NULL
        AND level IS NOT NULL AND experience IS NOT NULL AND strength IS NOT NULL
        AND agility IS NOT NULL AND intelligence IS NOT NULL AND stamina IS NOT NULL
        AND health IS NOT NULL AND mana IS NOT NULL AND body_layout_key IS NOT NULL)
    OR (actor_entry_policy = 'PLAYER_CREATED' AND creation_target_namespace IS NOT NULL
        AND creation_operation_id IS NOT NULL AND actor_identity_status = 'OWNER_RESOLVED'
        AND account_id IS NULL AND playable_state_key IS NULL
        AND level IS NULL AND experience IS NULL AND strength IS NULL AND agility IS NULL
        AND intelligence IS NULL AND stamina IS NULL AND health IS NULL AND mana IS NULL
        AND body_layout_key IS NULL AND authored_initial_core_json IS NOT NULL
        AND creation_descriptor_json IS NOT NULL
        AND creation_descriptor_digest IS NOT NULL
        AND creation_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND name = authored_initial_core_json::JSONB ->> 'displayName')
);

CREATE FUNCTION protect_entity_character_create_operation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('COMMITTED', 'ABORTED')
        OR (to_jsonb(NEW) - 'status' - 'terminal_bytes' - 'character_uuid')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'terminal_bytes' - 'character_uuid') THEN
        RAISE EXCEPTION 'CHARACTER_CREATE intent and terminal are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_character_create_operation_immutable
    BEFORE UPDATE ON entity_character_create_operations FOR EACH ROW
    EXECUTE FUNCTION protect_entity_character_create_operation();
CREATE TRIGGER trg_entity_character_create_operation_no_removal
    BEFORE DELETE OR TRUNCATE ON entity_character_create_operations FOR EACH STATEMENT
    EXECUTE FUNCTION reject_entity_assignment_history_removal();

CREATE FUNCTION protect_entity_created_actor() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.actor_entry_policy = 'PLAYER_CREATED' THEN
            RAISE EXCEPTION 'CHARACTER_CREATE committed actor is retained' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.actor_entry_policy = 'PLAYER_CREATED' AND
       (NEW.character_uuid, NEW.account_uuid, NEW.tenant_uuid, NEW.tenant_id, NEW.playable_state_namespace_id,
        NEW.playable_state_scope, NEW.creation_target_namespace, NEW.creation_operation_id,
        NEW.actor_entry_policy, NEW.authored_initial_core_json, NEW.creation_descriptor_json,
        NEW.creation_descriptor_digest, NEW.name)
        IS DISTINCT FROM
       (OLD.character_uuid, OLD.account_uuid, OLD.tenant_uuid, OLD.tenant_id, OLD.playable_state_namespace_id,
        OLD.playable_state_scope, OLD.creation_target_namespace, OLD.creation_operation_id,
        OLD.actor_entry_policy, OLD.authored_initial_core_json, OLD.creation_descriptor_json,
        OLD.creation_descriptor_digest, OLD.name) THEN
        RAISE EXCEPTION 'CHARACTER_CREATE authored identity/provenance is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.actor_entry_policy IS NULL AND NEW.actor_entry_policy IS NOT NULL THEN
        RAISE EXCEPTION 'Retained actors cannot be relabelled PLAYER_CREATED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_created_actor_immutable BEFORE UPDATE OR DELETE ON characters
    FOR EACH ROW EXECUTE FUNCTION protect_entity_created_actor();
/* [jooq ignore stop] */

/* [jooq ignore start] */
-- A terminal and its actor association must settle together, including direct SQL writers.
CREATE FUNCTION validate_entity_character_create_actor_terminal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    operation_row entity_character_create_operations%ROWTYPE;
    actor_row characters%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'characters' THEN
        IF NEW.actor_entry_policy IS DISTINCT FROM 'PLAYER_CREATED' THEN RETURN NEW; END IF;
        SELECT * INTO operation_row FROM entity_character_create_operations
            WHERE target_namespace = NEW.creation_target_namespace AND operation_id = NEW.creation_operation_id;
        actor_row := NEW;
    ELSE
        SELECT * INTO operation_row FROM entity_character_create_operations
            WHERE target_namespace = NEW.target_namespace AND operation_id = NEW.operation_id;
        IF operation_row.status = 'PENDING' THEN
            RAISE EXCEPTION 'CHARACTER_CREATE cannot commit a nonterminal operation' USING ERRCODE = '23514';
        END IF;
        IF operation_row.status = 'ABORTED' THEN
            IF EXISTS (SELECT 1 FROM characters WHERE creation_target_namespace = NEW.target_namespace
                AND creation_operation_id = NEW.operation_id) THEN
                RAISE EXCEPTION 'ABORTED CHARACTER_CREATE has an actor' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
        SELECT * INTO actor_row FROM characters WHERE character_uuid = operation_row.character_uuid;
    END IF;
    IF operation_row.status IS DISTINCT FROM 'COMMITTED'
        OR actor_row.actor_entry_policy IS DISTINCT FROM 'PLAYER_CREATED'
        OR (actor_row.character_uuid, actor_row.tenant_uuid, actor_row.account_uuid,
            actor_row.playable_state_namespace_id, actor_row.playable_state_scope,
            actor_row.creation_target_namespace, actor_row.creation_operation_id)
            IS DISTINCT FROM
           (operation_row.character_uuid, operation_row.tenant_uuid, operation_row.account_uuid,
            operation_row.playable_state_namespace_id, operation_row.playable_state_scope,
            operation_row.target_namespace, operation_row.operation_id) THEN
        RAISE EXCEPTION 'CHARACTER_CREATE terminal differs from its canonical actor association'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_entity_created_actor_terminal
    AFTER INSERT OR UPDATE ON characters DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_entity_character_create_actor_terminal();
CREATE CONSTRAINT TRIGGER trg_entity_creation_terminal_actor
    AFTER INSERT OR UPDATE ON entity_character_create_operations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_entity_character_create_actor_terminal();

CREATE FUNCTION protect_entity_character_create_roster_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'CHARACTER_CREATE roster serialization identity is retained'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_entity_creation_roster_guard_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON entity_character_create_roster_guards
    FOR EACH STATEMENT EXECUTE FUNCTION protect_entity_character_create_roster_guard();

CREATE FUNCTION protect_entity_created_actor_truncate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM characters WHERE actor_entry_policy = 'PLAYER_CREATED') THEN
        RAISE EXCEPTION 'CHARACTER_CREATE committed actors are retained' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_entity_created_actor_no_truncate BEFORE TRUNCATE ON characters
    FOR EACH STATEMENT EXECUTE FUNCTION protect_entity_created_actor_truncate();
/* [jooq ignore stop] */
