-- Fresh protected creation only; retained historical terminals are never backfilled.
-- Shares the canonical runtime allocator with PRESEEDED_ONLY. No retention horizon is granted.
CREATE TABLE entity_player_created_runtime_projections (
    runtime_entity_id BIGINT NOT NULL DEFAULT nextval('entity_runtime_entity_id_sequence') CHECK (runtime_entity_id > 0),
    tenant_uuid UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    game_instance_id VARCHAR(255) NOT NULL,
    character_uuid UUID NOT NULL,
    account_uuid UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    realm_uuid UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    creation_operation_id UUID NOT NULL,
    mutation_digest VARCHAR(64) NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    terminal_id UUID NOT NULL,
    terminal_digest VARCHAR(64) NOT NULL CHECK (terminal_digest ~ '^[0-9a-f]{64}$'),
    projection_generation BIGINT NOT NULL DEFAULT 1 CHECK (projection_generation = 1),
    projection_fence BIGINT NOT NULL DEFAULT 1 CHECK (projection_fence = 1),
    projection_state VARCHAR(16) NOT NULL DEFAULT 'CURRENT' CHECK (projection_state = 'CURRENT'),
    PRIMARY KEY (tenant_uuid, game_instance_id, runtime_entity_id),
    UNIQUE (runtime_entity_id),
    UNIQUE (tenant_uuid, playable_state_namespace_id, game_instance_id, character_uuid),
    UNIQUE (target_namespace, creation_operation_id),
    FOREIGN KEY (target_namespace, creation_operation_id)
        REFERENCES entity_character_create_operations (target_namespace, operation_id)
);

/* [jooq ignore start] */
-- PostgreSQL recognizes the canonical character UUID index; the H2 DDL simulation does not.
ALTER TABLE entity_player_created_runtime_projections
    ADD CONSTRAINT fk_entity_player_created_runtime_character
    FOREIGN KEY (character_uuid) REFERENCES characters (character_uuid);
CREATE FUNCTION validate_entity_player_created_runtime_projection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM entity_character_create_operations o
        JOIN characters c ON c.character_uuid = o.character_uuid
        WHERE o.target_namespace = NEW.target_namespace
          AND o.operation_id = NEW.creation_operation_id AND o.status = 'COMMITTED'
          AND o.character_uuid = NEW.character_uuid AND o.account_uuid = NEW.account_uuid
          AND o.tenant_uuid = NEW.tenant_uuid
          AND o.playable_state_namespace_id = NEW.playable_state_namespace_id
          AND o.playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'
          AND o.canonical_version_id = NEW.canonical_version_id
          AND o.mutation_digest = NEW.mutation_digest
          AND encode(sha256(o.terminal_bytes), 'hex') = NEW.terminal_digest
          AND c.tenant_uuid = NEW.tenant_uuid AND c.account_uuid = NEW.account_uuid
          AND c.playable_state_namespace_id = NEW.playable_state_namespace_id
          AND c.playable_state_scope = o.playable_state_scope
          AND c.creation_target_namespace = NEW.target_namespace
          AND c.creation_operation_id = NEW.creation_operation_id
          AND c.actor_entry_policy = 'PLAYER_CREATED'
          AND c.actor_identity_status = 'OWNER_RESOLVED'
          AND c.actor_identity_quarantine_reason IS NULL
    ) THEN
        RAISE EXCEPTION 'Runtime projection requires exact committed PLAYER_CREATED provenance';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_player_created_runtime_projection_source
    BEFORE INSERT ON entity_player_created_runtime_projections
    FOR EACH ROW EXECUTE FUNCTION validate_entity_player_created_runtime_projection();
CREATE TRIGGER trg_entity_player_created_runtime_projection_immutable
    BEFORE UPDATE OR DELETE ON entity_player_created_runtime_projections
    FOR EACH ROW EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE TRIGGER trg_entity_player_created_runtime_projection_no_truncate
    BEFORE TRUNCATE ON entity_player_created_runtime_projections
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
/* [jooq ignore stop] */
