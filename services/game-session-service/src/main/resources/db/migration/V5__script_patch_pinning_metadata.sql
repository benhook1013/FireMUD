ALTER TABLE game_instances
    ADD COLUMN IF NOT EXISTS script_patch_pinned_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS script_patch_pinned_by VARCHAR(200) NULL,
    ADD COLUMN IF NOT EXISTS script_patch_pinned_reason VARCHAR(500) NULL;
