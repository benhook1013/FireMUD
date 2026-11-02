-- Account UUID billing prerequisites only. No legacy-row promotion, provider work, subscription,
-- default instrument, reservation, detach, charge or entitlement is created by this migration.
CREATE TABLE billing_customer (
    account_uuid UUID PRIMARY KEY REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    customer_provisioning_request_id UUID NOT NULL UNIQUE
        CHECK (customer_provisioning_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    provider_idempotency_id UUID NOT NULL UNIQUE
        CHECK (provider_idempotency_id <> '00000000-0000-0000-0000-000000000000'::UUID)
        CHECK (provider_idempotency_id <> customer_provisioning_request_id),
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('UNCONFIRMED', 'RECONCILING', 'CONFIRMED')),
    provider_customer_id VARCHAR(255) UNIQUE CHECK (provider_customer_id ~ '^cus_[A-Za-z0-9]+$'),
    change_request_id UUID NOT NULL
        CHECK (change_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    observation VARCHAR(24) NOT NULL
        CHECK (observation IN ('NONE', 'AMBIGUOUS', 'PROVIDER_ABSENT', 'CONFIRMED')),
    evidence_digest VARCHAR(71) CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT billing_customer_observation_shape CHECK (
        (state = 'UNCONFIRMED' AND observation = 'NONE' AND evidence_digest IS NULL
            AND provider_customer_id IS NULL)
        OR (state = 'RECONCILING' AND observation IN ('AMBIGUOUS', 'PROVIDER_ABSENT')
            AND evidence_digest IS NOT NULL AND provider_customer_id IS NULL)
        OR (state = 'CONFIRMED' AND observation = 'CONFIRMED' AND evidence_digest IS NOT NULL
            AND provider_customer_id IS NOT NULL)),
    CONSTRAINT billing_customer_exact_binding UNIQUE (
        account_uuid, customer_provisioning_request_id, provider_customer_id)
);

-- Every observation, including the original unconfirmed intent, is retained. The immutable
-- operation/provider identities remain on the undeletable owner row referenced by this history.
CREATE TABLE account_billing_customer_observations (
    account_uuid UUID NOT NULL REFERENCES billing_customer(account_uuid) ON DELETE RESTRICT,
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    request_id UUID NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL,
    provider_customer_id VARCHAR(255),
    observation VARCHAR(24) NOT NULL,
    evidence_digest VARCHAR(71),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_uuid, row_version)
);

