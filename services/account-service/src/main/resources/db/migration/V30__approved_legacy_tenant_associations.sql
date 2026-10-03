-- No retained numeric tenant key is mapped by this migration. An Account-owned
-- import verifies exact V26 source evidence against a Game Design approved read.
CREATE TABLE account_approved_legacy_tenant_associations (
    legacy_tenant_id BIGINT PRIMARY KEY CHECK (legacy_tenant_id > 0),
    canonical_tenant_id UUID NOT NULL UNIQUE,
    source_legacy_game_tenant_id VARCHAR(36) NOT NULL UNIQUE,
    source_game_row_id BIGINT NOT NULL UNIQUE CHECK (source_game_row_id > 0),
    account_evidence_digest VARCHAR(71) NOT NULL,
    operation_id UUID NOT NULL,
    manifest_digest VARCHAR(71) NOT NULL,
    manifest_signature VARCHAR(128) NOT NULL,
    target_namespace VARCHAR(128) NOT NULL,
    signer_key_id VARCHAR(128) NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approval_reference VARCHAR(512) NOT NULL,
    signed_at VARCHAR(40) NOT NULL,
    operation_entry_count INTEGER NOT NULL CHECK (operation_entry_count > 0),
    manifest_schema_version INTEGER NOT NULL CHECK (manifest_schema_version = 1),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_approved_tenant_evidence_digest_shape
        CHECK (account_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT account_approved_tenant_manifest_digest_shape
        CHECK (manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT account_approved_tenant_signature_shape
        CHECK (length(manifest_signature) = 88)
);

-- [jooq ignore start]
CREATE FUNCTION reject_account_approved_tenant_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Approved Account legacy tenant association is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_approved_tenant_association_immutable
    BEFORE UPDATE OR DELETE ON account_approved_legacy_tenant_associations
    FOR EACH ROW EXECUTE FUNCTION reject_account_approved_tenant_change();
-- [jooq ignore stop]
