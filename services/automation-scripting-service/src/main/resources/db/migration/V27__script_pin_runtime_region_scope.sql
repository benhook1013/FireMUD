ALTER TABLE script_patch_pin_projections
    ADD COLUMN runtime_region_id VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN runtime_region_epoch BIGINT NOT NULL DEFAULT 0;
