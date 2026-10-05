-- Fresh Game Design identities are UUID-native; source row IDs and tenant keys remain provenance.
CREATE TABLE account_fresh_tenant_identity_associations (
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    target_namespace VARCHAR(128) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    creation_request_id UUID NOT NULL
        CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    operation_id UUID NOT NULL UNIQUE
        CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_digest VARCHAR(71) NOT NULL
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    canonical_tenant_id UUID NOT NULL UNIQUE
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    source_game_row_id BIGINT NOT NULL CHECK (source_game_row_id > 0),
    source_game_tenant_key VARCHAR(36) NOT NULL
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    provenance_kind VARCHAR(32) NOT NULL CHECK (provenance_kind = 'NEW_GAME_ROW'),
    evidence_digest VARCHAR(71) NOT NULL
        CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    identity_kind VARCHAR(32) NOT NULL DEFAULT 'FRESH_GAME_DESIGN'
        CHECK (identity_kind = 'FRESH_GAME_DESIGN'),
    associated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_fresh_tenant_request_unique
        UNIQUE (target_namespace, creation_request_id),
    CONSTRAINT account_fresh_tenant_source_unique
        UNIQUE (source_game_row_id, source_game_tenant_key, provenance_kind),
    CONSTRAINT account_fresh_tenant_identity_kind_unique
        UNIQUE (canonical_tenant_id, identity_kind)
);

-- This immutable claim serializes retained and fresh tenant UUID identities. Existing V30
-- retained rows already contain the anti-reassignment tuple; their expiring payload digests
-- are deliberately not required or reconstructed by this identity-only foundation.
ALTER TABLE account_approved_legacy_tenant_associations
    ADD COLUMN identity_kind VARCHAR(32) NOT NULL DEFAULT 'APPROVED_RETAINED'
        CHECK (identity_kind = 'APPROVED_RETAINED'),
    ADD CONSTRAINT account_approved_tenant_identity_kind_unique
        UNIQUE (canonical_tenant_id, identity_kind);

CREATE TABLE account_canonical_tenant_identity_claims (
    canonical_tenant_id UUID PRIMARY KEY
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    identity_kind VARCHAR(32) NOT NULL
        CHECK (identity_kind IN ('APPROVED_RETAINED', 'FRESH_GAME_DESIGN')),
    source_operation_id UUID NOT NULL,
    source_account_legacy_tenant_id BIGINT,
    source_target_namespace VARCHAR(128) NOT NULL,
    source_creation_request_id UUID,
    source_request_digest VARCHAR(71),
    source_game_row_id BIGINT NOT NULL CHECK (source_game_row_id > 0),
    source_game_tenant_key VARCHAR(36) NOT NULL
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    source_provenance_kind VARCHAR(32),
    source_evidence_digest VARCHAR(71),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_canonical_tenant_claim_pair_unique
        UNIQUE (canonical_tenant_id, identity_kind),
    CONSTRAINT account_canonical_tenant_claim_source_shape CHECK (
        (identity_kind = 'APPROVED_RETAINED'
            AND source_account_legacy_tenant_id IS NOT NULL
            AND source_account_legacy_tenant_id > 0
            AND source_creation_request_id IS NULL
            AND source_request_digest IS NULL
            AND source_provenance_kind IS NULL
            AND source_evidence_digest IS NULL)
        OR
        (identity_kind = 'FRESH_GAME_DESIGN'
            AND source_account_legacy_tenant_id IS NULL
            AND source_creation_request_id IS NOT NULL
            AND source_creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND source_request_digest IS NOT NULL
            AND source_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_provenance_kind = 'NEW_GAME_ROW'
            AND source_evidence_digest IS NOT NULL
            AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$')
    ),
    CONSTRAINT account_canonical_tenant_claim_operation_non_nil
        CHECK (source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID)
);

-- [jooq ignore start]
CREATE FUNCTION reject_account_canonical_tenant_identity_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Account canonical tenant identity evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION validate_account_canonical_tenant_identity_claim() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Account canonical tenant identity claims are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.identity_kind = 'APPROVED_RETAINED' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_approved_legacy_tenant_associations retained
            WHERE retained.canonical_tenant_id = NEW.canonical_tenant_id
              AND retained.identity_kind = NEW.identity_kind
              AND retained.operation_id = NEW.source_operation_id
              AND retained.legacy_tenant_id = NEW.source_account_legacy_tenant_id
              AND retained.target_namespace = NEW.source_target_namespace
              AND retained.source_game_row_id = NEW.source_game_row_id
              AND retained.source_legacy_game_tenant_id = NEW.source_game_tenant_key
        ) INTO source_matches;
    ELSIF NEW.identity_kind = 'FRESH_GAME_DESIGN' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_fresh_tenant_identity_associations fresh
            WHERE fresh.canonical_tenant_id = NEW.canonical_tenant_id
              AND fresh.identity_kind = NEW.identity_kind
              AND fresh.target_namespace = NEW.source_target_namespace
              AND fresh.creation_request_id = NEW.source_creation_request_id
              AND fresh.operation_id = NEW.source_operation_id
              AND fresh.request_digest = NEW.source_request_digest
              AND fresh.source_game_row_id = NEW.source_game_row_id
              AND fresh.source_game_tenant_key = NEW.source_game_tenant_key
              AND fresh.provenance_kind = NEW.source_provenance_kind
              AND fresh.evidence_digest = NEW.source_evidence_digest
        ) INTO source_matches;
    END IF;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Account canonical tenant claim has no exact immutable source'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION claim_approved_retained_tenant_identity() RETURNS trigger AS $$
