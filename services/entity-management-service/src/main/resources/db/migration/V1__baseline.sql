CREATE TABLE characters (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL,
    playable_state_key VARCHAR(120) NOT NULL DEFAULT 'shared-live',
    level INT NOT NULL DEFAULT 0,
    experience INT NOT NULL DEFAULT 0,
    strength INT NOT NULL DEFAULT 0,
    agility INT NOT NULL DEFAULT 0,
    intelligence INT NOT NULL DEFAULT 0,
    stamina INT NOT NULL DEFAULT 0,
    health INT NOT NULL DEFAULT 0,
    mana INT NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP NULL,
    version INT NOT NULL DEFAULT 0,
    body_layout_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT'
);

CREATE INDEX idx_characters_tenant_id ON characters(tenant_id);
CREATE INDEX idx_characters_tenant_account_playable_state
    ON characters (tenant_id, account_id, playable_state_key);
CREATE INDEX idx_characters_tenant_playable_state_name
    ON characters (tenant_id, playable_state_key, name);

CREATE TABLE npcs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    behavior VARCHAR(100),
    respawn_delay INTEGER NOT NULL DEFAULT 60,
    last_defeated_at TIMESTAMP,
    tenant_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    version_id BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX idx_npcs_tenant_id ON npcs(tenant_id);
CREATE INDEX idx_npcs_tenant_version_id ON npcs (tenant_id, version_id, id);

CREATE TABLE items (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    description VARCHAR(255),
    equipment_slot VARCHAR(32),
    is_container BOOLEAN NOT NULL DEFAULT false,
    is_stackable BOOLEAN NOT NULL DEFAULT false,
    stack_compatibility_mode VARCHAR(64) NOT NULL DEFAULT 'DEFINITION_ONLY',
    stack_variant_key VARCHAR(128),
    version_id BIGINT NOT NULL DEFAULT 1,
    equipment_slot_group_key VARCHAR(64),
    effect_payload_json TEXT
);

CREATE INDEX idx_items_tenant_id ON items(tenant_id);
CREATE INDEX idx_items_tenant_version_id ON items (tenant_id, version_id, id);

CREATE TABLE inventory (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, item_id)
);

CREATE INDEX idx_inventory_character_id ON inventory(character_id);
CREATE INDEX idx_inventory_item_id ON inventory(item_id);

CREATE TABLE crafting_recipes (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    result_item_id BIGINT NOT NULL REFERENCES items(id),
    result_quantity INT NOT NULL,
    version_id BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX idx_crafting_recipes_tenant_version_id ON crafting_recipes (tenant_id, version_id, id);

CREATE TABLE crafting_ingredients (
    recipe_id BIGINT NOT NULL REFERENCES crafting_recipes(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    PRIMARY KEY (recipe_id, item_id)
);

CREATE TABLE character_friend (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    friend_id BIGINT NOT NULL REFERENCES characters(id),
    tenant_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, friend_id)
);

CREATE INDEX idx_character_friend_character_id ON character_friend(character_id);
CREATE INDEX idx_character_friend_friend_id ON character_friend(friend_id);

CREATE TABLE room_ground_inventory (
    tenant_id BIGINT NOT NULL,
    game_instance_id VARCHAR(100) NOT NULL,
    room_instance_id VARCHAR(100) NOT NULL,
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, game_instance_id, room_instance_id, item_id)
);

CREATE INDEX idx_room_ground_inventory_room
    ON room_ground_inventory(tenant_id, game_instance_id, room_instance_id);
CREATE INDEX idx_room_ground_inventory_item ON room_ground_inventory(item_id);

CREATE TABLE character_equipment (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    slot VARCHAR(32) NOT NULL,
    item_id BIGINT NOT NULL REFERENCES items(id),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, slot)
);

CREATE INDEX idx_character_equipment_character_id
    ON character_equipment(character_id);

CREATE TABLE container_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    equipment_slot VARCHAR(64),
    game_instance_id VARCHAR(255),
    room_instance_id VARCHAR(255),
    item_id BIGINT NOT NULL REFERENCES items(id),
    version INT NOT NULL DEFAULT 0,
    item_instance_id BIGINT
);

CREATE INDEX idx_container_instances_tenant_character
    ON container_instances(tenant_id, character_id);
CREATE INDEX idx_container_instances_room
    ON container_instances(tenant_id, game_instance_id, room_instance_id);
CREATE INDEX idx_container_instances_item
    ON container_instances(item_id);

CREATE TABLE item_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    equipment_slot VARCHAR(64),
    game_instance_id VARCHAR(255),
    room_instance_id VARCHAR(255),
    container_instance_id BIGINT REFERENCES container_instances(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    visible_ref_token VARCHAR(128) NOT NULL,
    visible_ref_sequence BIGINT NOT NULL,
    visible_ref VARCHAR(160) NOT NULL,
    version INT NOT NULL DEFAULT 0
);

