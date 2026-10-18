-- Retain Game Design's exact public authored-core result against its original launch and
-- attempt. The row is source evidence only; it does not authorize allocation or runtime use.
CREATE TABLE gs_original_start_session_authored_actor_core_retention (
    target_namespace VARCHAR(63) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    game_instance_uuid UUID NOT NULL,
    owner_attempt_id UUID NOT NULL,
    owner_mutation_id UUID NOT NULL,
    owner_fence BIGINT NOT NULL,
    owner_lease_expires_at TIMESTAMPTZ NOT NULL,
    original_post_authorization_tuple BYTEA NOT NULL,
    account_redemption_projection BYTEA NOT NULL,
    account_redemption_projection_digest VARCHAR(64) NOT NULL,
    canonical_version_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    publish_workflow_id VARCHAR(256) NOT NULL,
    association_digest VARCHAR(71) NOT NULL,
    world_source_operation_id UUID NOT NULL,
    world_source_evidence_digest VARCHAR(71) NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    launch_descriptor_id VARCHAR(128) NOT NULL,
    descriptor_request_digest VARCHAR(71) NOT NULL,
    descriptor_result_digest VARCHAR(71) NOT NULL,
    release_attestation_evidence_digest VARCHAR(71) NOT NULL,
    source_read_request_bytes BYTEA NOT NULL,
    canonical_source_result_bytes BYTEA NOT NULL,
    source_result_digest VARCHAR(71) NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_gs_original_start_session_actor_core_retention
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_original_start_session_actor_core_instance
        UNIQUE (target_namespace, game_instance_uuid),
    CONSTRAINT uq_gs_original_start_session_actor_core_attempt
        UNIQUE (owner_attempt_id),
    CONSTRAINT fk_gs_original_start_session_actor_core_launch
        FOREIGN KEY (target_namespace, control_plane_request_id)
        REFERENCES game_session_canonical_instance_launch
            (target_namespace, control_plane_request_id) ON DELETE RESTRICT,
    CONSTRAINT ck_gs_original_start_session_actor_core_identity CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_fence > 0
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND selected_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND publish_workflow_id !~ '^[[:space:]]*$'
        AND world_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND launch_descriptor_id !~ '^[[:space:]]*$'),
    CONSTRAINT ck_gs_original_start_session_actor_core_content CHECK (
        octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144
        AND octet_length(account_redemption_projection) BETWEEN 1 AND 262144
        AND account_redemption_projection_digest ~ '^[0-9a-f]{64}$'
        AND account_redemption_projection_digest = encode(sha256(account_redemption_projection), 'hex')
        AND association_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest ~ '^sha256:[0-9a-f]{64}$'
        AND release_attestation_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(source_read_request_bytes) BETWEEN 1 AND 524288
        AND octet_length(canonical_source_result_bytes) BETWEEN 1 AND 8388608
        AND source_result_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_result_digest = 'sha256:' || encode(sha256(canonical_source_result_bytes), 'hex'))
);

-- V35's original-attempt table is intentionally hidden from jOOQ's DDL simulator. Keep the
-- durable relational binding in PostgreSQL while excluding only this cross-relation constraint.
-- [jooq ignore start]
ALTER TABLE gs_original_start_session_authored_actor_core_retention
    ADD CONSTRAINT fk_gs_original_start_session_actor_core_attempt
        FOREIGN KEY (target_namespace, control_plane_request_id)
        REFERENCES game_session_start_session_operator_attempt
            (target_namespace, control_plane_request_id) ON DELETE RESTRICT;
-- [jooq ignore stop]