BEGIN
    INSERT INTO account_canonical_tenant_identity_claims (
        canonical_tenant_id, identity_kind, source_operation_id,
        source_account_legacy_tenant_id, source_target_namespace,
        source_creation_request_id, source_request_digest, source_game_row_id,
        source_game_tenant_key, source_provenance_kind, source_evidence_digest
    ) VALUES (
        NEW.canonical_tenant_id, NEW.identity_kind, NEW.operation_id,
        NEW.legacy_tenant_id, NEW.target_namespace, NULL, NULL,
        NEW.source_game_row_id, NEW.source_legacy_game_tenant_id, NULL, NULL
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION claim_fresh_tenant_identity() RETURNS trigger AS $$
BEGIN
    INSERT INTO account_canonical_tenant_identity_claims (
        canonical_tenant_id, identity_kind, source_operation_id,
        source_account_legacy_tenant_id, source_target_namespace,
        source_creation_request_id, source_request_digest, source_game_row_id,
        source_game_tenant_key, source_provenance_kind, source_evidence_digest
    ) VALUES (
        NEW.canonical_tenant_id, NEW.identity_kind, NEW.operation_id,
        NULL, NEW.target_namespace, NEW.creation_request_id, NEW.request_digest,
        NEW.source_game_row_id, NEW.source_game_tenant_key, NEW.provenance_kind,
        NEW.evidence_digest
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_canonical_tenant_claim_validate
    BEFORE INSERT OR UPDATE OR DELETE ON account_canonical_tenant_identity_claims
    FOR EACH ROW EXECUTE FUNCTION validate_account_canonical_tenant_identity_claim();
CREATE TRIGGER account_canonical_tenant_claim_no_truncate
    BEFORE TRUNCATE ON account_canonical_tenant_identity_claims
    FOR EACH STATEMENT EXECUTE FUNCTION reject_account_canonical_tenant_identity_mutation();

-- V30 associations remain immutable and are copied only as exact stored identity tuples.
INSERT INTO account_canonical_tenant_identity_claims (
    canonical_tenant_id, identity_kind, source_operation_id,
    source_account_legacy_tenant_id, source_target_namespace,
    source_creation_request_id, source_request_digest, source_game_row_id,
    source_game_tenant_key, source_provenance_kind, source_evidence_digest
)
SELECT canonical_tenant_id, identity_kind, operation_id, legacy_tenant_id,
       target_namespace, NULL, NULL, source_game_row_id,
       source_legacy_game_tenant_id, NULL, NULL
FROM account_approved_legacy_tenant_associations;

CREATE TRIGGER account_approved_tenant_identity_claim_after_insert
    AFTER INSERT ON account_approved_legacy_tenant_associations
    FOR EACH ROW EXECUTE FUNCTION claim_approved_retained_tenant_identity();
CREATE TRIGGER account_fresh_tenant_identity_claim_after_insert
    AFTER INSERT ON account_fresh_tenant_identity_associations
    FOR EACH ROW EXECUTE FUNCTION claim_fresh_tenant_identity();
CREATE TRIGGER account_fresh_tenant_identity_immutable
    BEFORE UPDATE OR DELETE ON account_fresh_tenant_identity_associations
    FOR EACH ROW EXECUTE FUNCTION reject_account_canonical_tenant_identity_mutation();
CREATE TRIGGER account_fresh_tenant_identity_no_truncate
    BEFORE TRUNCATE ON account_fresh_tenant_identity_associations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_account_canonical_tenant_identity_mutation();
CREATE TRIGGER account_approved_tenant_identity_no_truncate
    BEFORE TRUNCATE ON account_approved_legacy_tenant_associations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_account_canonical_tenant_identity_mutation();
-- [jooq ignore stop]

ALTER TABLE account_approved_legacy_tenant_associations
    ADD CONSTRAINT account_approved_tenant_identity_claim_fk
        FOREIGN KEY (canonical_tenant_id, identity_kind)
        REFERENCES account_canonical_tenant_identity_claims (canonical_tenant_id, identity_kind)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE account_fresh_tenant_identity_associations
    ADD CONSTRAINT account_fresh_tenant_identity_claim_fk
        FOREIGN KEY (canonical_tenant_id, identity_kind)
        REFERENCES account_canonical_tenant_identity_claims (canonical_tenant_id, identity_kind)
        DEFERRABLE INITIALLY DEFERRED;
