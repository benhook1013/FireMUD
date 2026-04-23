ALTER TABLE generation_rule
    ADD COLUMN scope_type VARCHAR(64),
    ADD COLUMN scope_id VARCHAR(128);

CREATE INDEX idx_generation_rule_scope
    ON generation_rule (tenant_id, version_id, scope_type, scope_id, id);

CREATE UNIQUE INDEX uq_generation_rule_scope_name
    ON generation_rule (tenant_id, version_id, scope_type, scope_id, name);
