-- Add indexes for tenant_id on frequently queried tables
CREATE INDEX IF NOT EXISTS idx_game_tenant_id ON game(tenant_id);
CREATE INDEX IF NOT EXISTS idx_revision_tenant_id ON revision(tenant_id);
CREATE INDEX IF NOT EXISTS idx_version_tenant_id ON version(tenant_id);
CREATE INDEX IF NOT EXISTS idx_runtime_flag_tenant_id ON runtime_flag(tenant_id);
CREATE INDEX IF NOT EXISTS idx_game_templates_tenant_id ON game_templates(tenant_id);
