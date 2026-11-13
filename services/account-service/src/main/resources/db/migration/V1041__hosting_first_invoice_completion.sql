-- Held Account-owned existing first-invoice checkout. No registration, payment or entitlement is
-- authorized by this migration. Provider/reauth policy rows are deliberately not seeded.
CREATE TABLE account_billing_reauthentication_policy_values (
    policy_scope VARCHAR(16) NOT NULL CHECK (policy_scope IN ('PLATFORM', 'DEPLOYMENT')),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    recent_age_seconds BIGINT NOT NULL CHECK (recent_age_seconds BETWEEN 1 AND 300),
    max_clock_skew_seconds BIGINT NOT NULL CHECK (max_clock_skew_seconds BETWEEN 0 AND 30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (max_clock_skew_seconds < recent_age_seconds),
    PRIMARY KEY (policy_scope, policy_version)
);

CREATE TABLE account_billing_reauthentication_policy_current (
    policy_scope VARCHAR(16) PRIMARY KEY CHECK (policy_scope IN ('PLATFORM', 'DEPLOYMENT')),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    FOREIGN KEY (policy_scope, policy_version)
        REFERENCES account_billing_reauthentication_policy_values(policy_scope, policy_version)
        ON DELETE RESTRICT
);

CREATE TABLE account_hosting_billing_policy_values (
    provider_price_id VARCHAR(255) NOT NULL CHECK (provider_price_id ~ '^price_[A-Za-z0-9]+$'),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[a-z]{3}$'),
    PRIMARY KEY (provider_price_id, policy_version)
);
CREATE TABLE account_hosting_billing_policy_current (
    provider_price_id VARCHAR(255) PRIMARY KEY,
    policy_version BIGINT NOT NULL,
    FOREIGN KEY (provider_price_id, policy_version) REFERENCES account_hosting_billing_policy_values ON DELETE RESTRICT
);
CREATE TABLE subscription_first_invoices (
    subscription_id UUID PRIMARY KEY REFERENCES subscription(subscription_id) ON DELETE RESTRICT,
    creation_request_id UUID NOT NULL UNIQUE REFERENCES subscription_create_operations(request_id) ON DELETE RESTRICT,
    tenant_id UUID NOT NULL,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    payment_instrument_id UUID NOT NULL REFERENCES payment_instrument(instrument_uuid) ON DELETE RESTRICT,
    instrument_version BIGINT NOT NULL CHECK (instrument_version > 0),
    provider_subscription_id VARCHAR(255) NOT NULL UNIQUE,
    provider_customer_id VARCHAR(255) NOT NULL,
    provider_payment_method_id VARCHAR(255) NOT NULL,
    invoice_id VARCHAR(255) NOT NULL UNIQUE CHECK (invoice_id ~ '^in_[A-Za-z0-9]+$'),
    payment_intent_id VARCHAR(255) NOT NULL UNIQUE CHECK (payment_intent_id ~ '^pi_[A-Za-z0-9]+$'),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[a-z]{3}$'),
    quote_digest VARCHAR(71) NOT NULL,
    provider_price_id VARCHAR(255) NOT NULL CHECK (provider_price_id ~ '^price_[A-Za-z0-9]+$'),
    provider_quantity BIGINT NOT NULL CHECK (provider_quantity > 0),
    billing_policy_version BIGINT NOT NULL CHECK (billing_policy_version > 0),
    billing_policy_digest VARCHAR(71) NOT NULL,
    livemode BOOLEAN NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > captured_at),
    tuple_digest VARCHAR(71) NOT NULL CHECK (tuple_digest ~ '^sha256:[0-9a-f]{64}$'),
    UNIQUE (subscription_id, tuple_digest),
    FOREIGN KEY (provider_price_id, billing_policy_version) REFERENCES account_hosting_billing_policy_values(provider_price_id, policy_version) ON DELETE RESTRICT,
    FOREIGN KEY (provider_subscription_id, subscription_id) REFERENCES subscription_provider_history(provider_subscription_id, subscription_id) ON DELETE RESTRICT
);
CREATE TABLE subscription_first_invoice_confirmations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    subscription_id UUID NOT NULL UNIQUE,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tuple_digest VARCHAR(71) NOT NULL,
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version > 0),
    reauthentication_evidence_id UUID NOT NULL UNIQUE REFERENCES account_recent_reauthentication_consumptions(evidence_id) ON DELETE RESTRICT,
    provider_idempotency_id UUID NOT NULL UNIQUE,
    lifecycle_request_id UUID NOT NULL UNIQUE,
    audit_event_id UUID NOT NULL UNIQUE,
    consented_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL CHECK (valid_until > consented_at),
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    CHECK (request_id <> provider_idempotency_id AND request_id <> lifecycle_request_id AND provider_idempotency_id <> lifecycle_request_id),
    FOREIGN KEY (subscription_id, tuple_digest) REFERENCES subscription_first_invoices(subscription_id, tuple_digest) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id, tuple_digest),
    UNIQUE (request_id, subscription_id)
);
CREATE TABLE subscription_first_invoice_dispatches (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    authorized_at TIMESTAMPTZ NOT NULL,
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_first_invoice_confirmations(request_id, subscription_id) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id)
);
-- Authorization attempts are append-only; the original payment/consent/idempotency tuple never
-- changes. Only a newly created current attempt can win the sole durable command claim.
CREATE TABLE subscription_first_invoice_authorizations (
    authorization_request_id UUID PRIMARY KEY CHECK (authorization_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    authorization_version BIGINT NOT NULL CHECK (authorization_version > 0),
    reauthentication_evidence_id UUID NOT NULL UNIQUE REFERENCES account_recent_reauthentication_consumptions(evidence_id) ON DELETE RESTRICT,
    consented_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL CHECK (valid_until > consented_at),
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_first_invoice_dispatches(request_id, subscription_id) ON DELETE RESTRICT,
    UNIQUE (subscription_id, authorization_version),
    UNIQUE (authorization_request_id, request_id, subscription_id)
);
CREATE TABLE subscription_first_invoice_command_claims (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    authorization_request_id UUID NOT NULL UNIQUE,
    claimed_at TIMESTAMPTZ NOT NULL,
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (authorization_request_id, request_id, subscription_id) REFERENCES subscription_first_invoice_authorizations(authorization_request_id, request_id, subscription_id) ON DELETE RESTRICT
);
CREATE TABLE subscription_first_invoice_success_evidence (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    provider_event_id VARCHAR(255) NOT NULL REFERENCES stripe_event_inbox(provider_event_id) ON DELETE RESTRICT,
    event_payload_digest VARCHAR(71) NOT NULL,
    tuple_digest VARCHAR(71) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_first_invoice_dispatches(request_id, subscription_id) ON DELETE RESTRICT,
    FOREIGN KEY (request_id, subscription_id, tuple_digest) REFERENCES subscription_first_invoice_confirmations(request_id, subscription_id, tuple_digest) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id)
);
CREATE TABLE subscription_first_invoice_completions (
    subscription_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    lifecycle_request_id UUID NOT NULL UNIQUE REFERENCES subscription_lifecycle_receipts(request_id) DEFERRABLE INITIALLY DEFERRED,
    tuple_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    audit_event_id UUID NOT NULL UNIQUE REFERENCES account_audit_outbox(audit_event_id) ON DELETE RESTRICT,
    committed_at TIMESTAMPTZ NOT NULL,
    committed_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_first_invoice_success_evidence(request_id, subscription_id) ON DELETE RESTRICT
);
-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_authorization_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE consent subscription_first_invoice_confirmations%ROWTYPE; auth account_recent_reauthentication_evidence%ROWTYPE; invoice subscription_first_invoices%ROWTYPE; current_row RECORD; latest BIGINT;
BEGIN
    SELECT s.status, s.row_version, s.provider_subscription_id, w.phase INTO STRICT current_row
        FROM subscription s JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id
        WHERE s.subscription_id = NEW.subscription_id AND s.superseded_at IS NULL FOR UPDATE OF s;
    SELECT * INTO STRICT consent FROM subscription_first_invoice_confirmations WHERE request_id = NEW.request_id;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices WHERE subscription_id = NEW.subscription_id;
    SELECT * INTO STRICT auth FROM account_recent_reauthentication_evidence WHERE evidence_id = NEW.reauthentication_evidence_id;
    SELECT COALESCE(MAX(authorization_version), 0) INTO latest FROM subscription_first_invoice_authorizations WHERE subscription_id = NEW.subscription_id;
    IF consent.subscription_id <> NEW.subscription_id OR current_row.status <> 'provisioning' OR current_row.phase <> 'AWAITING_PAYMENT'
        OR current_row.row_version <> consent.expected_row_version OR current_row.provider_subscription_id <> invoice.provider_subscription_id
        OR NEW.authorization_version <> latest + 1
        OR (NEW.authorization_version = 1 AND (NEW.authorization_request_id <> NEW.request_id OR NEW.reauthentication_evidence_id <> consent.reauthentication_evidence_id OR consent.created_transaction_id <> txid_current()))
        OR (NEW.authorization_version > 1 AND NEW.authorization_request_id = NEW.request_id)
        OR auth.account_uuid <> consent.account_uuid OR auth.purpose <> 'HOSTING_FIRST_INVOICE_PAYMENT'
        OR auth.creation_operation_id <> NEW.authorization_request_id
        OR NEW.consented_at < auth.issued_at OR NEW.consented_at >= NEW.valid_until
        OR NEW.valid_until > invoice.expires_at
        OR NEW.valid_until > auth.issued_at + (auth.recent_age_seconds - auth.max_clock_skew_seconds) * INTERVAL '1 second'
        OR NOT EXISTS (SELECT 1 FROM account_recent_reauthentication_consumptions c WHERE c.evidence_id = auth.evidence_id AND c.creation_operation_id = NEW.authorization_request_id AND c.account_uuid = consent.account_uuid AND c.purpose = auth.purpose) THEN
        RAISE EXCEPTION 'First-invoice authorization requires fresh exact consumed consent and current version' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_authorization BEFORE INSERT ON subscription_first_invoice_authorizations
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_authorization_guard();
CREATE FUNCTION account_first_invoice_command_claim_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE auth subscription_first_invoice_authorizations%ROWTYPE; consent subscription_first_invoice_confirmations%ROWTYPE; current_row RECORD; latest BIGINT;
BEGIN
    SELECT * INTO STRICT auth FROM subscription_first_invoice_authorizations WHERE authorization_request_id = NEW.authorization_request_id;
    SELECT * INTO STRICT consent FROM subscription_first_invoice_confirmations WHERE request_id = NEW.request_id;
    SELECT s.status, s.row_version, w.phase INTO STRICT current_row FROM subscription s
        JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id
        WHERE s.subscription_id = NEW.subscription_id AND s.superseded_at IS NULL FOR UPDATE OF s;
    SELECT MAX(authorization_version) INTO latest FROM subscription_first_invoice_authorizations WHERE subscription_id = NEW.subscription_id;
    IF auth.authorization_version <> latest OR auth.created_transaction_id = txid_current()
        OR current_row.status <> 'provisioning' OR current_row.phase <> 'AWAITING_PAYMENT' OR current_row.row_version <> consent.expected_row_version
        OR NEW.claimed_at < auth.consented_at OR NEW.claimed_at >= auth.valid_until THEN
        RAISE EXCEPTION 'First-invoice command requires independently read latest unexpired authorization' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_command_claim BEFORE INSERT ON subscription_first_invoice_command_claims
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_command_claim_guard();
CREATE TRIGGER first_invoice_authorizations_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_authorizations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER first_invoice_authorizations_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_authorizations
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER first_invoice_command_claims_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_command_claims
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER first_invoice_command_claims_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_command_claims
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
ALTER TABLE subscription_first_invoices ADD CONSTRAINT first_invoice_expiry_bound CHECK (expires_at <= captured_at + INTERVAL '23 hours');
ALTER TABLE account_recent_reauthentication_evidence DROP CONSTRAINT account_recent_reauthentication_evidence_purpose_check;
ALTER TABLE account_recent_reauthentication_evidence ADD CONSTRAINT account_recent_reauthentication_evidence_purpose_check CHECK (purpose IN ('SELF_SERVICE_GAME_CREATION','HOSTING_FIRST_INVOICE_PAYMENT'));
ALTER TABLE account_recent_reauthentication_consumptions DROP CONSTRAINT account_recent_reauthentication_consumptions_purpose_check;
ALTER TABLE account_recent_reauthentication_consumptions ADD CONSTRAINT account_recent_reauthentication_consumptions_purpose_check CHECK (purpose IN ('SELF_SERVICE_GAME_CREATION','HOSTING_FIRST_INVOICE_PAYMENT'));
-- PostgreSQL truncates generated relation/column constraint names differently from SQL identifiers.
-- Resolve the one existing purpose-column check rather than assuming its truncated name.
DO $$
DECLARE existing_check TEXT;
BEGIN
    SELECT constraint_row.conname INTO STRICT existing_check FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'account_recent_reauthentication_replay_credentials'::REGCLASS
        AND constraint_row.contype = 'c'
        AND constraint_row.conkey = ARRAY[(SELECT attnum FROM pg_attribute
            WHERE attrelid = constraint_row.conrelid AND attname = 'purpose')]::SMALLINT[];
    EXECUTE format('ALTER TABLE account_recent_reauthentication_replay_credentials DROP CONSTRAINT %I', existing_check);
