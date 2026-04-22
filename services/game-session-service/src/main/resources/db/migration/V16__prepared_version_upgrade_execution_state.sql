ALTER TABLE prepared_version_upgrade
    ADD COLUMN executed_target_game_instance_id BIGINT,
    ADD COLUMN executed_pointer_version BIGINT,
    ADD COLUMN executed_at TIMESTAMP,
    ADD COLUMN execution_control_plane_request_id VARCHAR(128);
