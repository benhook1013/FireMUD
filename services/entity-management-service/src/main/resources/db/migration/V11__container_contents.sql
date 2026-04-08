ALTER TABLE items ADD COLUMN is_container BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE container_contents (
    tenant_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id),
    container_item_id BIGINT NOT NULL REFERENCES items(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, character_id, container_item_id, item_id)
);

CREATE INDEX idx_container_contents_character
    ON container_contents(tenant_id, character_id, container_item_id);

CREATE INDEX idx_container_contents_item
    ON container_contents(item_id);
