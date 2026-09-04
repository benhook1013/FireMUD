ALTER TABLE game_instances
    ADD COLUMN script_pin_epoch bigint;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_script_pin_tuple_coherent
    CHECK (
        (
            NULLIF(BTRIM(script_patch_version), '') IS NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(BTRIM(script_patch_pinned_control_plane_request_id), '') IS NULL
        )
        OR
        (
            NULLIF(BTRIM(script_patch_version), '') IS NOT NULL
            AND script_pin_epoch IS NOT NULL
            AND script_pin_epoch > 0
            AND NULLIF(BTRIM(script_patch_pinned_control_plane_request_id), '') IS NOT NULL
        )
    );
