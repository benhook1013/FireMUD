-- Pin one normalized Game Design template association to the original StartSession owner attempt.
-- This immutable evidence does not grant authority, select a descriptor, or activate a runtime.
-- Request storage is the 256 KiB canonical tuple plus 1 KiB closed-request overhead; response
-- storage has a local 4 MiB fail-closed budget and makes no claim about gRPC transport capacity.
-- [jooq ignore start]
CREATE UNIQUE INDEX uq_gs_start_session_operator_attempt_pin_binding
    ON game_session_start_session_operator_attempt
        (target_namespace, control_plane_request_id, owner_attempt_id, owner_fence, canonical_tenant_id);

CREATE TABLE game_session_start_session_template_association_pin (
    target_namespace VARCHAR(63) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    owner_attempt_id UUID NOT NULL,
    owner_fence BIGINT NOT NULL,
    post_authorization_execution_tuple BYTEA NOT NULL,
    post_authorization_tuple_digest VARCHAR(71) NOT NULL,
    account_redemption_projection_digest VARCHAR(71) NOT NULL,
    association_request_wire BYTEA NOT NULL,
    association_response_wire BYTEA NOT NULL,
    association_request_digest VARCHAR(71) NOT NULL,
    association_response_digest VARCHAR(71) NOT NULL,
    template_id BIGINT NOT NULL,
    canonical_version_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    publish_workflow_id VARCHAR(256) NOT NULL,
    publication_selection_digest VARCHAR(71) NOT NULL,
    association_digest VARCHAR(71) NOT NULL,
    world_intake_request_id UUID NOT NULL,
    world_operation_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_gs_start_session_template_association_pin
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_start_session_template_association_attempt UNIQUE (owner_attempt_id),
    CONSTRAINT fk_gs_start_session_template_association_attempt
        FOREIGN KEY (
            target_namespace,
            control_plane_request_id,
            owner_attempt_id,
            owner_fence,
            canonical_tenant_id)
        REFERENCES game_session_start_session_operator_attempt
            (target_namespace, control_plane_request_id, owner_attempt_id, owner_fence, canonical_tenant_id),
    CONSTRAINT chk_gs_start_session_template_association_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND octet_length(control_plane_request_id) BETWEEN 1 AND 128
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND control_plane_request_id !~ '[[:cntrl:]]'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_fence > 0
        AND template_id > 0
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND selected_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND btrim(publish_workflow_id) <> ''
        AND octet_length(publish_workflow_id) <= 256
        AND world_intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND world_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_gs_start_session_template_association_evidence CHECK (
        octet_length(post_authorization_execution_tuple) BETWEEN 1 AND 262144
        AND octet_length(association_request_wire) BETWEEN 1 AND 263168
        AND octet_length(association_response_wire) BETWEEN 1 AND 4194304
        AND post_authorization_tuple_digest ~ '^sha256:[0-9a-f]{64}$'
        AND account_redemption_projection_digest ~ '^sha256:[0-9a-f]{64}$'
        AND association_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND association_response_digest ~ '^sha256:[0-9a-f]{64}$'
        AND publication_selection_digest ~ '^sha256:[0-9a-f]{64}$'
        AND association_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE FUNCTION reject_gs_start_session_template_association_pin_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'StartSession template association pins are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER gs_start_session_template_association_pin_immutable
BEFORE UPDATE OR DELETE ON game_session_start_session_template_association_pin
FOR EACH ROW EXECUTE FUNCTION reject_gs_start_session_template_association_pin_mutation();

CREATE TRIGGER gs_start_session_template_association_pin_no_truncate
BEFORE TRUNCATE ON game_session_start_session_template_association_pin
FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_start_session_template_association_pin_mutation();
-- [jooq ignore stop]