CREATE TABLE payment_instrument (
    instrument_uuid UUID PRIMARY KEY
        CHECK (instrument_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    customer_provisioning_request_id UUID NOT NULL,
    provider_customer_id VARCHAR(255) NOT NULL,
    provider_payment_method_id VARCHAR(255) NOT NULL UNIQUE
        CHECK (provider_payment_method_id ~ '^pm_[A-Za-z0-9]+$'),
    attachment_request_id UUID NOT NULL UNIQUE
        CHECK (attachment_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ATTACHABLE', 'UNAVAILABLE')),
    -- Only provider-approved display fields, never raw credentials or an arbitrary metadata blob.
    display_brand VARCHAR(32) CHECK (display_brand ~ '^[a-z][a-z_]{0,31}$'),
    display_last_four VARCHAR(4) CHECK (display_last_four ~ '^[0-9]{4}$'),
    display_expiry_month INTEGER CHECK (display_expiry_month BETWEEN 1 AND 12),
    display_expiry_year INTEGER CHECK (display_expiry_year BETWEEN 2000 AND 9999),
    change_request_id UUID NOT NULL
        CHECK (change_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT payment_instrument_expiry_pair CHECK (
        (display_expiry_month IS NULL) = (display_expiry_year IS NULL)),
    -- A non-null provider customer can exist only on the exact CONFIRMED Account customer row.
    CONSTRAINT payment_instrument_confirmed_customer_fk FOREIGN KEY (
        account_uuid, customer_provisioning_request_id, provider_customer_id)
        REFERENCES billing_customer(account_uuid, customer_provisioning_request_id, provider_customer_id)
        ON DELETE RESTRICT
);
CREATE INDEX idx_payment_instrument_account ON payment_instrument(account_uuid, instrument_uuid);

CREATE TABLE account_payment_instrument_observations (
    instrument_uuid UUID NOT NULL REFERENCES payment_instrument(instrument_uuid) ON DELETE RESTRICT,
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    request_id UUID NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL,
    display_brand VARCHAR(32),
    display_last_four VARCHAR(4),
    display_expiry_month INTEGER,
    display_expiry_year INTEGER,
    evidence_digest VARCHAR(71) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (instrument_uuid, row_version)
);

-- [jooq ignore start]
CREATE FUNCTION account_billing_prerequisite_no_erasure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Account billing prerequisite evidence is retained' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_billing_customer_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Billing customer intent cannot be deleted' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.row_version <> 1 OR NEW.state <> 'UNCONFIRMED'
            OR NEW.change_request_id IS DISTINCT FROM NEW.customer_provisioning_request_id THEN
            RAISE EXCEPTION 'Billing customer must begin as one unconfirmed intent' USING ERRCODE = '23514';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
    ELSE
        IF (NEW.account_uuid, NEW.customer_provisioning_request_id, NEW.provider_idempotency_id, NEW.created_at)
            IS DISTINCT FROM
            (OLD.account_uuid, OLD.customer_provisioning_request_id, OLD.provider_idempotency_id, OLD.created_at)
            OR OLD.state = 'CONFIRMED' OR NEW.state NOT IN ('RECONCILING', 'CONFIRMED')
            OR NEW.row_version <> OLD.row_version + 1
            OR NEW.change_request_id = OLD.change_request_id
            OR NEW.change_request_id = NEW.provider_idempotency_id THEN
            RAISE EXCEPTION 'Billing customer identity, confirmation or version fence rejected' USING ERRCODE = '23514';
        END IF;
        NEW.updated_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_billing_customer_record_observation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO account_billing_customer_observations VALUES (
        NEW.account_uuid, NEW.row_version, NEW.change_request_id, NEW.state,
        NEW.provider_customer_id, NEW.observation, NEW.evidence_digest, NEW.updated_at);
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_billing_customer_observation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_row billing_customer%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM billing_customer WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF (NEW.row_version, NEW.request_id, NEW.state, NEW.provider_customer_id,
        NEW.observation, NEW.evidence_digest, NEW.recorded_at) IS DISTINCT FROM
       (current_row.row_version, current_row.change_request_id, current_row.state,
        current_row.provider_customer_id, current_row.observation, current_row.evidence_digest, current_row.updated_at) THEN
        RAISE EXCEPTION 'Billing customer observation differs from owner transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_payment_instrument_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment instrument cannot be deleted or detached' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.row_version <> 1 OR NEW.change_request_id IS DISTINCT FROM NEW.attachment_request_id THEN
            RAISE EXCEPTION 'Payment instrument requires its original attachment receipt' USING ERRCODE = '23514';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
    ELSE
        IF (NEW.instrument_uuid, NEW.account_uuid, NEW.customer_provisioning_request_id,
            NEW.provider_customer_id, NEW.provider_payment_method_id, NEW.attachment_request_id, NEW.created_at)
            IS DISTINCT FROM
           (OLD.instrument_uuid, OLD.account_uuid, OLD.customer_provisioning_request_id,
            OLD.provider_customer_id, OLD.provider_payment_method_id, OLD.attachment_request_id, OLD.created_at)
            OR NEW.row_version <> OLD.row_version + 1 OR NEW.change_request_id = OLD.change_request_id THEN
            RAISE EXCEPTION 'Payment instrument identity, ownership or version fence rejected' USING ERRCODE = '23514';
        END IF;
        NEW.updated_at := clock_timestamp();
    END IF;
    -- DETACHED is deliberately not a legal state. A future genuine reservation/reference owner
    -- must fence detach before any such transition or API is introduced.
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_payment_instrument_record_observation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO account_payment_instrument_observations VALUES (
        NEW.instrument_uuid, NEW.row_version, NEW.change_request_id, NEW.state,
        NEW.display_brand, NEW.display_last_four, NEW.display_expiry_month, NEW.display_expiry_year,
        NEW.evidence_digest, NEW.updated_at);
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_payment_instrument_observation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_row payment_instrument%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM payment_instrument WHERE instrument_uuid = NEW.instrument_uuid FOR UPDATE;
    IF (NEW.row_version, NEW.request_id, NEW.state, NEW.display_brand, NEW.display_last_four,
        NEW.display_expiry_month, NEW.display_expiry_year, NEW.evidence_digest, NEW.recorded_at) IS DISTINCT FROM
       (current_row.row_version, current_row.change_request_id, current_row.state,
        current_row.display_brand, current_row.display_last_four, current_row.display_expiry_month,
        current_row.display_expiry_year, current_row.evidence_digest, current_row.updated_at) THEN
        RAISE EXCEPTION 'Payment instrument observation differs from owner transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER billing_customer_guard BEFORE INSERT OR UPDATE OR DELETE ON billing_customer
    FOR EACH ROW EXECUTE FUNCTION account_billing_customer_guard();
CREATE TRIGGER billing_customer_observation AFTER INSERT OR UPDATE ON billing_customer
    FOR EACH ROW EXECUTE FUNCTION account_billing_customer_record_observation();
CREATE TRIGGER billing_customer_no_truncate BEFORE TRUNCATE ON billing_customer
    FOR EACH STATEMENT EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
CREATE TRIGGER billing_customer_observation_guard BEFORE INSERT ON account_billing_customer_observations
    FOR EACH ROW EXECUTE FUNCTION account_billing_customer_observation_guard();
CREATE TRIGGER billing_customer_observation_immutable BEFORE UPDATE OR DELETE ON account_billing_customer_observations
    FOR EACH ROW EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
CREATE TRIGGER billing_customer_observation_no_truncate BEFORE TRUNCATE ON account_billing_customer_observations
    FOR EACH STATEMENT EXECUTE FUNCTION account_billing_prerequisite_no_erasure();

CREATE TRIGGER payment_instrument_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_instrument
    FOR EACH ROW EXECUTE FUNCTION account_payment_instrument_guard();
CREATE TRIGGER payment_instrument_observation AFTER INSERT OR UPDATE ON payment_instrument
    FOR EACH ROW EXECUTE FUNCTION account_payment_instrument_record_observation();
CREATE TRIGGER payment_instrument_no_truncate BEFORE TRUNCATE ON payment_instrument
    FOR EACH STATEMENT EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
CREATE TRIGGER payment_instrument_observation_guard BEFORE INSERT ON account_payment_instrument_observations
    FOR EACH ROW EXECUTE FUNCTION account_payment_instrument_observation_guard();
CREATE TRIGGER payment_instrument_observation_immutable BEFORE UPDATE OR DELETE ON account_payment_instrument_observations
    FOR EACH ROW EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
CREATE TRIGGER payment_instrument_observation_no_truncate BEFORE TRUNCATE ON account_payment_instrument_observations
    FOR EACH STATEMENT EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
-- [jooq ignore stop]
