ALTER TABLE items ADD COLUMN version INT NOT NULL DEFAULT 0;
CREATE INDEX idx_items_tenant_id ON items(tenant_id);
CREATE INDEX idx_characters_tenant_id ON characters(tenant_id);
CREATE INDEX idx_npcs_tenant_id ON npcs(tenant_id);
