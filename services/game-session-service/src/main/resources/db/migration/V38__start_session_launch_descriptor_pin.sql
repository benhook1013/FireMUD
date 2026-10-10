-- Pin one exact-replay Game Design descriptor outcome to the already retained StartSession
-- association. This immutable evidence grants no World hold, runtime admission, or activation.
-- Request storage is the bounded canonical tuple plus 1 KiB closed-request overhead. Response
-- storage has a local 8 MiB fail-closed budget and makes no claim about gRPC transport capacity.
-- [jooq ignore start]
CREATE UNIQUE INDEX uq_gs_start_session_template_association_pin_binding
    ON game_session_start_session_template_association_pin
        (target_namespace, control_plane_request_id, owner_attempt_id, owner_fence, canonical_tenant_id);

CREATE TABLE game_session_start_session_launch_descriptor_pin (
    target_namespace VARCHAR(63) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    owner_attempt_id UUID NOT NULL,
    owner_fence BIGINT NOT NULL,
    association_request_digest VARCHAR(71) NOT NULL,
    association_response_digest VARCHAR(71) NOT NULL,
    descriptor_request_wire BYTEA NOT NULL,
    descriptor_response_wire BYTEA NOT NULL,
    descriptor_request_digest VARCHAR(71) NOT NULL,
    descriptor_response_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_gs_start_session_launch_descriptor_pin
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_start_session_launch_descriptor_attempt UNIQUE (owner_attempt_id),
    CONSTRAINT fk_gs_start_session_launch_descriptor_association
        FOREIGN KEY (
            target_namespace,
            control_plane_request_id,
            owner_attempt_id,
            owner_fence,
            canonical_tenant_id)
        REFERENCES game_session_start_session_template_association_pin
            (target_namespace, control_plane_request_id, owner_attempt_id, owner_fence, canonical_tenant_id),
    CONSTRAINT chk_gs_start_session_launch_descriptor_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND octet_length(control_plane_request_id) BETWEEN 1 AND 128
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND control_plane_request_id !~ '[[:cntrl:]]'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_fence > 0),
    CONSTRAINT chk_gs_start_session_launch_descriptor_evidence CHECK (
        octet_length(descriptor_request_wire) BETWEEN 1 AND 263168
        AND octet_length(descriptor_response_wire) BETWEEN 1 AND 8388608
        AND association_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND association_response_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_response_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE FUNCTION reject_gs_start_session_launch_descriptor_pin_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'StartSession launch descriptor pins are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER gs_start_session_launch_descriptor_pin_immutable
BEFORE UPDATE OR DELETE ON game_session_start_session_launch_descriptor_pin
FOR EACH ROW EXECUTE FUNCTION reject_gs_start_session_launch_descriptor_pin_mutation();

CREATE TRIGGER gs_start_session_launch_descriptor_pin_no_truncate
BEFORE TRUNCATE ON game_session_start_session_launch_descriptor_pin
FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_start_session_launch_descriptor_pin_mutation();
-- [jooq ignore stop]
