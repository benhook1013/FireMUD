CREATE TABLE game_design_game_session_tenant_association_operations (
    operation_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    signer_key_id VARCHAR(128) NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approval_reference VARCHAR(512) NOT NULL,
    signed_at TEXT NOT NULL,
    legacy_game_session_tenant_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    source_game_row_id BIGINT NOT NULL REFERENCES game(id),
    source_game_tenant_key VARCHAR(36) NOT NULL,
    provenance_kind VARCHAR(32) NOT NULL,
    game_session_evidence_digest VARCHAR(71) NOT NULL,
    manifest_digest VARCHAR(71) NOT NULL,
    signature VARCHAR(88) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_gd_game_session_association_canonical
        UNIQUE (target_namespace, canonical_tenant_id),
    CONSTRAINT uq_gd_game_session_association_retained_key
        UNIQUE (target_namespace, legacy_game_session_tenant_id),
    CONSTRAINT chk_gd_game_session_association_schema CHECK (schema_version = 1),
    CONSTRAINT chk_gd_game_session_association_operation CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_game_session_association_namespace CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    ),
    CONSTRAINT chk_gd_game_session_association_approval_labels CHECK (
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
    CONSTRAINT chk_gd_game_session_association_signed_at CHECK (
        char_length(signed_at) BETWEEN 1 AND 64
    ),
    CONSTRAINT chk_gd_game_session_association_retained_key CHECK (
        legacy_game_session_tenant_id > 0
    ),
    CONSTRAINT chk_gd_game_session_association_canonical CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_game_session_association_source_row CHECK (source_game_row_id > 0),
    CONSTRAINT chk_gd_game_session_association_source_key CHECK (
        char_length(source_game_tenant_key) BETWEEN 1 AND 36
        AND source_game_tenant_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gd_game_session_association_provenance CHECK (
        provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
    ),
    CONSTRAINT chk_gd_game_session_association_digests CHECK (
        game_session_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND manifest_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_game_session_association_signature CHECK (
        signature ~ '^[A-Za-z0-9+/]{86}==$'
    )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_gd_game_session_tenant_association() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Game Session tenant association evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM game g
        WHERE g.id = NEW.source_game_row_id
          AND g.tenant_id = NEW.source_game_tenant_key
          AND g.canonical_tenant_id = NEW.canonical_tenant_id
          AND g.tenant_identity_provenance_kind = NEW.provenance_kind
          AND g.tenant_identity_source_game_id = g.id
          AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    ) INTO source_matches;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Game Session tenant association source does not match its Game Design row'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_association_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_game_session_tenant_association_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_gd_game_session_tenant_association();

CREATE FUNCTION reject_gd_game_session_tenant_association_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Session tenant association evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_association_no_truncate
    BEFORE TRUNCATE ON game_design_game_session_tenant_association_operations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gd_game_session_tenant_association_truncate();
-- [jooq ignore stop]
