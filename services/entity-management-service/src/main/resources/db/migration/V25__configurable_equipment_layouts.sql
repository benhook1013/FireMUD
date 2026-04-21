ALTER TABLE characters
    ADD COLUMN body_layout_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE items
    ADD COLUMN equipment_slot_group_key VARCHAR(64);

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