ALTER TABLE container_instances
    ADD CONSTRAINT fk_container_instances_item_instance
    FOREIGN KEY (item_instance_id) REFERENCES item_instances(id);

CREATE UNIQUE INDEX ux_container_instances_item_instance
    ON container_instances(item_instance_id);

CREATE INDEX idx_item_instances_tenant_character
    ON item_instances(tenant_id, character_id);
CREATE INDEX idx_item_instances_tenant_equipment
    ON item_instances(tenant_id, character_id, equipment_slot);
CREATE INDEX idx_item_instances_room
    ON item_instances(tenant_id, game_instance_id, room_instance_id);
CREATE INDEX idx_item_instances_container
    ON item_instances(tenant_id, container_instance_id);
CREATE INDEX idx_item_instances_item
    ON item_instances(item_id);
CREATE UNIQUE INDEX ux_item_instances_visible_ref
    ON item_instances(tenant_id, visible_ref);
CREATE UNIQUE INDEX ux_item_instances_visible_ref_sequence
    ON item_instances(tenant_id, visible_ref_token, visible_ref_sequence);

CREATE TABLE item_visible_ref_counters (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    visible_ref_token VARCHAR(128) NOT NULL,
    next_sequence BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_item_visible_ref_counters_token
    ON item_visible_ref_counters(tenant_id, visible_ref_token);

CREATE TABLE item_stacks (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    equipment_slot VARCHAR(64),
    game_instance_id VARCHAR(255),
    room_instance_id VARCHAR(255),
    container_instance_id BIGINT REFERENCES container_instances(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    compatibility_fingerprint VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    stack_family_key VARCHAR(128)
);

CREATE INDEX idx_item_stacks_tenant_character
    ON item_stacks(tenant_id, character_id);
CREATE INDEX idx_item_stacks_room
    ON item_stacks(tenant_id, game_instance_id, room_instance_id);
CREATE INDEX idx_item_stacks_container
    ON item_stacks(tenant_id, container_instance_id);
CREATE INDEX idx_item_stacks_item
    ON item_stacks(item_id);
CREATE UNIQUE INDEX ux_item_stacks_holder_item_fingerprint
    ON item_stacks(
        tenant_id,
        character_id,
        equipment_slot,
        game_instance_id,
        room_instance_id,
        container_instance_id,
        item_id,
        compatibility_fingerprint
    );

CREATE TABLE entity_mutation_effects (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    effect_id VARCHAR(128) NOT NULL,
    operation_name VARCHAR(80) NOT NULL,
    response_type VARCHAR(255),
    response_payload BYTEA,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_entity_mutation_effects_tenant_effect
    ON entity_mutation_effects(tenant_id, effect_id);

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
    ON item_transfer_audits(item_instance_id);
CREATE INDEX idx_item_transfer_audits_correlation
    ON item_transfer_audits(tenant_id, correlation_key);

CREATE TABLE equipment_slot_definitions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL DEFAULT 1,
    slot_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    slot_group_key VARCHAR(64),
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_equipment_slot_definitions_key
    ON equipment_slot_definitions(tenant_id, version_id, slot_key);
CREATE INDEX idx_equipment_slot_definitions_group
    ON equipment_slot_definitions(tenant_id, version_id, slot_group_key);

CREATE TABLE body_layout_slot_definitions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL DEFAULT 1,
    body_layout_key VARCHAR(64) NOT NULL,
    slot_key VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_body_layout_slot_definitions_key
    ON body_layout_slot_definitions(tenant_id, version_id, body_layout_key, slot_key);

CREATE TABLE actor_resource_states (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
    stat_key VARCHAR(120) NOT NULL,
    current_value BIGINT NOT NULL,
    max_value BIGINT,
    base_value BIGINT,
    source_type VARCHAR(64) NOT NULL DEFAULT 'CHARACTER_BASELINE',
    source_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    playable_state_key VARCHAR(120) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_actor_resource_state UNIQUE (tenant_id, playable_state_key, character_id, stat_key)
);

CREATE INDEX idx_actor_resource_state_character
    ON actor_resource_states (tenant_id, playable_state_key, character_id);

CREATE TABLE actor_active_conditions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
    condition_key VARCHAR(120) NOT NULL,
    stack_count INTEGER NOT NULL DEFAULT 1,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(160),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    effect_payload_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    playable_state_key VARCHAR(120) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_actor_active_conditions_character
    ON actor_active_conditions (tenant_id, playable_state_key, character_id);
CREATE INDEX idx_actor_active_conditions_expiry
    ON actor_active_conditions (tenant_id, playable_state_key, expires_at);
