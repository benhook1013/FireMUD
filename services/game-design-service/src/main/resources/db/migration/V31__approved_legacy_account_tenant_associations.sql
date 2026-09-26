-- A Game Design owner-approved manifest is the only source of a retained Account
-- numeric tenant key to canonical Game Design tenant UUID association. No row is
-- inferred from matching numeric/text keys or inserted by this migration.
CREATE TABLE legacy_account_tenant_association_operations (
    operation_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    manifest_digest VARCHAR(71) NOT NULL UNIQUE,
    signature VARCHAR(128) NOT NULL,
    target_namespace VARCHAR(128) NOT NULL,
    signer_key_id VARCHAR(128) NOT NULL,
    approved_by VARCHAR(256) NOT NULL,
    approval_reference VARCHAR(512) NOT NULL,
    signed_at VARCHAR(40) NOT NULL,
    entry_count INTEGER NOT NULL CHECK (entry_count > 0),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT legacy_tenant_manifest_digest_shape
        CHECK (manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT legacy_tenant_signature_shape
        CHECK (length(signature) = 88)
);

CREATE TABLE legacy_account_tenant_associations (
    legacy_account_tenant_id BIGINT PRIMARY KEY CHECK (legacy_account_tenant_id > 0),
    legacy_game_tenant_id VARCHAR(36) NOT NULL UNIQUE,
    canonical_tenant_id UUID NOT NULL UNIQUE,
    source_game_row_id BIGINT NOT NULL UNIQUE REFERENCES game(id),
    account_evidence_digest VARCHAR(71) NOT NULL,
    operation_id UUID NOT NULL REFERENCES legacy_account_tenant_association_operations(operation_id),
    CONSTRAINT legacy_tenant_account_evidence_digest_shape
        CHECK (account_evidence_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE FUNCTION reject_legacy_tenant_association_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Approved legacy tenant association evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER legacy_tenant_association_operation_immutable
    BEFORE UPDATE OR DELETE ON legacy_account_tenant_association_operations
    FOR EACH ROW EXECUTE FUNCTION reject_legacy_tenant_association_change();

CREATE TRIGGER legacy_tenant_association_immutable
    BEFORE UPDATE OR DELETE ON legacy_account_tenant_associations
    FOR EACH ROW EXECUTE FUNCTION reject_legacy_tenant_association_change();
-- [jooq ignore stop]
