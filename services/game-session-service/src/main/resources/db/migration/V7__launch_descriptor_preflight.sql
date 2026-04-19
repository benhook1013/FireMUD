ALTER TABLE game_instances
    ADD COLUMN game_template_id BIGINT,
    ADD COLUMN launch_descriptor_id VARCHAR(64),
    ADD COLUMN version_id BIGINT,
    ADD COLUMN release_bundle_id BIGINT,
    ADD COLUMN version_state_epoch BIGINT,
    ADD COLUMN generation_config_revision VARCHAR(128);

CREATE INDEX idx_game_instances_launch_descriptor_id
    ON game_instances(launch_descriptor_id);
