ALTER TABLE gameplay_command
    ADD COLUMN script_pin_epoch bigint,
    ADD COLUMN script_pin_control_plane_request_id character varying(128);

-- Existing nonterminal command rows predate durable owner evidence. Do not
-- invent a pin epoch or control-plane request id for patch-only observations.
-- Clear that untrustworthy execution evidence while retaining patch-only
-- observations on completed rows as non-executable history.
UPDATE gameplay_command
SET script_patch_version = NULL
WHERE upper(btrim(source_type)) = 'AUTOMATION'
  AND completed_at IS NULL
  AND NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL
  AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL;

-- Nonterminal player commands have no durable owner evidence. Clear legacy
-- patch-only observations rather than manufacturing an epoch or request id.
UPDATE gameplay_command
SET script_patch_version = NULL
WHERE upper(btrim(source_type)) = 'PLAYER'
  AND completed_at IS NULL
  AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL;

ALTER TABLE gameplay_command
    ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent
    CHECK (
        (
            completed_at IS NOT NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
        OR
        (
            upper(btrim(source_type)) = 'PLAYER'
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
        OR
        (
            upper(btrim(source_type)) = 'AUTOMATION'
            AND NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL
            AND (
                (
                    NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NULL
                    AND script_pin_epoch IS NULL
                    AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
                )
                OR
                (
                    NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
                    AND script_pin_epoch IS NOT NULL
                    AND script_pin_epoch > 0
                    AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NOT NULL
                )
            )
        )
        OR
        (
            upper(btrim(source_type)) <> 'PLAYER'
            AND NOT (
                upper(btrim(source_type)) = 'AUTOMATION'
                AND NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL
            )
            AND script_pin_epoch IS NULL
            AND NULLIF(regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g'), '') IS NULL
        )
    ) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;
