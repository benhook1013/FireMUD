ALTER TABLE game_design_game_session_tenant_association_operations
    DROP CONSTRAINT chk_gd_game_session_association_schema;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP CONSTRAINT chk_gd_game_session_association_approval_labels;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP CONSTRAINT chk_gd_game_session_association_signed_at;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP CONSTRAINT chk_gd_game_session_association_digests;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP CONSTRAINT chk_gd_game_session_association_signature;

ALTER TABLE game_design_game_session_tenant_association_operations
    ADD COLUMN captured_at TIMESTAMPTZ;
ALTER TABLE game_design_game_session_tenant_association_operations
    ADD COLUMN terminal_outcome VARCHAR(32) NOT NULL DEFAULT 'ASSOCIATED';

CREATE TABLE game_design_game_session_tenant_association_payload (
    operation_id UUID PRIMARY KEY
        REFERENCES game_design_game_session_tenant_association_operations(operation_id)
        ON DELETE RESTRICT,
    schema_version INTEGER NOT NULL,
    signer_key_id VARCHAR(128) NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approval_reference VARCHAR(512) NOT NULL,
    signed_at TEXT NOT NULL,
    game_session_projection_digest VARCHAR(71),
    game_session_evidence_digest VARCHAR(71) NOT NULL,
    manifest_digest VARCHAR(71) NOT NULL,
    signature VARCHAR(88) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_gd_game_session_association_payload_schema CHECK (schema_version IN (1, 2)),
    CONSTRAINT chk_gd_game_session_association_payload_capture CHECK (
        (schema_version = 1 AND game_session_projection_digest IS NULL)
        OR (schema_version = 2
            AND game_session_projection_digest IS NOT NULL
            AND game_session_projection_digest ~ '^sha256:[0-9a-f]{64}$')
    ),
    CONSTRAINT chk_gd_game_session_association_payload_labels CHECK (
        char_length(signer_key_id) BETWEEN 1 AND 128
        AND char_length(approved_by) BETWEEN 1 AND 256
        AND char_length(approval_reference) BETWEEN 1 AND 512
        AND char_length(signed_at) BETWEEN 1 AND 64
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
    CONSTRAINT chk_gd_game_session_association_payload_digests CHECK (
        game_session_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND manifest_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_game_session_association_payload_signature CHECK (
        signature ~ '^[A-Za-z0-9+/]{86}==$'
    )
);

INSERT INTO game_design_game_session_tenant_association_payload (
    operation_id,
    schema_version,
    signer_key_id,
    approved_by,
    approval_reference,
    signed_at,
    game_session_evidence_digest,
    manifest_digest,
    signature
)
SELECT
    operation_id,
    schema_version,
    signer_key_id,
    approved_by,
    approval_reference,
    signed_at,
    game_session_evidence_digest,
    manifest_digest,
    signature
FROM game_design_game_session_tenant_association_operations;

ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN schema_version;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN signer_key_id;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN approved_by;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN approval_reference;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN signed_at;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN game_session_evidence_digest;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN manifest_digest;
ALTER TABLE game_design_game_session_tenant_association_operations
    DROP COLUMN signature;

ALTER TABLE game_design_game_session_tenant_association_operations
    ADD CONSTRAINT chk_gd_game_session_association_capture_time CHECK (
        captured_at IS NULL OR captured_at <= created_at
    );
ALTER TABLE game_design_game_session_tenant_association_operations
    ADD CONSTRAINT chk_gd_game_session_association_terminal_outcome CHECK (
        terminal_outcome IN ('ASSOCIATED')
    );

CREATE TABLE game_design_game_session_tenant_association_legal_hold (
    hold_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL
        REFERENCES game_design_game_session_tenant_association_operations(operation_id)
        ON DELETE RESTRICT,
    hold_scope VARCHAR(32) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    case_reference VARCHAR(256) NOT NULL,
    authorization_id UUID NOT NULL,
    authorized_principal VARCHAR(256) NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL,
    review_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    released_by VARCHAR(256),
    release_reference VARCHAR(256),
    CONSTRAINT chk_gd_game_session_tenant_hold_scope CHECK (hold_scope = 'RAW_PAYLOAD'),
    CONSTRAINT chk_gd_game_session_tenant_hold_reason CHECK (
        reason_code IN ('LITIGATION', 'REGULATORY', 'SECURITY_CASE')
    ),
    CONSTRAINT chk_gd_game_session_tenant_hold_authorization CHECK (
        hold_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND authorization_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND char_length(case_reference) BETWEEN 1 AND 256
        AND char_length(authorized_principal) BETWEEN 1 AND 256
        AND review_at > authorized_at
    ),
    CONSTRAINT chk_gd_game_session_tenant_hold_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reference IS NULL)
        OR (released_at IS NOT NULL
            AND released_by IS NOT NULL
            AND release_reference IS NOT NULL
            AND char_length(released_by) BETWEEN 1 AND 256
            AND char_length(release_reference) BETWEEN 1 AND 256)
    )
);

CREATE UNIQUE INDEX uq_gd_game_session_association_active_hold
    ON game_design_game_session_tenant_association_legal_hold (operation_id)
    WHERE released_at IS NULL;

CREATE INDEX ix_gd_game_session_association_payload_expiry
    ON game_design_game_session_tenant_association_operations (captured_at, operation_id);

-- [jooq ignore start]
CREATE FUNCTION reject_unverified_gd_game_session_tenant_hold() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design retained-tenant legal holds require an authenticated owner authorization boundary'
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_hold_authentication_required
    BEFORE INSERT ON game_design_game_session_tenant_association_legal_hold
    FOR EACH ROW EXECUTE FUNCTION reject_unverified_gd_game_session_tenant_hold();

CREATE FUNCTION guard_gd_game_session_tenant_payload_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design retained tenant approval payload is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_payload_immutable
    BEFORE UPDATE ON game_design_game_session_tenant_association_payload
    FOR EACH ROW EXECUTE FUNCTION guard_gd_game_session_tenant_payload_update();

CREATE FUNCTION guard_gd_game_session_tenant_payload_delete() RETURNS trigger AS $$
BEGIN
    IF OLD.operation_id IN (
        SELECT operation_id
        FROM game_design_game_session_tenant_association_legal_hold
        WHERE released_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Game Design tenant approval payload is protected by an active legal hold'
            USING ERRCODE = 'check_violation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM game_design_game_session_tenant_association_operations association
        WHERE association.operation_id = OLD.operation_id
          AND association.captured_at IS NOT NULL
          AND association.captured_at > clock_timestamp() - INTERVAL '30 days'
    ) THEN
        RAISE EXCEPTION 'Game Design tenant approval payload has not reached its capture-based expiry'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_payload_expiry_guard
    BEFORE DELETE ON game_design_game_session_tenant_association_payload
    FOR EACH ROW EXECUTE FUNCTION guard_gd_game_session_tenant_payload_delete();

CREATE FUNCTION guard_gd_game_session_tenant_hold_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design tenant legal hold release requires an authenticated owner authorization boundary'
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_hold_release_only
    BEFORE UPDATE ON game_design_game_session_tenant_association_legal_hold
    FOR EACH ROW EXECUTE FUNCTION guard_gd_game_session_tenant_hold_update();

CREATE FUNCTION guard_gd_game_session_tenant_hold_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design tenant legal hold audit records require a finite policy-controlled expiry'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_game_session_tenant_hold_audit_no_delete
    BEFORE DELETE ON game_design_game_session_tenant_association_legal_hold
    FOR EACH ROW EXECUTE FUNCTION guard_gd_game_session_tenant_hold_delete();
-- [jooq ignore stop]
