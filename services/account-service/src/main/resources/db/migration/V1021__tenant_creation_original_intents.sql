-- Retain original Account tenant-creation request data only. This is not an authorization,
-- source-hold, billing-owner, delivery, or Game Design dispatch surface.
CREATE TABLE account_tenant_creation_original_intents (
    request_id UUID PRIMARY KEY
        CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    creation_operation_id UUID NOT NULL UNIQUE
        CHECK (creation_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_design_creation_request_id UUID NOT NULL
        CHECK (game_design_creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    initiating_account_uuid UUID NOT NULL
        REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    source_game_tenant_key VARCHAR(36) NOT NULL
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    name VARCHAR(100) NOT NULL CHECK (char_length(name) <= 100),
    description TEXT
        CHECK (
            description IS NULL
            OR (char_length(description) <= 255 AND octet_length(description) <= 1020)
        ),
    recent_reauthentication_evidence_id UUID NOT NULL UNIQUE
        REFERENCES account_recent_reauthentication_evidence (evidence_id) ON DELETE RESTRICT,
    creator_party_id UUID NOT NULL
        CHECK (creator_party_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    consent_operation_id UUID NOT NULL
        CHECK (consent_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    consent_statement_version INTEGER NOT NULL CHECK (consent_statement_version > 0),
    affirmative_consent BOOLEAN NOT NULL CHECK (affirmative_consent),
    game_design_request_digest VARCHAR(71) NOT NULL
        CHECK (game_design_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) BETWEEN 1 AND 8192),
    request_digest VARCHAR(71) NOT NULL
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_tenant_creation_original_gd_request_unique
        UNIQUE (target_namespace, game_design_creation_request_id)
);

-- The intent references the exact durable, unconsumed reauthentication source. This check is
-- correlation and serialization only; it does not evaluate currentness or grant permission.
-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_original_intent_source_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    evidence_row account_recent_reauthentication_evidence%ROWTYPE;
BEGIN
    SELECT * INTO evidence_row
      FROM account_recent_reauthentication_evidence
      WHERE evidence_id = NEW.recent_reauthentication_evidence_id
      FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tenant-creation intent recent reauthentication evidence is missing'
            USING ERRCODE = '23514';
    END IF;
    IF evidence_row.account_uuid IS DISTINCT FROM NEW.initiating_account_uuid
        OR evidence_row.purpose IS DISTINCT FROM 'SELF_SERVICE_GAME_CREATION'
        OR evidence_row.creation_operation_id IS DISTINCT FROM NEW.creation_operation_id THEN
        RAISE EXCEPTION 'Tenant-creation intent differs from its original reauthentication evidence'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM account_recent_reauthentication_consumptions consumption
        WHERE consumption.evidence_id = evidence_row.evidence_id
    ) THEN
        RAISE EXCEPTION 'Tenant-creation intent recent reauthentication evidence is consumed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_original_intent_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original Account tenant-creation intent is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_tenant_creation_original_intent_source_guard
    BEFORE INSERT ON account_tenant_creation_original_intents
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_original_intent_source_guard();
CREATE TRIGGER account_tenant_creation_original_intent_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_original_intents
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_original_intent_no_mutation();
CREATE TRIGGER account_tenant_creation_original_intent_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_original_intents
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_original_intent_no_mutation();
-- [jooq ignore stop]
