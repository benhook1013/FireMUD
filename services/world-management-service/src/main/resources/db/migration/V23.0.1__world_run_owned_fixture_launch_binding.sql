ALTER TABLE world_instance
    ADD COLUMN run_owned_fixture_binding_sha256 CHAR(64),
    ADD COLUMN run_owned_fixture_preparing_lifecycle_epoch BIGINT,
    ADD CONSTRAINT ck_world_instance_run_owned_fixture_binding
        CHECK (
            (run_owned_fixture_binding_sha256 IS NULL
                AND run_owned_fixture_preparing_lifecycle_epoch IS NULL)
            OR (run_owned_fixture_binding_sha256 IS NOT NULL
                AND run_owned_fixture_binding_sha256 ~ '^[0-9a-f]{64}$'
                AND run_owned_fixture_preparing_lifecycle_epoch IS NOT NULL
                AND run_owned_fixture_preparing_lifecycle_epoch > 0)
        );

CREATE UNIQUE INDEX uk_world_instance_run_owned_fixture_operation
    ON world_instance (tenant_id, control_plane_request_id)
    WHERE run_owned_fixture_binding_sha256 IS NOT NULL;
