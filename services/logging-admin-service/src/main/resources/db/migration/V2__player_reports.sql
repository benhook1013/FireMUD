CREATE TABLE player_reports (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    reporter_account_id BIGINT NOT NULL,
    target_account_id BIGINT,
    type VARCHAR(20) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