END;
$$;
ALTER TABLE account_recent_reauthentication_replay_credentials ADD CONSTRAINT billing_reauth_replay_credentials_purpose_check CHECK (purpose IN ('SELF_SERVICE_GAME_CREATION','HOSTING_FIRST_INVOICE_PAYMENT'));
ALTER TABLE account_recent_reauthentication_replay_failures DROP CONSTRAINT account_recent_reauthentication_replay_failures_purpose_check;
ALTER TABLE account_recent_reauthentication_replay_failures ADD CONSTRAINT account_recent_reauthentication_replay_failures_purpose_check CHECK (purpose IN ('SELF_SERVICE_GAME_CREATION','HOSTING_FIRST_INVOICE_PAYMENT'));
CREATE OR REPLACE FUNCTION account_recent_reauthentication_evidence_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_row RECORD;
    generation_row RECORD;
    fence_row RECORD;
    platform_row RECORD;
    deployment_row RECORD;
    effective_age BIGINT;
    effective_skew BIGINT;
    policy_preimage TEXT;
    expected_policy_version TEXT;
BEGIN
    SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id, lifecycle_state
      INTO account_row FROM accounts WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF NOT FOUND OR account_row.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR account_row.id IS DISTINCT FROM NEW.account_uuid_source_numeric_id
        OR account_row.account_uuid_provenance IS DISTINCT FROM NEW.identity_provenance
        OR account_row.lifecycle_state IS DISTINCT FROM 'active' THEN
        RAISE EXCEPTION 'Recent reauthentication requires the exact current active Account identity'
            USING ERRCODE = '23514';
    END IF;

    SELECT generation, source_version INTO generation_row
      FROM account_authority_generations
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid FOR SHARE;
    SELECT issuance_fence, source_version INTO fence_row
      FROM account_authority_issuance_fences
      WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF NOT FOUND OR generation_row.generation IS DISTINCT FROM NEW.account_authority_generation
        OR generation_row.source_version IS DISTINCT FROM NEW.account_authority_source_version
        OR fence_row.issuance_fence IS DISTINCT FROM NEW.issuance_fence
        OR fence_row.source_version IS DISTINCT FROM NEW.issuance_fence_source_version THEN
        RAISE EXCEPTION 'Recent reauthentication Account authority source is stale'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.purpose = 'HOSTING_FIRST_INVOICE_PAYMENT' THEN
    SELECT value.policy_version, value.recent_age_seconds, value.max_clock_skew_seconds
      INTO platform_row
      FROM account_billing_reauthentication_policy_current current
      JOIN account_billing_reauthentication_policy_values value
        ON value.policy_scope = current.policy_scope AND value.policy_version = current.policy_version
      WHERE current.policy_scope = 'PLATFORM';
    SELECT value.policy_version, value.recent_age_seconds, value.max_clock_skew_seconds
      INTO deployment_row
      FROM account_billing_reauthentication_policy_current current
      JOIN account_billing_reauthentication_policy_values value
        ON value.policy_scope = current.policy_scope AND value.policy_version = current.policy_version
      WHERE current.policy_scope = 'DEPLOYMENT';
    ELSE
    SELECT value.policy_version, value.recent_age_seconds, value.max_clock_skew_seconds
      INTO platform_row
      FROM account_recent_reauthentication_policy_current current
      JOIN account_recent_reauthentication_policy_values value
        ON value.policy_scope = current.policy_scope AND value.policy_version = current.policy_version
      WHERE current.policy_scope = 'PLATFORM';
    SELECT value.policy_version, value.recent_age_seconds, value.max_clock_skew_seconds
      INTO deployment_row
      FROM account_recent_reauthentication_policy_current current
      JOIN account_recent_reauthentication_policy_values value
        ON value.policy_scope = current.policy_scope AND value.policy_version = current.policy_version
      WHERE current.policy_scope = 'DEPLOYMENT';
    END IF;
    IF NOT FOUND OR platform_row.policy_version IS DISTINCT FROM NEW.platform_policy_version
        OR deployment_row.policy_version IS DISTINCT FROM NEW.deployment_policy_version
        OR deployment_row.recent_age_seconds > platform_row.recent_age_seconds
        OR deployment_row.max_clock_skew_seconds > platform_row.max_clock_skew_seconds THEN
        RAISE EXCEPTION 'Recent reauthentication policy source is missing, stale, or non-tightening'
            USING ERRCODE = '23514';
    END IF;
    effective_age := LEAST(platform_row.recent_age_seconds, deployment_row.recent_age_seconds);
    effective_skew := LEAST(platform_row.max_clock_skew_seconds, deployment_row.max_clock_skew_seconds);
    IF effective_skew >= effective_age
        OR NEW.recent_age_seconds IS DISTINCT FROM effective_age
        OR NEW.max_clock_skew_seconds IS DISTINCT FROM effective_skew THEN
        RAISE EXCEPTION 'Recent reauthentication effective policy values are invalid'
            USING ERRCODE = '23514';
    END IF;

    policy_preimage := 'account-recent-reauthentication-policy/v1' || E'\n'
        || 'purpose=' || NEW.purpose || E'\n'
        || 'platformVersion=' || platform_row.policy_version::TEXT || E'\n'
        || 'platformRecentAgeSeconds=' || platform_row.recent_age_seconds::TEXT || E'\n'
        || 'platformMaxClockSkewSeconds=' || platform_row.max_clock_skew_seconds::TEXT || E'\n'
        || 'deploymentVersion=' || deployment_row.policy_version::TEXT || E'\n'
        || 'deploymentRecentAgeSeconds=' || deployment_row.recent_age_seconds::TEXT || E'\n'
        || 'deploymentMaxClockSkewSeconds=' || deployment_row.max_clock_skew_seconds::TEXT || E'\n'
        || 'effectiveRecentAgeSeconds=' || effective_age::TEXT || E'\n'
        || 'effectiveMaxClockSkewSeconds=' || effective_skew::TEXT || E'\n';
    expected_policy_version := 'sha256:' || encode(sha256(convert_to(policy_preimage, 'UTF8')), 'hex');
    IF NEW.effective_policy_version IS DISTINCT FROM expected_policy_version THEN
        RAISE EXCEPTION 'Recent reauthentication effective policy version is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_billing_reauthentication_policy_values_immutable BEFORE UPDATE OR DELETE ON account_billing_reauthentication_policy_values FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_billing_reauthentication_policy_values_no_truncate BEFORE TRUNCATE ON account_billing_reauthentication_policy_values FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_billing_reauthentication_policy_current_no_delete BEFORE DELETE ON account_billing_reauthentication_policy_current FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_billing_reauthentication_policy_current_no_truncate BEFORE TRUNCATE ON account_billing_reauthentication_policy_current FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_hosting_billing_policy_values_immutable BEFORE UPDATE OR DELETE ON account_hosting_billing_policy_values FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_hosting_billing_policy_values_no_truncate BEFORE TRUNCATE ON account_hosting_billing_policy_values FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_hosting_billing_policy_current_no_delete BEFORE DELETE ON account_hosting_billing_policy_current FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_hosting_billing_policy_current_no_truncate BEFORE TRUNCATE ON account_hosting_billing_policy_current FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoices_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoices FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoices_no_truncate BEFORE TRUNCATE ON subscription_first_invoices FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_confirmations_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_confirmations FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_confirmations_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_confirmations FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_success_evidence_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_success_evidence FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_success_evidence_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_success_evidence FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_completions_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_completions FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_completions_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_completions FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER account_billing_reauth_current_monotonic BEFORE UPDATE ON account_billing_reauthentication_policy_current
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_policy_current_guard();
CREATE FUNCTION account_hosting_billing_policy_current_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider_price_id IS DISTINCT FROM OLD.provider_price_id OR NEW.policy_version <= OLD.policy_version THEN
        RAISE EXCEPTION 'Hosting billing policy must advance monotonically' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER hosting_billing_current_monotonic BEFORE UPDATE ON account_hosting_billing_policy_current
    FOR EACH ROW EXECUTE FUNCTION account_hosting_billing_policy_current_guard();
