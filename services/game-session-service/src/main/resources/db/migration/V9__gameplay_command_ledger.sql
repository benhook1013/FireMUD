CREATE TABLE gameplay_command (
    id BIGSERIAL PRIMARY KEY,
    command_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    account_id BIGINT,
    character_id BIGINT,
    command_name VARCHAR(80) NOT NULL,
    sanitized_command_text VARCHAR(1000) NOT NULL,
    requires_solo_tick BOOLEAN NOT NULL,
    execution_outcome VARCHAR(40) NOT NULL,
    gameplay_result VARCHAR(40) NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    staged_at TIMESTAMP,
    completed_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(80),
    failure_message VARCHAR(500)
);

CREATE UNIQUE INDEX idx_gameplay_command_command_id ON gameplay_command (command_id);
CREATE INDEX idx_gameplay_command_tenant_instance_status ON gameplay_command (tenant_id, game_instance_id, execution_outcome);
