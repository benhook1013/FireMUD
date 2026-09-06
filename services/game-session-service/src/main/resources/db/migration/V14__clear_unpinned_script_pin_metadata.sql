UPDATE game_instances
SET script_patch_pinned_at = NULL,
    script_patch_pinned_by = NULL,
    script_patch_pinned_reason = NULL
WHERE NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
  AND script_pin_epoch IS NULL
  AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL;

ALTER TABLE game_instances
    ADD CONSTRAINT game_instances_unpinned_script_pin_metadata_coherent CHECK (
        (
            NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
            AND script_pin_epoch IS NOT NULL
            AND script_pin_epoch > 0
            AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
        OR
        (
            NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
            AND script_patch_pinned_at IS NULL
            AND script_patch_pinned_by IS NULL
            AND script_patch_pinned_reason IS NULL
        )
    ) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;
