-- Retain the one immutable initial billing-owner consent and the current owner tuple.
-- This persistence does not authenticate the evidence, create a subscription, or grant access.
CREATE TABLE account_initial_tenant_billing_owner_operations (
    request_id UUID PRIMARY KEY
        CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    tenant_uuid UUID NOT NULL UNIQUE
        REFERENCES account_canonical_tenant_identity_claims (canonical_tenant_id) ON DELETE RESTRICT,
    initiating_account_uuid UUID NOT NULL
        REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    initial_owner_account_uuid UUID NOT NULL
        REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    initial_owner_version BIGINT NOT NULL CHECK (initial_owner_version = 1),
    initial_owner_generation UUID NOT NULL UNIQUE
        CHECK (initial_owner_generation <> '00000000-0000-0000-0000-000000000000'::UUID),
    target_namespace VARCHAR(63) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    creation_request_id UUID NOT NULL
        CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    creation_operation_id UUID NOT NULL UNIQUE
        CHECK (creation_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    creation_request_digest VARCHAR(71) NOT NULL
        CHECK (creation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_game_row_id BIGINT NOT NULL CHECK (source_game_row_id > 0),
    source_game_tenant_key VARCHAR(36) NOT NULL
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    provenance_kind VARCHAR(32) NOT NULL CHECK (provenance_kind = 'NEW_GAME_ROW'),
    creation_evidence_digest VARCHAR(71) NOT NULL
        CHECK (creation_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    account_authorization_operation_id UUID NOT NULL
        CHECK (account_authorization_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_authorization_digest VARCHAR(71) NOT NULL
        CHECK (account_authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    creator_evidence_digest VARCHAR(71) NOT NULL
        CHECK (creator_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    creator_evidence_payload BYTEA NOT NULL
        CHECK (octet_length(creator_evidence_payload) BETWEEN 1 AND 1048576),
    creator_evidence_payload_digest VARCHAR(71) NOT NULL
        CHECK (creator_evidence_payload_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (creator_evidence_payload_digest = 'sha256:' || encode(sha256(creator_evidence_payload), 'hex')),
    consent_operation_id UUID NOT NULL UNIQUE
        CHECK (consent_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    consent_statement_version INTEGER NOT NULL CHECK (consent_statement_version > 0),
    consent_affirmative BOOLEAN NOT NULL CHECK (consent_affirmative),
    consent_payload BYTEA NOT NULL
        CHECK (octet_length(consent_payload) BETWEEN 1 AND 1048576),
    consent_digest VARCHAR(71) NOT NULL
        CHECK (consent_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (consent_digest = 'sha256:' || encode(sha256(consent_payload), 'hex')),
    request_payload BYTEA NOT NULL
        CHECK (octet_length(request_payload) BETWEEN 1 AND 2097152),
    request_digest VARCHAR(71) NOT NULL
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    result_payload BYTEA NOT NULL
        CHECK (octet_length(result_payload) BETWEEN 1 AND 2097152),
    result_digest VARCHAR(71) NOT NULL
        CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (result_digest = 'sha256:' || encode(sha256(result_payload), 'hex')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_initial_billing_owner_initiator_check
        CHECK (initial_owner_account_uuid = initiating_account_uuid),
    CONSTRAINT account_initial_billing_owner_creation_request_unique
        UNIQUE (target_namespace, creation_request_id),
    CONSTRAINT account_initial_billing_owner_request_tenant_unique
        UNIQUE (request_id, tenant_uuid)
);

-- This tuple may advance only through a later audited transfer owner. Initial consent remains in
-- the immutable operation above and is never rewritten by a transfer.
CREATE TABLE account_tenant_billing_owners (
    tenant_uuid UUID PRIMARY KEY,
    owner_account_uuid UUID NOT NULL REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    owner_version BIGINT NOT NULL CHECK (owner_version > 0),
    owner_generation UUID NOT NULL UNIQUE
        CHECK (owner_generation <> '00000000-0000-0000-0000-000000000000'::UUID),
    initial_request_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_tenant_billing_owner_initial_operation_fk
        FOREIGN KEY (initial_request_id, tenant_uuid)
        REFERENCES account_initial_tenant_billing_owner_operations (request_id, tenant_uuid)
        ON DELETE RESTRICT
);

-- The immutable operation must not commit without its one matching current-owner row. Deferral
-- permits the repository's operation-then-owner insert order while coupling both at transaction
-- completion. The referenced key deliberately excludes mutable owner/version/generation values.
ALTER TABLE account_tenant_billing_owners
    ADD CONSTRAINT account_tenant_billing_owner_initial_pair_unique
        UNIQUE (initial_request_id, tenant_uuid);
ALTER TABLE account_initial_tenant_billing_owner_operations
    ADD CONSTRAINT account_initial_billing_owner_current_owner_fk
        FOREIGN KEY (request_id, tenant_uuid)
        REFERENCES account_tenant_billing_owners (initial_request_id, tenant_uuid)
        DEFERRABLE INITIALLY DEFERRED;

-- [jooq ignore start]
CREATE FUNCTION account_initial_billing_owner_operation_source_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fresh account_fresh_tenant_identity_associations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT fresh FROM account_fresh_tenant_identity_associations
        WHERE canonical_tenant_id = NEW.tenant_uuid FOR KEY SHARE;
    IF NEW.initiating_account_uuid IS DISTINCT FROM NEW.initial_owner_account_uuid
        OR NEW.target_namespace IS DISTINCT FROM fresh.target_namespace
        OR NEW.creation_request_id IS DISTINCT FROM fresh.creation_request_id
        OR NEW.creation_operation_id IS DISTINCT FROM fresh.operation_id
        OR NEW.creation_request_digest IS DISTINCT FROM fresh.request_digest
        OR NEW.source_game_row_id IS DISTINCT FROM fresh.source_game_row_id
        OR NEW.source_game_tenant_key IS DISTINCT FROM fresh.source_game_tenant_key
        OR NEW.provenance_kind IS DISTINCT FROM fresh.provenance_kind
        OR NEW.creation_evidence_digest IS DISTINCT FROM fresh.evidence_digest THEN
        RAISE EXCEPTION 'Initial billing-owner operation differs from its fresh tenant source'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_initial_billing_owner_operation_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Initial billing-owner operation evidence is append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_tenant_billing_owner_initial_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    initial account_initial_tenant_billing_owner_operations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT initial FROM account_initial_tenant_billing_owner_operations
        WHERE request_id = NEW.initial_request_id AND tenant_uuid = NEW.tenant_uuid FOR KEY SHARE;
    IF NEW.owner_account_uuid IS DISTINCT FROM initial.initial_owner_account_uuid
        OR NEW.owner_version IS DISTINCT FROM initial.initial_owner_version
        OR NEW.owner_generation IS DISTINCT FROM initial.initial_owner_generation THEN
        RAISE EXCEPTION 'Initial current billing owner differs from its immutable operation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_initial_billing_owner_source_guard
    BEFORE INSERT ON account_initial_tenant_billing_owner_operations
    FOR EACH ROW EXECUTE FUNCTION account_initial_billing_owner_operation_source_guard();
CREATE TRIGGER account_initial_billing_owner_immutable
    BEFORE UPDATE OR DELETE ON account_initial_tenant_billing_owner_operations
    FOR EACH ROW EXECUTE FUNCTION account_initial_billing_owner_operation_no_mutation();
CREATE TRIGGER account_initial_billing_owner_no_truncate
    BEFORE TRUNCATE ON account_initial_tenant_billing_owner_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_initial_billing_owner_operation_no_mutation();
CREATE TRIGGER account_tenant_billing_owner_initial_insert_guard
    BEFORE INSERT ON account_tenant_billing_owners
    FOR EACH ROW EXECUTE FUNCTION account_tenant_billing_owner_initial_insert_guard();
-- [jooq ignore end]