CREATE FUNCTION account_first_invoice_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_row subscription%ROWTYPE; creation subscription_create_operations%ROWTYPE; wallet payment_instrument%ROWTYPE; commercial account_hosting_billing_policy_values%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    SELECT * INTO STRICT creation FROM subscription_create_operations WHERE request_id = current_row.creation_request_id;
    SELECT * INTO STRICT wallet FROM payment_instrument WHERE instrument_uuid = current_row.payment_instrument_id;
    SELECT * INTO STRICT commercial FROM account_hosting_billing_policy_values WHERE provider_price_id = NEW.provider_price_id AND policy_version = NEW.billing_policy_version;
    IF current_row.status <> 'provisioning' OR current_row.superseded_at IS NOT NULL OR current_row.current_receipt_request_id IS NOT NULL
        OR (NEW.provider_price_id, NEW.provider_quantity) IS DISTINCT FROM (creation.provider_price_id, creation.provider_quantity)
        OR (NEW.provider_quantity, NEW.amount, NEW.currency) IS DISTINCT FROM (commercial.quantity, commercial.amount, commercial.currency)
        OR (NEW.creation_request_id, NEW.tenant_id, NEW.account_uuid, NEW.payment_instrument_id, NEW.instrument_version,
            NEW.provider_customer_id, NEW.provider_payment_method_id, NEW.quote_digest)
            IS DISTINCT FROM (creation.request_id, creation.tenant_id, creation.account_uuid, creation.payment_instrument_id,
                creation.reserved_instrument_version, creation.provider_customer_id, wallet.provider_payment_method_id, creation.plan_quote_digest) THEN
        RAISE EXCEPTION 'First invoice differs from exact reserved creation tuple' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_source BEFORE INSERT ON subscription_first_invoices FOR EACH ROW EXECUTE FUNCTION account_first_invoice_source_guard();
