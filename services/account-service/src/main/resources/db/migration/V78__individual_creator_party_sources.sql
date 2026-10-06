-- Owner-local preparation only: no party provisioning, authenticated caller producer,
-- hosted terms, source revocation writer or runtime activation is supplied here.
CREATE TABLE account_individual_creator_party_sources (
    creator_party_id UUID PRIMARY KEY CHECK (creator_party_id <> '00000000-0000-0000-0000-000000000000'),
    account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    verification_status VARCHAR(16) NOT NULL
        CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED', 'UNSUPPORTED')),
    identity_version BIGINT NOT NULL CHECK (identity_version > 0),
    policy_reference VARCHAR(512),
    policy_version BIGINT,
    verification_evidence_reference VARCHAR(512),
    verification_evidence_version BIGINT,
    source_version BIGINT NOT NULL CHECK (source_version = 1),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) > 0),
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (policy_reference IS NULL OR length(btrim(policy_reference)) > 0),
    CHECK (verification_evidence_reference IS NULL OR length(btrim(verification_evidence_reference)) > 0),
    CHECK ((policy_reference IS NULL) = (policy_version IS NULL)),
    CHECK ((verification_evidence_reference IS NULL) = (verification_evidence_version IS NULL)),
    CHECK (policy_version IS NULL OR policy_version > 0),
    CHECK (verification_evidence_version IS NULL OR verification_evidence_version > 0),
    CHECK (verification_status <> 'VERIFIED' OR
        (policy_reference IS NOT NULL AND verification_evidence_reference IS NOT NULL)),
    UNIQUE (creator_party_id, account_uuid)
);

-- All history counts as prior state. Retained/tombstoned evidence cannot be reset to fresh.
-- This migration intentionally enrolls no existing tenant or party.
CREATE TABLE account_tenant_creator_party_history (
    history_id UUID PRIMARY KEY CHECK (history_id <> '00000000-0000-0000-0000-000000000000'),
    tenant_uuid UUID NOT NULL,
    origin VARCHAR(24) NOT NULL CHECK (origin IN ('FRESH_INITIAL', 'RETAINED', 'TOMBSTONE')),
    creator_party_id UUID REFERENCES account_individual_creator_party_sources (creator_party_id),
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    evidence_payload BYTEA NOT NULL CHECK (octet_length(evidence_payload) > 0),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_uuid, source_version),
    CHECK (origin <> 'FRESH_INITIAL' OR (creator_party_id IS NOT NULL AND source_version = 1))
);

CREATE TABLE account_fresh_creator_party_association_operations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    tenant_uuid UUID NOT NULL UNIQUE REFERENCES account_canonical_tenant_identity_claims (canonical_tenant_id),
    initiating_account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    creator_party_id UUID NOT NULL,
    creation_operation_id UUID NOT NULL UNIQUE,
    creator_evidence_payload BYTEA NOT NULL CHECK (octet_length(creator_evidence_payload) > 0),
    party_source_payload BYTEA NOT NULL CHECK (octet_length(party_source_payload) > 0),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    history_id UUID NOT NULL UNIQUE REFERENCES account_tenant_creator_party_history (history_id),
    result_payload BYTEA NOT NULL CHECK (octet_length(result_payload) > 0),
    result_digest VARCHAR(71) NOT NULL CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_party_id, initiating_account_uuid)
        REFERENCES account_individual_creator_party_sources (creator_party_id, account_uuid)
);

-- [jooq ignore start]
CREATE FUNCTION account_creator_party_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Account creator-party source/history/receipt is immutable';
END;
$$;
CREATE TRIGGER account_individual_creator_party_immutable
    BEFORE UPDATE OR DELETE ON account_individual_creator_party_sources
    FOR EACH ROW EXECUTE FUNCTION account_creator_party_immutable_guard();
CREATE TRIGGER account_creator_party_history_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creator_party_history
    FOR EACH ROW EXECUTE FUNCTION account_creator_party_immutable_guard();
