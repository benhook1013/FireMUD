ALTER TABLE game_instances
    ADD COLUMN script_pin_epoch bigint;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_script_pin_tuple_coherent
    CHECK (
        (
            NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
        OR
        (
            NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
            AND script_pin_epoch IS NOT NULL
            AND script_pin_epoch > 0
            AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );
