ALTER TABLE feature_flag ADD COLUMN tenant_id BIGINT;
UPDATE feature_flag SET tenant_id = 0 WHERE tenant_id IS NULL;
ALTER TABLE feature_flag ALTER COLUMN tenant_id SET NOT NULL;
