ALTER TABLE gameplay_command
    ADD COLUMN admitted_release_bundle_id BIGINT,
    ADD COLUMN admitted_version_id BIGINT,
    ADD COLUMN declared_effects_json TEXT;
