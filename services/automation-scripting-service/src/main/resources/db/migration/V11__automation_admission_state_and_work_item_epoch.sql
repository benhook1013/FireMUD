CREATE TABLE automation_admission_states (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    mode VARCHAR(64) NOT NULL DEFAULT 'NORMAL',
    admission_epoch BIGINT NOT NULL DEFAULT 1,
    control_plane_request_id VARCHAR(128),
    actor_principal VARCHAR(128),
    reason VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_automation_admission_scope UNIQUE (tenant_id, game_instance_id, region_id)
);

ALTER TABLE script_work_items
    ADD COLUMN admission_epoch BIGINT NOT NULL DEFAULT 1;

CREATE INDEX idx_automation_admission_scope
    ON automation_admission_states(tenant_id, game_instance_id, region_id);

CREATE INDEX idx_script_work_items_scope_epoch_status
    ON script_work_items(tenant_id, game_instance_id, region_id, admission_epoch, status);
