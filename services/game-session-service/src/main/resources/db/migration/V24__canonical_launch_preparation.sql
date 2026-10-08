-- Evidence-only canonical launch preparation. It creates no runtime instance or admission state.
CREATE TABLE game_session_canonical_launch_preparation (
    target_namespace character varying(63) NOT NULL,
    control_plane_request_id character varying(128) NOT NULL,
    operation_id uuid NOT NULL,
    acting_account_uuid uuid NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    realm_id uuid NOT NULL,
    catalog_creation_request_id uuid NOT NULL,
    catalog_revision bigint NOT NULL,
    source_intake_operation_id uuid NOT NULL,
    source_intake_request_id uuid NOT NULL,
    source_registration_request_id uuid NOT NULL,
    source_operation_id uuid NOT NULL,
    catalog_request_digest character varying(71) NOT NULL,
    catalog_receipt_digest character varying(71) NOT NULL,
    source_intake_request_digest character varying(71) NOT NULL,
    source_intake_receipt_digest character varying(71) NOT NULL,
    source_evidence_digest character varying(71) NOT NULL,
    descriptor_request_digest character varying(71) NOT NULL,
    descriptor_result_digest character varying(71) NOT NULL,
    request_digest character varying(71) NOT NULL,
    receipt_digest character varying(71) NOT NULL,
    request_evidence_json jsonb NOT NULL,
    catalog_evidence_json jsonb NOT NULL,
    source_intake_evidence_json jsonb NOT NULL,
    launch_descriptor_evidence_json jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_launch_preparation
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_canonical_launch_preparation_operation UNIQUE (operation_id),
    CONSTRAINT fk_gs_canonical_launch_preparation_catalog
        FOREIGN KEY (target_namespace, realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id),
    CONSTRAINT fk_gs_canonical_launch_preparation_source_intake
        FOREIGN KEY (source_intake_operation_id)
        REFERENCES game_session_authored_world_source_intake (operation_id),
    CONSTRAINT chk_gs_canonical_launch_preparation_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND octet_length(control_plane_request_id) BETWEEN 1 AND 128
        AND btrim(control_plane_request_id) <> ''
        AND operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND acting_account_uuid <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND catalog_creation_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND catalog_revision > 0
        AND source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_registration_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT chk_gs_canonical_launch_preparation_digests CHECK (
        catalog_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND catalog_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest ~ '^sha256:[0-9a-f]{64}$'
        AND request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gs_canonical_launch_preparation_json CHECK (
        jsonb_typeof(request_evidence_json) = 'object'
        AND jsonb_typeof(catalog_evidence_json) = 'object'
        AND jsonb_typeof(source_intake_evidence_json) = 'object'
        AND jsonb_typeof(launch_descriptor_evidence_json) = 'object')
);

-- [jooq ignore start]
CREATE FUNCTION reject_game_session_canonical_launch_preparation_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Canonical launch preparation evidence is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'gs_canonical_launch_preparation_immutable';
END;
$$;

CREATE TRIGGER game_session_canonical_launch_preparation_immutable
BEFORE UPDATE OR DELETE ON game_session_canonical_launch_preparation
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_launch_preparation_mutation();

CREATE TRIGGER game_session_canonical_launch_preparation_no_truncate
BEFORE TRUNCATE ON game_session_canonical_launch_preparation
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_launch_preparation_mutation();
-- [jooq ignore stop]
