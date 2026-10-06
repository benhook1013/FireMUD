-- The immutable row is the smallest retained anti-reassignment claim. The V26
-- source-capture timestamp is authoritative for expiry; linked evidence hashes and
-- signed approval metadata live only in the expiring payload.
CREATE TABLE account_approved_legacy_tenant_associations (
    legacy_tenant_id BIGINT PRIMARY KEY CHECK (legacy_tenant_id > 0),
    canonical_tenant_id UUID NOT NULL UNIQUE,
    source_legacy_game_tenant_id VARCHAR(36) NOT NULL UNIQUE,
    source_game_row_id BIGINT NOT NULL UNIQUE CHECK (source_game_row_id > 0),
    operation_id UUID NOT NULL UNIQUE,
    target_namespace VARCHAR(128) NOT NULL,
    source_captured_at TIMESTAMPTZ NOT NULL,
    terminal_outcome VARCHAR(32) NOT NULL DEFAULT 'ASSOCIATED',
    CONSTRAINT account_approved_tenant_uuid_non_nil
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_approved_tenant_operation_non_nil
        CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_approved_tenant_namespace_shape
        CHECK (char_length(target_namespace) BETWEEN 1 AND 128
            AND target_namespace !~ '[[:cntrl:]]'
            AND target_namespace !~ '^[[:space:]]*$'),
    CONSTRAINT account_approved_tenant_source_key_shape
        CHECK (char_length(source_legacy_game_tenant_id) BETWEEN 1 AND 36
            AND source_legacy_game_tenant_id !~ '^[[:space:]]*$'),
    CONSTRAINT account_approved_tenant_terminal_outcome
        CHECK (terminal_outcome = 'ASSOCIATED')
);

CREATE TABLE account_approved_legacy_tenant_association_payload (
    operation_id UUID PRIMARY KEY
        REFERENCES account_approved_legacy_tenant_associations(operation_id) ON DELETE RESTRICT,
    account_evidence_digest VARCHAR(71) NOT NULL,
    manifest_digest VARCHAR(71) NOT NULL,
    signer_key_id VARCHAR(128) NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approval_reference VARCHAR(512) NOT NULL,
    signed_at VARCHAR(64) NOT NULL,
    manifest_signature VARCHAR(88) NOT NULL,
    operation_entry_count INTEGER NOT NULL CHECK (operation_entry_count > 0),
    manifest_schema_version INTEGER NOT NULL CHECK (manifest_schema_version = 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_approved_tenant_payload_signature
        CHECK (manifest_signature ~ '^[A-Za-z0-9+/]{86}==$'),
    CONSTRAINT account_approved_tenant_payload_digests
        CHECK (account_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT account_approved_tenant_payload_labels
        CHECK (char_length(signer_key_id) BETWEEN 1 AND 128
            AND char_length(approved_by) BETWEEN 1 AND 256
            AND char_length(approval_reference) BETWEEN 1 AND 512
            AND char_length(signed_at) BETWEEN 1 AND 64
            AND signer_key_id !~ '[[:cntrl:]]'
            AND approved_by !~ '[[:cntrl:]]'
            AND approval_reference !~ '[[:cntrl:]]'
            AND signer_key_id !~ '^[[:space:]]*$'
            AND approved_by !~ '^[[:space:]]*$'
            AND approval_reference !~ '^[[:space:]]*$')
);

CREATE INDEX account_approved_tenant_payload_expiry
    ON account_approved_legacy_tenant_associations(source_captured_at, operation_id);

-- [jooq ignore start]
CREATE FUNCTION reject_account_approved_tenant_claim_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Approved Account tenant identity claim is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION validate_account_approved_tenant_claim_capture() RETURNS trigger AS $$
BEGIN
    IF NEW.source_captured_at > clock_timestamp()
        OR NEW.source_captured_at <= clock_timestamp() - INTERVAL '30 days' THEN
        RAISE EXCEPTION 'Approved Account tenant source capture is future-dated or expired'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_approved_tenant_claim_capture_window
    BEFORE INSERT ON account_approved_legacy_tenant_associations
    FOR EACH ROW EXECUTE FUNCTION validate_account_approved_tenant_claim_capture();

CREATE TRIGGER account_approved_tenant_claim_immutable
    BEFORE UPDATE OR DELETE ON account_approved_legacy_tenant_associations
    FOR EACH ROW EXECUTE FUNCTION reject_account_approved_tenant_claim_mutation();

CREATE TRIGGER account_approved_tenant_claim_no_truncate
    BEFORE TRUNCATE ON account_approved_legacy_tenant_associations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_account_approved_tenant_claim_mutation();

CREATE FUNCTION guard_account_approved_tenant_payload() RETURNS trigger AS $$
DECLARE
    captured TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT source_captured_at INTO captured
        FROM account_approved_legacy_tenant_associations
        WHERE operation_id = NEW.operation_id;
        IF captured IS NULL OR captured <= clock_timestamp() - INTERVAL '30 days' THEN
            RAISE EXCEPTION 'Approved Account tenant payload is unavailable or past capture-based expiry'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Approved Account tenant payload is immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT source_captured_at INTO captured
    FROM account_approved_legacy_tenant_associations
    WHERE operation_id = OLD.operation_id;
    IF captured IS NOT NULL AND captured > clock_timestamp() - INTERVAL '30 days' THEN
        RAISE EXCEPTION 'Approved Account tenant payload has not reached capture-based expiry'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_approved_tenant_payload_immutable_expiry
    BEFORE INSERT OR UPDATE OR DELETE ON account_approved_legacy_tenant_association_payload
    FOR EACH ROW EXECUTE FUNCTION guard_account_approved_tenant_payload();

CREATE FUNCTION reject_account_approved_tenant_payload_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Approved Account tenant payload cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_approved_tenant_payload_no_truncate
    BEFORE TRUNCATE ON account_approved_legacy_tenant_association_payload
    FOR EACH STATEMENT EXECUTE FUNCTION reject_account_approved_tenant_payload_truncate();
-- [jooq ignore stop]
