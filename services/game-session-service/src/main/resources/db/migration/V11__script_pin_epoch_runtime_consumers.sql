CREATE TABLE script_pin_operation (
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    control_plane_request_id character varying(128) NOT NULL,
    operation_kind character varying(32) NOT NULL,
    target_script_patch_version character varying(100) NOT NULL,
    expected_pin_kind character varying(32) NOT NULL,
    expected_script_pin_epoch bigint,
    actor_principal character varying(200) NOT NULL,
    reason character varying(500) NOT NULL,
    mutation_digest character varying(64) NOT NULL,
    outcome character varying(32) NOT NULL,
    error_code character varying(128),
    previous_script_patch_version character varying(100),
    previous_script_pin_epoch bigint,
    resulting_script_patch_version character varying(100),
    resulting_script_pin_epoch bigint,
    committed_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id),
    CHECK (
        (previous_script_patch_version IS NULL AND previous_script_pin_epoch IS NULL)
        OR
        (previous_script_patch_version IS NOT NULL AND previous_script_pin_epoch IS NOT NULL AND previous_script_pin_epoch > 0)
    ),
    CHECK (
        (resulting_script_patch_version IS NULL AND resulting_script_pin_epoch IS NULL)
        OR
        (resulting_script_patch_version IS NOT NULL AND resulting_script_pin_epoch IS NOT NULL AND resulting_script_pin_epoch > 0)
    ),
    CHECK (expected_pin_kind <> 'EXPECT_EPOCH' OR expected_script_pin_epoch IS NOT NULL)
);

CREATE INDEX idx_script_pin_operation_instance
    ON script_pin_operation (tenant_id, game_instance_id, committed_at, control_plane_request_id);