CREATE FUNCTION account_first_invoice_consent_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE auth account_recent_reauthentication_evidence%ROWTYPE; invoice subscription_first_invoices%ROWTYPE; current_row RECORD;
BEGIN
    SELECT * INTO STRICT auth FROM account_recent_reauthentication_evidence WHERE evidence_id = NEW.reauthentication_evidence_id;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices WHERE subscription_id = NEW.subscription_id;
    SELECT s.status, s.row_version, s.provider_subscription_id, w.phase INTO STRICT current_row
        FROM subscription s JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id
        WHERE s.subscription_id = NEW.subscription_id AND w.request_id = invoice.creation_request_id
            AND s.superseded_at IS NULL FOR SHARE OF s, w;
    IF current_row.status <> 'provisioning' OR current_row.phase <> 'AWAITING_PAYMENT'
        OR current_row.row_version <> NEW.expected_row_version
        OR current_row.provider_subscription_id IS DISTINCT FROM invoice.provider_subscription_id THEN
        RAISE EXCEPTION 'First-invoice consent requires exact current awaiting-payment source' USING ERRCODE = '23514';
    END IF;
    IF auth.purpose <> 'HOSTING_FIRST_INVOICE_PAYMENT' OR auth.creation_operation_id <> NEW.request_id
        OR auth.account_uuid <> NEW.account_uuid OR invoice.account_uuid <> NEW.account_uuid OR invoice.tuple_digest <> NEW.tuple_digest
        OR NEW.consented_at < auth.issued_at OR NEW.valid_until > auth.issued_at + (auth.recent_age_seconds - auth.max_clock_skew_seconds) * INTERVAL '1 second'
        OR NEW.valid_until > invoice.expires_at THEN
        RAISE EXCEPTION 'First-invoice consent requires current exact billing-purpose reauthentication' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_consent BEFORE INSERT ON subscription_first_invoice_confirmations FOR EACH ROW EXECUTE FUNCTION account_first_invoice_consent_guard();
