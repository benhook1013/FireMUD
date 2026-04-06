CREATE TABLE container_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    version INT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, character_id, item_id)
);

CREATE INDEX idx_container_instances_tenant_character
    ON container_instances(tenant_id, character_id);

CREATE INDEX idx_container_instances_item
    ON container_instances(item_id);

DROP TABLE container_contents;

CREATE TABLE container_contents (
    tenant_id BIGINT NOT NULL,
    container_instance_id BIGINT NOT NULL REFERENCES container_instances(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, container_instance_id, item_id)
);

CREATE INDEX idx_container_contents_container
    ON container_contents(tenant_id, container_instance_id);

CREATE INDEX idx_container_contents_item
    ON container_contents(item_id);
