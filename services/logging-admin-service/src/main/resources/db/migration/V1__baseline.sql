CREATE TABLE moderation_actions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL
);

CREATE TABLE player_reports (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    reporter_account_id BIGINT NOT NULL,
    target_account_id BIGINT,
    type VARCHAR(20) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE log_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    message VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id BIGINT
);

CREATE INDEX idx_moderation_actions_tenant_account_created_at
    ON moderation_actions (tenant_id, account_id, created_at DESC);

CREATE INDEX idx_log_events_tenant_timestamp
    ON log_events (tenant_id, timestamp DESC);
