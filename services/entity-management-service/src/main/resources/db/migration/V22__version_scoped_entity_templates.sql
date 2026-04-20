ALTER TABLE items
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE npcs
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE crafting_recipes
    ADD COLUMN version_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX idx_items_tenant_version_id ON items (tenant_id, version_id, id);
CREATE INDEX idx_npcs_tenant_version_id ON npcs (tenant_id, version_id, id);
CREATE INDEX idx_crafting_recipes_tenant_version_id ON crafting_recipes (tenant_id, version_id, id);
