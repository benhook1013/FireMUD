ALTER TABLE game_instances
    ADD COLUMN script_pin_epoch bigint;

-- Existing partial legacy tuples do not provide enough owner evidence to retain.
UPDATE game_instances
SET script_patch_version = NULL,
    script_pin_epoch = NULL,
    script_patch_pinned_control_plane_request_id = NULL
WHERE (
          NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
          AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
      )
   OR (
          NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
          AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
      );

-- Retain coherent legacy pins and initialize their newly-added epoch.
UPDATE game_instances
SET script_pin_epoch = 1
WHERE NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
  AND NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
  AND script_pin_epoch IS NULL;

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
    ) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;