CREATE TRIGGER account_creator_party_operation_immutable
    BEFORE UPDATE OR DELETE ON account_fresh_creator_party_association_operations
    FOR EACH ROW EXECUTE FUNCTION account_creator_party_immutable_guard();

CREATE FUNCTION account_creator_party_history_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- All future history writers must use this same canonical tenant serialization.
    -- An older repeatable snapshot cannot prove history absence after waiting for this lock.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Creator-party history requires READ COMMITTED owner transaction';
    END IF;
    PERFORM 1 FROM account_canonical_tenant_identity_claims
        WHERE canonical_tenant_id = NEW.tenant_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Creator-party history requires existing canonical tenant provenance';
    END IF;
    IF NEW.origin = 'FRESH_INITIAL' AND EXISTS (
        SELECT 1 FROM account_tenant_creator_party_history WHERE tenant_uuid = NEW.tenant_uuid) THEN
        RAISE EXCEPTION 'Fresh association cannot replace prior creator-party history';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_creator_party_history_insert
    BEFORE INSERT ON account_tenant_creator_party_history
    FOR EACH ROW EXECUTE FUNCTION account_creator_party_history_insert_guard();

CREATE FUNCTION account_creator_party_initial_receipt_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    history account_tenant_creator_party_history%ROWTYPE;
    receipt account_fresh_creator_party_association_operations%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'account_tenant_creator_party_history' THEN
        history := NEW;
        IF history.origin <> 'FRESH_INITIAL' THEN RETURN NEW; END IF;
        SELECT * INTO receipt FROM account_fresh_creator_party_association_operations
            WHERE history_id = history.history_id;
    ELSE
        receipt := NEW;
        SELECT * INTO history FROM account_tenant_creator_party_history
            WHERE history_id = receipt.history_id;
    END IF;
    IF receipt.request_id IS NULL OR history.history_id IS NULL
        OR history.origin <> 'FRESH_INITIAL'
        OR history.tenant_uuid <> receipt.tenant_uuid
        OR history.creator_party_id <> receipt.creator_party_id
        OR history.evidence_payload <> receipt.result_payload
        OR history.evidence_digest <> receipt.result_digest
        OR (SELECT count(*) FROM account_tenant_creator_party_history
            WHERE tenant_uuid = receipt.tenant_uuid) <> 1 THEN
        RAISE EXCEPTION 'Fresh creator-party history requires exact immutable owner receipt';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM account_individual_creator_party_sources party
        WHERE party.creator_party_id = receipt.creator_party_id
            AND party.account_uuid = receipt.initiating_account_uuid
            AND party.verification_status = 'VERIFIED'
            AND party.source_payload = receipt.party_source_payload)
        OR NOT EXISTS (
        SELECT 1 FROM account_tenant_creation_bootstrap_operations bootstrap
        JOIN account_fresh_tenant_identity_associations fresh
            ON fresh.canonical_tenant_id = bootstrap.tenant_uuid
        WHERE bootstrap.status = 'COMMITTED'
            AND bootstrap.tenant_uuid = receipt.tenant_uuid
            AND bootstrap.initiating_account_uuid = receipt.initiating_account_uuid
            AND bootstrap.creation_operation_id = receipt.creation_operation_id
            AND fresh.operation_id = receipt.creation_operation_id
            AND fresh.creation_request_id = bootstrap.creation_request_id
            AND bootstrap.creator_evidence_payload = receipt.creator_evidence_payload) THEN
        RAISE EXCEPTION 'Fresh creator-party receipt requires exact persisted individual and creator sources';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_creator_party_initial_history_receipt
    AFTER INSERT ON account_tenant_creator_party_history DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_creator_party_initial_receipt_guard();
CREATE CONSTRAINT TRIGGER account_creator_party_initial_operation_receipt
    AFTER INSERT ON account_fresh_creator_party_association_operations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_creator_party_initial_receipt_guard();
-- [jooq ignore stop]
