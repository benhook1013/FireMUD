CREATE TABLE item_transfer_audits (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    item_instance_id BIGINT,
    quantity INT NOT NULL,
    stack_family_key VARCHAR(128),
    verb VARCHAR(32) NOT NULL,
    actor_character_id BIGINT,
    session_id VARCHAR(128),
    effect_id VARCHAR(128),
    correlation_key VARCHAR(512) NOT NULL,
    source_holder_kind VARCHAR(32) NOT NULL,
    source_character_id BIGINT,
    source_equipment_slot VARCHAR(64),
    source_game_instance_id VARCHAR(255),
    source_room_instance_id VARCHAR(255),
    source_container_instance_id BIGINT,
    destination_holder_kind VARCHAR(32) NOT NULL,
    destination_character_id BIGINT,
    destination_equipment_slot VARCHAR(64),
    destination_game_instance_id VARCHAR(255),
    destination_room_instance_id VARCHAR(255),
    destination_container_instance_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_item_transfer_audits_tenant_created
    ON item_transfer_audits(tenant_id, created_at DESC);

CREATE INDEX idx_item_transfer_audits_item_instance
    ON item_transfer_audits(item_instance_id)
    WHERE item_instance_id IS NOT NULL;

CREATE INDEX idx_item_transfer_audits_correlation
    ON item_transfer_audits(tenant_id, correlation_key);
