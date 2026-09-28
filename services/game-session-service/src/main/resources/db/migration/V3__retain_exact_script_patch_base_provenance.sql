ALTER TABLE game_instances
    ADD COLUMN script_patch_base_version_id bigint;

ALTER TABLE game_instances
    ADD CONSTRAINT ck_game_instances_script_patch_base_version_positive CHECK (
        script_patch_base_version_id IS NULL
        OR (
            script_patch_base_version_id > 0
            AND script_patch_version IS NOT NULL
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );

ALTER TABLE script_pin_operation
    ADD COLUMN validated_base_version_id bigint,
    ADD COLUMN previous_script_patch_base_version_id bigint,
    ADD COLUMN resulting_script_patch_base_version_id bigint;

ALTER TABLE script_pin_operation
    ADD CONSTRAINT ck_script_pin_operation_base_versions_positive CHECK (
        (validated_base_version_id IS NULL OR validated_base_version_id > 0)
        AND (previous_script_patch_base_version_id IS NULL OR previous_script_patch_base_version_id > 0)
        AND (resulting_script_patch_base_version_id IS NULL OR resulting_script_patch_base_version_id > 0)
        AND (previous_script_patch_base_version_id IS NULL OR previous_script_patch_version IS NOT NULL)
        AND (resulting_script_patch_base_version_id IS NULL OR resulting_script_patch_version IS NOT NULL)
    );

ALTER TABLE remote_command_coordinator
    ADD COLUMN script_patch_base_version_id bigint;

ALTER TABLE remote_command_coordinator
    ADD CONSTRAINT ck_remote_command_coordinator_script_patch_base_version_positive CHECK (
        script_patch_base_version_id IS NULL
        OR (
            script_patch_base_version_id > 0
            AND script_patch_version IS NOT NULL
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );

ALTER TABLE remote_followup
    ADD COLUMN script_patch_base_version_id bigint;

ALTER TABLE remote_followup
    ADD CONSTRAINT ck_remote_followup_script_patch_base_version_positive CHECK (
        script_patch_base_version_id IS NULL
        OR (
            script_patch_base_version_id > 0
            AND script_patch_version IS NOT NULL
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );

ALTER TABLE remote_followup_result
    ADD COLUMN script_patch_base_version_id bigint;

ALTER TABLE remote_followup_result
    ADD CONSTRAINT ck_remote_followup_result_script_patch_base_version_positive CHECK (
        script_patch_base_version_id IS NULL
        OR (
            script_patch_base_version_id > 0
            AND script_patch_version IS NOT NULL
            AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL
        )
    );
