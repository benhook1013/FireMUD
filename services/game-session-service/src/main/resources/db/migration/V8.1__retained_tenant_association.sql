CREATE TABLE game_session_retained_tenant_association (
    operation_id UUID PRIMARY KEY,
    approval_schema_version INTEGER NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    association_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    approval_operation_id UUID NOT NULL,
    signer_key_id VARCHAR(128) NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approval_reference VARCHAR(512) NOT NULL,
    signed_at TEXT NOT NULL,
    legacy_game_session_tenant_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    provenance_kind VARCHAR(32) NOT NULL,
    game_session_evidence_digest VARCHAR(71) NOT NULL,
    approval_manifest_digest VARCHAR(71) NOT NULL,
    approval_signature VARCHAR(88) NOT NULL,
    snapshot_canonical_json TEXT NOT NULL,
    snapshot_evidence_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_gs_retained_tenant_association_request
        UNIQUE (target_namespace, association_request_id),
    CONSTRAINT uq_gs_retained_tenant_association_canonical
        UNIQUE (target_namespace, canonical_tenant_id),
    CONSTRAINT uq_gs_retained_tenant_association_legacy_key
        UNIQUE (target_namespace, legacy_game_session_tenant_id),
    CONSTRAINT chk_gs_retained_tenant_association_schema
        CHECK (approval_schema_version = 1),
    CONSTRAINT chk_gs_retained_tenant_association_operation_ids CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND association_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND approval_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gs_retained_tenant_association_namespace CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    ),
    CONSTRAINT chk_gs_retained_tenant_association_approval_labels CHECK (
        char_length(signer_key_id) BETWEEN 1 AND 128
        AND char_length(approved_by) BETWEEN 1 AND 256
        AND char_length(approval_reference) BETWEEN 1 AND 512
        AND octet_length(signer_key_id) <= 128
        AND octet_length(approved_by) <= 256
        AND octet_length(approval_reference) <= 512
        AND signer_key_id !~ '[[:cntrl:]]'
        AND approved_by !~ '[[:cntrl:]]'
        AND approval_reference !~ '[[:cntrl:]]'
        AND signer_key_id !~ '^[[:space:]]*$'
        AND approved_by !~ '^[[:space:]]*$'
        AND approval_reference !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_retained_tenant_association_signed_at CHECK (
        char_length(signed_at) BETWEEN 1 AND 64
    ),
    CONSTRAINT chk_gs_retained_tenant_association_retained_key CHECK (
        legacy_game_session_tenant_id > 0
    ),
    CONSTRAINT chk_gs_retained_tenant_association_canonical_tenant CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gs_retained_tenant_association_source_row CHECK (source_game_row_id > 0),
    CONSTRAINT chk_gs_retained_tenant_association_source_key CHECK (
        char_length(source_game_tenant_key) BETWEEN 1 AND 36
        AND source_game_tenant_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_retained_tenant_association_provenance CHECK (
        provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30')
    ),
    CONSTRAINT chk_gs_retained_tenant_association_digests CHECK (
        request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND game_session_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND approval_manifest_digest ~ '^sha256:[0-9a-f]{64}$'
        AND snapshot_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gs_retained_tenant_association_signature CHECK (
        approval_signature ~ '^[A-Za-z0-9+/]{86}==$'
    ),
    CONSTRAINT chk_gs_retained_tenant_association_snapshot CHECK (
        octet_length(snapshot_canonical_json) > 0
    )
);

-- [jooq ignore start]
CREATE FUNCTION reject_game_session_retained_tenant_association_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session retained tenant association evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER game_session_retained_tenant_association_immutable
BEFORE UPDATE OR DELETE ON game_session_retained_tenant_association
FOR EACH ROW EXECUTE FUNCTION reject_game_session_retained_tenant_association_mutation();

CREATE TRIGGER game_session_retained_tenant_association_no_truncate
BEFORE TRUNCATE ON game_session_retained_tenant_association
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_retained_tenant_association_mutation();
-- [jooq ignore stop]
