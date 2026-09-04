-- Admission-mode request identity is immutable history. The mutable admission-state row is only
-- the current projection and must not be the idempotency authority after later mode transitions.
CREATE TABLE automation_admission_request_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    mode VARCHAR(64) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    admission_epoch BIGINT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    actor_principal VARCHAR(256) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_automation_admission_request_history_identity UNIQUE (
        tenant_id, game_instance_id, region_id, mode, control_plane_request_id
    )
);

CREATE INDEX idx_automation_admission_request_history_scope
    ON automation_admission_request_history
       (tenant_id, game_instance_id, region_id, created_at);