-- New rows must join the exact complete launch and still-live original pending attempt. The
-- authenticated source result is independently validated in Game Session before it reaches SQL.
-- [jooq ignore start]
CREATE FUNCTION guard_gs_original_start_session_actor_core_retention()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE launch game_session_canonical_instance_launch%ROWTYPE;
    attempt game_session_start_session_operator_attempt%ROWTYPE;
    source_result JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game Session authored-core source retention is immutable and append-only'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO STRICT launch
      FROM game_session_canonical_instance_launch
     WHERE target_namespace = NEW.target_namespace
       AND control_plane_request_id = NEW.control_plane_request_id;
    SELECT * INTO STRICT attempt
      FROM game_session_start_session_operator_attempt
     WHERE target_namespace = NEW.target_namespace
       AND control_plane_request_id = NEW.control_plane_request_id;

    source_result := convert_from(NEW.canonical_source_result_bytes, 'UTF8')::JSONB;
    IF launch.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR launch.game_instance_uuid IS DISTINCT FROM NEW.game_instance_uuid
        OR launch.original_start_session_tuple IS DISTINCT FROM
            NEW.original_post_authorization_tuple
        OR launch.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
        OR launch.world_slug IS DISTINCT FROM NEW.world_slug
        OR launch.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'
            IS DISTINCT FROM NEW.descriptor_request_digest
        OR launch.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'
            IS DISTINCT FROM NEW.descriptor_result_digest
        OR launch.complete_launch_binding_evidence -> 'descriptor' ->> 'authoredWorldSourceOperationId'
            IS DISTINCT FROM NEW.world_source_operation_id::TEXT
        OR launch.complete_launch_binding_evidence -> 'descriptor' ->> 'authoredWorldSourceEvidenceDigest'
            IS DISTINCT FROM NEW.world_source_evidence_digest
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest'
            IS DISTINCT FROM NEW.release_attestation_evidence_digest
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
            IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'commitId'
            IS DISTINCT FROM NEW.selected_commit_id::TEXT
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'publishWorkflowId'
            IS DISTINCT FROM NEW.publish_workflow_id
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'authoredWorldSourceOperationId'
            IS DISTINCT FROM NEW.world_source_operation_id::TEXT
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'authoredWorldSourceEvidenceDigest'
            IS DISTINCT FROM NEW.world_source_evidence_digest
        OR launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'worldSlug'
            IS DISTINCT FROM NEW.world_slug
        OR attempt.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR attempt.owner_attempt_id IS DISTINCT FROM NEW.owner_attempt_id
        OR attempt.owner_mutation_id IS DISTINCT FROM NEW.owner_mutation_id
        OR attempt.owner_fence IS DISTINCT FROM NEW.owner_fence
        OR attempt.post_authorization_execution_tuple IS DISTINCT FROM
            NEW.original_post_authorization_tuple
        OR attempt.account_redemption_projection IS DISTINCT FROM
            NEW.account_redemption_projection
        OR attempt.phase_state IS DISTINCT FROM 'OWNER_EXECUTION_PENDING'
        OR attempt.lease_expires_at IS DISTINCT FROM NEW.owner_lease_expires_at
        OR attempt.lease_expires_at <= clock_timestamp()
        OR jsonb_typeof(source_result) IS DISTINCT FROM 'object'
        OR source_result ->> 'schema' IS DISTINCT FROM
            'game-design-authored-initial-actor-core-read/v1'
        OR (SELECT count(*) FROM jsonb_object_keys(source_result)) <> 5
        OR jsonb_typeof(source_result -> 'request') IS DISTINCT FROM 'string'
        OR decode(source_result ->> 'request', 'base64') IS DISTINCT FROM
            NEW.source_read_request_bytes
        OR jsonb_typeof(source_result -> 'associationRead') IS DISTINCT FROM 'string'
        OR jsonb_typeof(source_result -> 'launchDescriptor') IS DISTINCT FROM 'string'
        OR jsonb_typeof(source_result -> 'projections') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Authored-core retention differs from the exact live launch, attempt, or closed result'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_gs_original_start_session_actor_core_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_original_start_session_actor_core_retention_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON gs_original_start_session_authored_actor_core_retention
    FOR EACH ROW EXECUTE FUNCTION guard_gs_original_start_session_actor_core_retention();

CREATE FUNCTION reject_gs_original_start_session_actor_core_retention_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session authored-core source retention cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER gs_original_start_session_actor_core_retention_no_truncate
    BEFORE TRUNCATE ON gs_original_start_session_authored_actor_core_retention
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_original_start_session_actor_core_retention_truncate();
-- [jooq ignore stop]
