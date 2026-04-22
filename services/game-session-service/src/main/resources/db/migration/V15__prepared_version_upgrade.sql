CREATE TABLE prepared_version_upgrade (
    id BIGSERIAL PRIMARY KEY,
    preparation_id VARCHAR(64) NOT NULL UNIQUE,
    control_plane_request_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    source_game_instance_id BIGINT NOT NULL,
    source_version_id BIGINT NOT NULL,
    target_version_id BIGINT NOT NULL,
    target_launch_descriptor_id VARCHAR(64) NOT NULL,
    remap_set_id VARCHAR(64),
    result VARCHAR(32) NOT NULL,
    reasons_json TEXT NOT NULL,
    checked_participants_json TEXT NOT NULL,
    participant_results_json TEXT NOT NULL,
    checked_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_prepared_version_upgrade_source_instance
    ON prepared_version_upgrade(tenant_id, source_game_instance_id, target_version_id);

CREATE UNIQUE INDEX uq_prepared_version_upgrade_request
    ON prepared_version_upgrade(tenant_id, control_plane_request_id);

ALTER TABLE gameplay_admission_pointer_event
    ADD COLUMN prepared_version_upgrade_id VARCHAR(64);