CREATE FUNCTION account_first_invoice_completion_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE consent subscription_first_invoice_confirmations%ROWTYPE; receipt subscription_lifecycle_receipts%ROWTYPE; audit_row RECORD;
BEGIN
    SELECT * INTO STRICT consent FROM subscription_first_invoice_confirmations WHERE request_id = NEW.request_id;
    SELECT * INTO STRICT receipt FROM subscription_lifecycle_receipts WHERE request_id = NEW.lifecycle_request_id;
    SELECT *, xmin::text::BIGINT AS source_transaction_id INTO STRICT audit_row FROM account_audit_outbox WHERE audit_event_id = NEW.audit_event_id;
    IF (consent.subscription_id, consent.lifecycle_request_id, consent.tuple_digest, consent.audit_event_id)
            IS DISTINCT FROM (NEW.subscription_id, NEW.lifecycle_request_id, NEW.tuple_digest, NEW.audit_event_id)
        OR receipt.subscription_id <> NEW.subscription_id OR receipt.receipt_digest <> NEW.receipt_digest
        OR receipt.status NOT IN ('trialing','active','past_due','grace') OR receipt.committed_transaction_id <> txid_current()
        OR audit_row.event_type <> 'HOSTING_FIRST_INVOICE_COMPLETED' OR audit_row.tenant_uuid IS DISTINCT FROM receipt.tenant_id
        OR audit_row.source_transaction_id <> mod(txid_current(), 4294967296)
        OR audit_row.payload::JSONB->>'tupleDigest' IS DISTINCT FROM NEW.tuple_digest
        OR audit_row.payload::JSONB->>'receiptDigest' IS DISTINCT FROM NEW.receipt_digest THEN
        RAISE EXCEPTION 'First-invoice completion requires exact atomic hosting receipt and authority outboxes' USING ERRCODE = '23514';
    END IF;
    NEW.committed_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_completion BEFORE INSERT ON subscription_first_invoice_completions FOR EACH ROW EXECUTE FUNCTION account_first_invoice_completion_guard();
