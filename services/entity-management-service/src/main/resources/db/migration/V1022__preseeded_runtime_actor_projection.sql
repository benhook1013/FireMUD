-- New S3 runtime identity; no existing receipt, character identity or replay bytes are rewritten.
-- Global sequence is deliberately stronger than per-instance uniqueness and is never recycled.
CREATE SEQUENCE entity_runtime_entity_id_sequence AS BIGINT NO CYCLE;
CREATE TABLE entity_preseeded_actor_runtime_projections (
    runtime_entity_id BIGINT NOT NULL DEFAULT nextval('entity_runtime_entity_id_sequence') CHECK (runtime_entity_id > 0),
    tenant_uuid UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    game_instance_id VARCHAR(255) NOT NULL,
    character_uuid UUID NOT NULL,
    assignment_uuid UUID NOT NULL REFERENCES entity_preseeded_actor_assignment_operations (assignment_uuid),
    intent_digest VARCHAR(64) NOT NULL CHECK (intent_digest ~ '^[0-9a-f]{64}$'),
    published_owner_proof_digest VARCHAR(64) NOT NULL CHECK (published_owner_proof_digest ~ '^[0-9a-f]{64}$'),
    projection_generation BIGINT NOT NULL DEFAULT 1 CHECK (projection_generation > 0),
    projection_fence BIGINT NOT NULL DEFAULT 1 CHECK (projection_fence > 0),
    projection_state VARCHAR(16) NOT NULL DEFAULT 'CURRENT' CHECK (projection_state IN ('CURRENT', 'FENCED')),
    PRIMARY KEY (tenant_uuid, game_instance_id, runtime_entity_id),
    UNIQUE (runtime_entity_id),
    UNIQUE (tenant_uuid, playable_state_namespace_id, game_instance_id, character_uuid),
    UNIQUE (assignment_uuid)
);

/* [jooq ignore start] */
CREATE FUNCTION validate_entity_preseeded_runtime_projection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM entity_preseeded_actor_assignment_operations a
        WHERE a.assignment_uuid = NEW.assignment_uuid AND a.status = 'ASSIGNED'
          AND a.completed_at IS NOT NULL AND a.publication_evidence_format = 'CANONICAL_UUID'
          AND a.tenant_uuid = NEW.tenant_uuid
          AND a.playable_state_namespace_id = NEW.playable_state_namespace_id
          AND a.game_instance_id = NEW.game_instance_id AND a.character_uuid = NEW.character_uuid
          AND a.intent_digest = NEW.intent_digest
          AND a.published_owner_proof_digest = NEW.published_owner_proof_digest
    ) THEN
        RAISE EXCEPTION 'Runtime projection requires exact committed assignment provenance';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_preseeded_runtime_projection_source
    BEFORE INSERT ON entity_preseeded_actor_runtime_projections
    FOR EACH ROW EXECUTE FUNCTION validate_entity_preseeded_runtime_projection();
-- No replacement transition is enabled until the exact current replacement-owner chain exists.
CREATE TRIGGER trg_entity_preseeded_runtime_projection_immutable
    BEFORE UPDATE OR DELETE ON entity_preseeded_actor_runtime_projections
    FOR EACH ROW EXECUTE FUNCTION reject_entity_assignment_history_removal();
CREATE TRIGGER trg_entity_preseeded_runtime_projection_no_truncate
    BEFORE TRUNCATE ON entity_preseeded_actor_runtime_projections
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();
/* [jooq ignore stop] */
