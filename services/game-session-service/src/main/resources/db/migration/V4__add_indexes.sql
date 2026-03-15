CREATE INDEX IF NOT EXISTS idx_game_instances_tenant_id ON game_instances(tenant_id);
CREATE INDEX IF NOT EXISTS idx_game_instances_owner_account_id ON game_instances(owner_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_feature_flag_tenant_name ON feature_flag(tenant_id, name);
CREATE INDEX IF NOT EXISTS idx_feature_flag_tenant_id ON feature_flag(tenant_id);
