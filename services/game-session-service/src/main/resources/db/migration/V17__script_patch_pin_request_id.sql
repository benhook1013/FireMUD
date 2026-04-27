ALTER TABLE game_instances
    ADD COLUMN IF NOT EXISTS script_patch_pinned_control_plane_request_id VARCHAR(128) NULL;
