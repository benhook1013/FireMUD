ALTER TABLE version
    ADD COLUMN version_number INT NOT NULL DEFAULT 1,
    ADD COLUMN base_version_id BIGINT REFERENCES version(id),
    ADD COLUMN script_patch_version VARCHAR(100),
    ADD COLUMN is_script_only BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notes VARCHAR(255);

ALTER TABLE version
    DROP COLUMN IF EXISTS revision_ids;
