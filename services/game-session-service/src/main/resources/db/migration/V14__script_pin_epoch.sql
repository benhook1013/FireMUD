ALTER TABLE game_instances
    ADD COLUMN script_pin_epoch bigint;

UPDATE game_instances
SET script_patch_version = CASE
        WHEN NULLIF(BTRIM(script_patch_version), '') IS NOT NULL
            AND NULLIF(BTRIM(script_patch_pinned_control_plane_request_id), '') IS NOT NULL
          THEN script_patch_version
        ELSE NULL
    END,
    script_pin_epoch = CASE
        WHEN NULLIF(BTRIM(script_patch_version), '') IS NOT NULL
            AND NULLIF(BTRIM(script_patch_pinned_control_plane_request_id), '') IS NOT NULL
          THEN 1
        ELSE NULL
    END,
    script_patch_pinned_control_plane_request_id = CASE
        WHEN NULLIF(BTRIM(script_patch_version), '') IS NOT NULL
            AND NULLIF(BTRIM(script_patch_pinned_control_plane_request_id), '') IS NOT NULL
          THEN script_patch_pinned_control_plane_request_id
        ELSE NULL
    END;

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
