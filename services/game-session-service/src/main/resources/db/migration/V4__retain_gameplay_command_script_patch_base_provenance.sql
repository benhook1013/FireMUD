-- Retain the exact script-patch base admitted with a gameplay command.
-- This migration is additive for databases that already applied V1 through V3.
ALTER TABLE gameplay_command
    ADD COLUMN script_patch_base_version_id bigint;

ALTER TABLE gameplay_command
    ADD CONSTRAINT ck_gameplay_command_script_patch_base_version_positive CHECK (
        script_patch_base_version_id IS NULL
        OR (
            script_patch_base_version_id > 0
            AND script_patch_version IS NOT NULL
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );
