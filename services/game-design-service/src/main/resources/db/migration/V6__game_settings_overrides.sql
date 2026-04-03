CREATE TABLE game_settings_override (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    game_instance_id BIGINT NULL,
    domain VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_game_settings_override_tenant_domain
    ON game_settings_override (tenant_id, domain)
    WHERE game_instance_id IS NULL;

CREATE UNIQUE INDEX uq_game_settings_override_game_instance_domain
    ON game_settings_override (tenant_id, game_instance_id, domain)
    WHERE game_instance_id IS NOT NULL;

CREATE INDEX idx_game_settings_override_scope
    ON game_settings_override (tenant_id, game_instance_id);
