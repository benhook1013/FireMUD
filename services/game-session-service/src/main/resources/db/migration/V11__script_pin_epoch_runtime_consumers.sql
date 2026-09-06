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
    committed_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id),
    CONSTRAINT ck_script_pin_operation_kind CHECK (
        operation_kind IN ('SET', 'ROLLBACK', 'REPIN')
    ),
    CONSTRAINT ck_script_pin_operation_required_text_nonblank CHECK (
        BTRIM(control_plane_request_id) <> ''
        AND BTRIM(target_script_patch_version) <> ''
        AND BTRIM(actor_principal) <> ''
        AND BTRIM(reason) <> ''
        AND BTRIM(mutation_digest) <> ''
    ),
    CONSTRAINT ck_script_pin_operation_previous_tuple CHECK (
        (previous_script_patch_version IS NULL AND previous_script_pin_epoch IS NULL)
        OR
        (previous_script_patch_version IS NOT NULL AND previous_script_pin_epoch IS NOT NULL AND previous_script_pin_epoch > 0)
    ),
    CONSTRAINT ck_script_pin_operation_resulting_tuple CHECK (
        (resulting_script_patch_version IS NULL AND resulting_script_pin_epoch IS NULL)
        OR
        (resulting_script_patch_version IS NOT NULL AND resulting_script_pin_epoch IS NOT NULL AND resulting_script_pin_epoch > 0)
    ),
    CONSTRAINT ck_script_pin_operation_expected_pin CHECK (
        (expected_pin_kind = 'EXPECT_UNPINNED' AND expected_script_pin_epoch IS NULL)
        OR
        (expected_pin_kind = 'EXPECT_EPOCH' AND expected_script_pin_epoch IS NOT NULL AND expected_script_pin_epoch > 0)
        OR
        (expected_pin_kind = 'UNCONDITIONAL' AND expected_script_pin_epoch IS NULL)
    ),
    CONSTRAINT ck_script_pin_operation_outcome_error CHECK (
        (outcome = 'COMMITTED' AND error_code IS NULL)
        OR
        (outcome = 'FAILED' AND error_code IS NOT NULL AND BTRIM(error_code) <> '')
    )
);

CREATE INDEX idx_script_pin_operation_instance
    ON script_pin_operation (tenant_id, game_instance_id, committed_at, control_plane_request_id);
