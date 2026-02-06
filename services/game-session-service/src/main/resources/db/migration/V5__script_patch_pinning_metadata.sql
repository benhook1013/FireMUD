ALTER TABLE game_instances
    ADD COLUMN script_patch_pinned_at TIMESTAMP NULL,
    ADD COLUMN script_patch_pinned_by VARCHAR(200) NULL,
    ADD COLUMN script_patch_pinned_reason VARCHAR(500) NULL;