-- The original provisioning claim and a later signed first-invoice fulfillment are distinct
-- operations. Neither a provider read nor a NULL provisioning attempt is completion evidence.
CREATE OR REPLACE FUNCTION account_subscription_provisioning_confirmed_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE work subscription_provisioning_work%ROWTYPE;
BEGIN
    SELECT * INTO STRICT work FROM subscription_provisioning_work WHERE request_id = NEW.request_id;
    IF work.phase = 'CONFIRMED' AND NOT EXISTS (
        SELECT 1 FROM subscription_lifecycle_operations operation
        JOIN subscription_lifecycle_receipts receipt USING (request_id, subscription_id)
        WHERE operation.creation_request_id = work.request_id AND operation.subscription_id = work.subscription_id
            AND operation.provisioning_attempt_id = work.last_attempt_id AND operation.state = 'COMMITTED')
        AND NOT EXISTS (
            SELECT 1 FROM subscription_first_invoices invoice
            JOIN subscription_first_invoice_confirmations consent
                ON consent.subscription_id = invoice.subscription_id AND consent.tuple_digest = invoice.tuple_digest
            JOIN subscription_first_invoice_success_evidence success
                ON (success.request_id, success.subscription_id, success.tuple_digest)
                    = (consent.request_id, consent.subscription_id, consent.tuple_digest)
            JOIN subscription_first_invoice_completions completion
                ON (completion.request_id, completion.subscription_id, completion.lifecycle_request_id,
                    completion.tuple_digest, completion.audit_event_id)
                    = (consent.request_id, consent.subscription_id, consent.lifecycle_request_id,
                        consent.tuple_digest, consent.audit_event_id)
            JOIN subscription_lifecycle_operations operation
                ON operation.request_id = completion.lifecycle_request_id
                    AND operation.subscription_id = completion.subscription_id
            JOIN subscription_lifecycle_receipts receipt
                ON receipt.request_id = operation.request_id AND receipt.subscription_id = operation.subscription_id
            WHERE invoice.creation_request_id = work.request_id AND invoice.subscription_id = work.subscription_id
                AND operation.creation_request_id = work.request_id AND operation.state = 'COMMITTED'
                AND operation.provisioning_attempt_id IS NULL
                AND operation.provider_subscription_id = invoice.provider_subscription_id
                AND receipt.provider_subscription_id = invoice.provider_subscription_id
                AND receipt.status IN ('trialing','active','past_due','grace')
                AND receipt.receipt_digest = completion.receipt_digest
                AND completion.committed_transaction_id = receipt.committed_transaction_id
                AND completion.committed_transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'Provisioning confirmation lacks its exact lifecycle receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
ALTER TABLE subscription_provisioning_work DROP CONSTRAINT subscription_provisioning_work_phase_check;
ALTER TABLE subscription_provisioning_work ADD CONSTRAINT subscription_provisioning_work_phase_check
    CHECK (phase IN ('RECONCILING','CLAIMED','AWAITING_PAYMENT','CONFIRMED','BILLING_RECONCILIATION_TERMINAL_FAILURE'));
CREATE OR REPLACE FUNCTION account_subscription_provisioning_work_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Provisioning work is retained' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'RECONCILING' OR NEW.attempt_count <> 0 OR NEW.row_version <> 1
            OR NEW.failure_reason IS NOT NULL OR NEW.terminal_at IS NOT NULL THEN
            RAISE EXCEPTION 'Provisioning work must start unclaimed' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF OLD.phase IN ('CONFIRMED','BILLING_RECONCILIATION_TERMINAL_FAILURE') OR (OLD.phase = 'AWAITING_PAYMENT' AND NEW.phase NOT IN ('CONFIRMED','BILLING_RECONCILIATION_TERMINAL_FAILURE'))
            OR (NEW.request_id, NEW.subscription_id, NEW.max_attempts, NEW.max_stalled_attempts,
                NEW.lease_millis, NEW.backoff_millis, NEW.deadline_at)
                IS DISTINCT FROM (OLD.request_id, OLD.subscription_id, OLD.max_attempts, OLD.max_stalled_attempts,
                    OLD.lease_millis, OLD.backoff_millis, OLD.deadline_at)
            OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION 'Provisioning identity, limits or terminal fence rejected' USING ERRCODE = '23514';
        END IF;
        IF NEW.phase = 'CLAIMED' THEN
            IF NEW.attempt_count <> OLD.attempt_count + 1 OR NEW.last_attempt_id IS NOT DISTINCT FROM OLD.last_attempt_id
                OR NEW.attempt_count > NEW.max_attempts OR NEW.attempt_count > NEW.max_stalled_attempts THEN
                RAISE EXCEPTION 'Provisioning claim count or attempt fence rejected' USING ERRCODE = '23514';
            END IF;
        ELSIF (NEW.attempt_count, NEW.last_attempt_id, NEW.subscription_row_version, NEW.lease_expires_at)
            IS DISTINCT FROM (OLD.attempt_count, OLD.last_attempt_id, OLD.subscription_row_version, OLD.lease_expires_at) THEN
            RAISE EXCEPTION 'Provisioning settlement cannot replace its attempt' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_first_invoice_link_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_row subscription%ROWTYPE; invoice subscription_first_invoices%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices WHERE subscription_id = NEW.subscription_id;
    IF current_row.provider_subscription_id IS DISTINCT FROM invoice.provider_subscription_id THEN
        RAISE EXCEPTION 'First-invoice linkage requires exact current canonical provider' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER first_invoice_link AFTER INSERT ON subscription_first_invoices
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_first_invoice_link_consistency();
CREATE FUNCTION account_first_invoice_dispatch_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE consent subscription_first_invoice_confirmations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT consent FROM subscription_first_invoice_confirmations WHERE request_id = NEW.request_id;
    IF consent.subscription_id <> NEW.subscription_id OR consent.created_transaction_id <> txid_current()
        OR NEW.authorized_at < consent.consented_at OR NEW.authorized_at >= consent.valid_until THEN
        RAISE EXCEPTION 'Initial payment dispatch must commit atomically with original current exact consent' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_dispatch BEFORE INSERT ON subscription_first_invoice_dispatches
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_dispatch_guard();
CREATE TRIGGER subscription_first_invoice_dispatches_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_dispatches
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_first_invoice_dispatches_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_dispatches
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE FUNCTION account_first_invoice_success_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE intake stripe_event_inbox%ROWTYPE; invoice subscription_first_invoices%ROWTYPE; payload JSONB;
BEGIN
    SELECT * INTO STRICT intake FROM stripe_event_inbox WHERE provider_event_id = NEW.provider_event_id;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices WHERE subscription_id = NEW.subscription_id;
    payload := convert_from(intake.raw_payload, 'UTF8')::JSONB #> '{data,object}';
    IF intake.event_type <> 'payment_intent.succeeded' OR intake.payload_sha256 <> NEW.event_payload_digest
        OR intake.livemode <> invoice.livemode OR NEW.tuple_digest <> invoice.tuple_digest
        OR payload->>'object' IS DISTINCT FROM 'payment_intent' OR payload->>'status' IS DISTINCT FROM 'succeeded'
        OR payload->>'id' IS DISTINCT FROM invoice.payment_intent_id OR payload->>'customer' IS DISTINCT FROM invoice.provider_customer_id
        OR payload->>'payment_method' IS DISTINCT FROM invoice.provider_payment_method_id OR payload->>'currency' IS DISTINCT FROM invoice.currency
        OR payload->'amount' IS DISTINCT FROM to_jsonb(invoice.amount) OR payload->'amount_received' IS DISTINCT FROM to_jsonb(invoice.amount)
        OR payload->'livemode' IS DISTINCT FROM to_jsonb(invoice.livemode) THEN
        RAISE EXCEPTION 'First invoice requires exact signed successful payment tuple, not invoice.paid' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_success BEFORE INSERT ON subscription_first_invoice_success_evidence
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_success_guard();
CREATE FUNCTION account_first_invoice_active_receipt_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('trialing','active','past_due','grace') AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_completions WHERE subscription_id = NEW.subscription_id)
        AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence e
            JOIN subscription_first_invoice_confirmations c USING (request_id, subscription_id)
            WHERE c.subscription_id = NEW.subscription_id AND c.lifecycle_request_id = NEW.request_id) THEN
        RAISE EXCEPTION 'First authorizing hosting receipt requires exact signed checkout completion evidence' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_active_receipt BEFORE INSERT ON subscription_lifecycle_receipts
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_active_receipt_guard();
-- [jooq ignore stop]
COMMENT ON TABLE subscription_first_invoices IS 'Immutable provider-linked awaiting-payment evidence only; does not authorize hosting. Retention/deletion/backup registration gates remain held.';
