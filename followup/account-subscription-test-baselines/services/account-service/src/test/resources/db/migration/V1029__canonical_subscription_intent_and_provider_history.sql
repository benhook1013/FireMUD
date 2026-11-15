-- One new, initially empty UUID owner. Numeric evidence remains untouched in the V1028 archive.
-- No provider dispatch, verified lifecycle producer, transfer, entitlement or runtime grant.
CREATE TABLE subscription_create_operations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    subscription_id UUID NOT NULL UNIQUE CHECK (subscription_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    tenant_id UUID NOT NULL REFERENCES account_tenant_billing_owners(tenant_uuid) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    owner_version BIGINT NOT NULL CHECK (owner_version > 0),
    owner_generation UUID NOT NULL CHECK (owner_generation <> '00000000-0000-0000-0000-000000000000'::UUID),
    actor_account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    authorization_operation_id UUID NOT NULL CHECK (authorization_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    authorization_digest VARCHAR(71) NOT NULL CHECK (authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    plan_code VARCHAR(255) NOT NULL CHECK (length(btrim(plan_code)) > 0 AND plan_code = btrim(plan_code)),
    plan_quote_payload BYTEA NOT NULL CHECK (octet_length(plan_quote_payload) BETWEEN 1 AND 4096),
    plan_quote_digest VARCHAR(71) NOT NULL CHECK (plan_quote_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (plan_quote_digest = 'sha256:' || encode(sha256(plan_quote_payload), 'hex')),
    provider_price_id VARCHAR(255) NOT NULL CHECK (provider_price_id ~ '^price_[A-Za-z0-9]+$'),
    provider_quantity BIGINT NOT NULL CHECK (provider_quantity > 0),
    max_active_sessions BIGINT NOT NULL CHECK (max_active_sessions >= 0),
    max_concurrent_game_instances BIGINT NOT NULL CHECK (max_concurrent_game_instances >= 0),
    max_storage_bytes BIGINT NOT NULL CHECK (max_storage_bytes >= 0),
    payment_instrument_id UUID NOT NULL REFERENCES payment_instrument(instrument_uuid) ON DELETE RESTRICT,
    reserved_instrument_version BIGINT NOT NULL CHECK (reserved_instrument_version > 0),
    customer_provisioning_request_id UUID NOT NULL REFERENCES billing_customer(customer_provisioning_request_id) ON DELETE RESTRICT,
    provider_customer_id VARCHAR(255) NOT NULL,
    subscription_provisioning_request_id UUID NOT NULL UNIQUE
        CHECK (subscription_provisioning_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    cleanup_provider_identity UUID NOT NULL UNIQUE
        CHECK (cleanup_provider_identity <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) BETWEEN 1 AND 1048576),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    replaced_subscription_id UUID,
    replaced_row_version BIGINT CHECK (replaced_row_version > 0),
    -- The immutable creation result is these columns, not a later mutable lifecycle snapshot.
    result_status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (result_status = 'pending'),
    result_row_version BIGINT NOT NULL DEFAULT 1 CHECK (result_row_version = 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((replaced_subscription_id IS NULL) = (replaced_row_version IS NULL)),
    CHECK (request_id <> subscription_provisioning_request_id AND request_id <> cleanup_provider_identity
        AND subscription_provisioning_request_id <> cleanup_provider_identity
        AND customer_provisioning_request_id NOT IN (request_id, subscription_provisioning_request_id, cleanup_provider_identity)),
    UNIQUE (request_id, subscription_id),
    UNIQUE (request_id, plan_quote_digest),
    UNIQUE (request_id, subscription_provisioning_request_id),
    UNIQUE (request_id, cleanup_provider_identity),
    UNIQUE (request_id, subscription_id, payment_instrument_id, reserved_instrument_version),
    FOREIGN KEY (account_uuid, customer_provisioning_request_id, provider_customer_id)
        REFERENCES billing_customer(account_uuid, customer_provisioning_request_id, provider_customer_id) ON DELETE RESTRICT
);

CREATE TABLE subscription (
    subscription_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES account_tenant_billing_owners(tenant_uuid) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    owner_version BIGINT NOT NULL CHECK (owner_version > 0),
    owner_generation UUID NOT NULL,
    plan_code VARCHAR(255) NOT NULL,
    -- The exact current quote is retained by the immutable operation, not reconstructed from a
    -- mutable plan catalog. This digest couples the current read to that original snapshot.
    plan_quote_digest VARCHAR(71) NOT NULL,
    payment_instrument_id UUID NOT NULL REFERENCES payment_instrument(instrument_uuid) ON DELETE RESTRICT,
    reserved_instrument_version BIGINT NOT NULL CHECK (reserved_instrument_version > 0),
    customer_provisioning_request_id UUID NOT NULL REFERENCES billing_customer(customer_provisioning_request_id) ON DELETE RESTRICT,
    provider_customer_id VARCHAR(255) NOT NULL,
    creation_request_id UUID NOT NULL UNIQUE,
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending','provisioning','trialing','active','past_due','grace','suspended','canceled')),
    provider_subscription_id VARCHAR(255) CHECK (provider_subscription_id ~ '^sub_[A-Za-z0-9]+$'),
    terminal_resolution_id UUID,
    superseded_at TIMESTAMPTZ,
    superseded_by_request_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (status NOT IN ('trialing','active','past_due','grace','suspended') OR provider_subscription_id IS NOT NULL),
    -- Effective hosting cutoff and provider retirement are separate: a scheduled end may close
    -- hosting before a provider-terminal read permits retiring the instrument reservation.
    CHECK (terminal_resolution_id IS NULL OR status = 'canceled'),
    CHECK ((superseded_at IS NULL) = (superseded_by_request_id IS NULL)),
    CHECK (superseded_at IS NULL OR status = 'canceled'),
    UNIQUE (creation_request_id, subscription_id),
    FOREIGN KEY (creation_request_id, subscription_id)
        REFERENCES subscription_create_operations(request_id, subscription_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (creation_request_id, plan_quote_digest)
        REFERENCES subscription_create_operations(request_id, plan_quote_digest) ON DELETE RESTRICT,
    FOREIGN KEY (superseded_by_request_id) REFERENCES subscription_create_operations(request_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE subscription_create_operations ADD CONSTRAINT subscription_create_current_pair_fk
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription(creation_request_id, subscription_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE subscription_create_operations ADD CONSTRAINT subscription_create_replaced_fk
    FOREIGN KEY (replaced_subscription_id) REFERENCES subscription(subscription_id) ON DELETE RESTRICT;

-- A shared key registry prevents even cross-role reuse between different subscription creates.
CREATE TABLE subscription_create_provider_identities (
    provider_identity UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES subscription_create_operations(request_id) ON DELETE RESTRICT,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('CREATE','CLEANUP')),
    UNIQUE (request_id, purpose)
);

CREATE TABLE subscription_instrument_reservations (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    payment_instrument_id UUID NOT NULL,
    instrument_version BIGINT NOT NULL CHECK (instrument_version > 0),
    terminal_resolution_id UUID UNIQUE,
    released_at TIMESTAMPTZ,
    CHECK ((terminal_resolution_id IS NULL) = (released_at IS NULL)),
    FOREIGN KEY (request_id, subscription_id, payment_instrument_id, instrument_version)
        REFERENCES subscription_create_operations(request_id, subscription_id, payment_instrument_id, reserved_instrument_version)
        ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id)
);
CREATE INDEX idx_subscription_instrument_hold ON subscription_instrument_reservations(payment_instrument_id)
    WHERE released_at IS NULL;

-- An immutable local work intent, NOT a dispatched item or lifecycle-event authority bridge.
CREATE TABLE subscription_create_outbox (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    work_kind VARCHAR(40) NOT NULL CHECK (work_kind = 'SUBSCRIPTION_PROVISIONING_INTENT'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_create_operations(request_id, subscription_id) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id)
);
ALTER TABLE subscription_create_operations ADD CONSTRAINT subscription_create_reservation_pair_fk
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_instrument_reservations(request_id, subscription_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE subscription_create_operations ADD CONSTRAINT subscription_create_outbox_pair_fk
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_create_outbox(request_id, subscription_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE subscription_provider_history (
    provider_subscription_id VARCHAR(255) PRIMARY KEY CHECK (provider_subscription_id ~ '^sub_[A-Za-z0-9]+$'),
    subscription_id UUID NOT NULL REFERENCES subscription(subscription_id) ON DELETE RESTRICT,
    operation_kind VARCHAR(32) NOT NULL CHECK (operation_kind = 'SUBSCRIPTION_PROVISIONING'),
    request_id UUID NOT NULL,
    provider_operation_id UUID NOT NULL,
    -- Transfer roles/references require the genuine transfer owner, not an invented operation.
    role VARCHAR(16) NOT NULL CHECK (role = 'canonical'),
    effective_at TIMESTAMPTZ NOT NULL,
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (provider_subscription_id, subscription_id),
    UNIQUE (request_id),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_create_operations(request_id, subscription_id) ON DELETE RESTRICT,
    FOREIGN KEY (request_id, provider_operation_id)
        REFERENCES subscription_create_operations(request_id, subscription_provisioning_request_id) ON DELETE RESTRICT
);
ALTER TABLE subscription ADD CONSTRAINT subscription_confirmed_provider_history_fk
    FOREIGN KEY (provider_subscription_id, subscription_id)
    REFERENCES subscription_provider_history(provider_subscription_id, subscription_id) DEFERRABLE INITIALLY DEFERRED;

-- Separate immutable terminal evidence; inserting a history mapping cannot imply cancellation.
-- No public writer exists until provider reconciliation and local cleanup are genuinely composed.
CREATE TABLE subscription_provider_terminal_resolutions (
    resolution_id UUID PRIMARY KEY CHECK (resolution_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_id UUID NOT NULL UNIQUE,
    subscription_id UUID NOT NULL UNIQUE,
    cleanup_provider_identity UUID NOT NULL,
    provider_subscription_id VARCHAR(255),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('PROVIDER_ABSENT','CANCELED')),
    effective_at TIMESTAMPTZ NOT NULL,
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((outcome = 'PROVIDER_ABSENT') = (provider_subscription_id IS NULL)),
    UNIQUE (resolution_id, subscription_id),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_create_operations(request_id, subscription_id) ON DELETE RESTRICT,
    FOREIGN KEY (request_id, cleanup_provider_identity)
        REFERENCES subscription_create_operations(request_id, cleanup_provider_identity) ON DELETE RESTRICT,
    FOREIGN KEY (provider_subscription_id, subscription_id)
        REFERENCES subscription_provider_history(provider_subscription_id, subscription_id) ON DELETE RESTRICT
);
ALTER TABLE subscription ADD CONSTRAINT subscription_terminal_resolution_fk
    FOREIGN KEY (terminal_resolution_id, subscription_id)
    REFERENCES subscription_provider_terminal_resolutions(resolution_id, subscription_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE subscription_instrument_reservations ADD CONSTRAINT subscription_reservation_resolution_fk
    FOREIGN KEY (terminal_resolution_id, subscription_id)
    REFERENCES subscription_provider_terminal_resolutions(resolution_id, subscription_id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE subscription_current_observations (
    subscription_id UUID NOT NULL REFERENCES subscription(subscription_id) ON DELETE RESTRICT,
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    status VARCHAR(16) NOT NULL,
    provider_subscription_id VARCHAR(255),
    terminal_resolution_id UUID,
    superseded_at TIMESTAMPTZ,
    superseded_by_request_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subscription_id, row_version)
);

-- [jooq ignore start]
-- The canonical relation is new and empty. No old numeric row is a duplicate candidate/winner.
-- Fence this new owner while checking the exact index candidates, not another schema catalog.
LOCK TABLE subscription IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT tenant_id FROM subscription WHERE superseded_at IS NULL GROUP BY tenant_id HAVING count(*) > 1)
        OR EXISTS (SELECT provider_subscription_id FROM subscription
            WHERE superseded_at IS NULL AND provider_subscription_id IS NOT NULL
            GROUP BY provider_subscription_id HAVING count(*) <> 1) THEN
        RAISE EXCEPTION 'Canonical subscription candidates require explicit reconciliation';
    END IF;
END;
$$;
-- [jooq ignore stop]
CREATE UNIQUE INDEX uq_subscription_active_tenant ON subscription(tenant_id) WHERE superseded_at IS NULL;
CREATE UNIQUE INDEX uq_subscription_active_provider ON subscription(provider_subscription_id)
    WHERE superseded_at IS NULL AND provider_subscription_id IS NOT NULL;

-- [jooq ignore start]
CREATE FUNCTION account_subscription_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Canonical subscription evidence is retained and immutable' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_subscription_create_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    wallet payment_instrument%ROWTYPE;
    customer billing_customer%ROWTYPE;
    owner account_tenant_billing_owners%ROWTYPE;
    previous subscription%ROWTYPE;
BEGIN
    -- Same lock order as V1027 repositories: Account -> customer -> instrument -> tenant owner.
    PERFORM 1 FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    SELECT * INTO STRICT customer FROM billing_customer WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    SELECT * INTO STRICT wallet FROM payment_instrument WHERE instrument_uuid = NEW.payment_instrument_id FOR UPDATE;
    SELECT * INTO STRICT owner FROM account_tenant_billing_owners WHERE tenant_uuid = NEW.tenant_id FOR UPDATE;
    IF convert_from(NEW.plan_quote_payload, 'UTF8')::jsonb IS DISTINCT FROM jsonb_build_object(
        'schemaVersion', 'account-hosting-plan-quote/v1', 'planCode', NEW.plan_code,
        'providerPriceId', NEW.provider_price_id, 'providerQuantity', NEW.provider_quantity::text,
        'maxActiveSessions', NEW.max_active_sessions::text,
        'maxConcurrentGameInstances', NEW.max_concurrent_game_instances::text,
        'maxStorageBytes', NEW.max_storage_bytes::text) THEN
        RAISE EXCEPTION 'Subscription hosting quote differs from its exact retained fields' USING ERRCODE = '23514';
    END IF;
    IF (owner.owner_account_uuid, owner.owner_version, owner.owner_generation)
            IS DISTINCT FROM (NEW.account_uuid, NEW.owner_version, NEW.owner_generation)
        OR (wallet.account_uuid, wallet.row_version, wallet.customer_provisioning_request_id, wallet.provider_customer_id)
            IS DISTINCT FROM (NEW.account_uuid, NEW.reserved_instrument_version, NEW.customer_provisioning_request_id, NEW.provider_customer_id)
        OR wallet.state <> 'ATTACHABLE' OR customer.state <> 'CONFIRMED'
        OR customer.provider_customer_id IS DISTINCT FROM NEW.provider_customer_id
        OR customer.provider_idempotency_id IN (NEW.request_id, NEW.subscription_provisioning_request_id, NEW.cleanup_provider_identity)
        OR convert_from(NEW.request_payload, 'UTF8')::jsonb IS DISTINCT FROM jsonb_build_object(
            'schemaVersion', 'account-subscription-create-request/v1', 'tenantId', NEW.tenant_id::text,
            'ownerAccountId', NEW.account_uuid::text, 'planCode', NEW.plan_code,
            'instrumentSelection', jsonb_build_object('kind', 'EXACT_SAVED_INSTRUMENT', 'paymentInstrumentId', NEW.payment_instrument_id::text)) THEN
        RAISE EXCEPTION 'Subscription create owner, wallet or normalized request fence rejected' USING ERRCODE = '23514';
    END IF;
    IF NEW.replaced_subscription_id IS NOT NULL THEN
        SELECT * INTO STRICT previous FROM subscription WHERE subscription_id = NEW.replaced_subscription_id FOR UPDATE;
        IF previous.tenant_id <> NEW.tenant_id OR previous.status <> 'canceled'
            OR previous.terminal_resolution_id IS NULL
            OR previous.row_version <> NEW.replaced_row_version + 1
            OR previous.superseded_by_request_id IS DISTINCT FROM NEW.request_id THEN
            RAISE EXCEPTION 'Canceled subscription replacement fence rejected' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM subscription WHERE tenant_id = NEW.tenant_id AND superseded_at IS NULL) THEN
        RAISE EXCEPTION 'Tenant already has a current subscription' USING ERRCODE = '23514';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_record_provider_identities() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO subscription_create_provider_identities VALUES (NEW.subscription_provisioning_request_id, NEW.request_id, 'CREATE');
    INSERT INTO subscription_create_provider_identities VALUES (NEW.cleanup_provider_identity, NEW.request_id, 'CLEANUP');
    RETURN NEW;
END;
$$;
CREATE FUNCTION account_subscription_provider_identity_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM subscription_create_operations WHERE request_id = NEW.request_id
        AND ((NEW.purpose = 'CREATE' AND subscription_provisioning_request_id = NEW.provider_identity)
            OR (NEW.purpose = 'CLEANUP' AND cleanup_provider_identity = NEW.provider_identity))) THEN
        RAISE EXCEPTION 'Subscription provider identity differs from its typed operation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_current_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    intent subscription_create_operations%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Canonical subscription cannot be deleted' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        SELECT * INTO STRICT intent FROM subscription_create_operations WHERE request_id = NEW.creation_request_id;
        IF (NEW.subscription_id, NEW.tenant_id, NEW.account_uuid, NEW.owner_version, NEW.owner_generation,
            NEW.plan_code, NEW.plan_quote_digest, NEW.payment_instrument_id, NEW.reserved_instrument_version,
            NEW.customer_provisioning_request_id, NEW.provider_customer_id)
            IS DISTINCT FROM (intent.subscription_id, intent.tenant_id, intent.account_uuid, intent.owner_version,
                intent.owner_generation, intent.plan_code, intent.plan_quote_digest, intent.payment_instrument_id, intent.reserved_instrument_version,
                intent.customer_provisioning_request_id, intent.provider_customer_id)
            OR NEW.row_version <> 1 OR NEW.status <> 'pending' OR NEW.provider_subscription_id IS NOT NULL
            OR NEW.terminal_resolution_id IS NOT NULL OR NEW.superseded_at IS NOT NULL THEN
            RAISE EXCEPTION 'Subscription must begin as its exact pending intent' USING ERRCODE = '23514';
        END IF;
        NEW.created_at := intent.created_at;
        NEW.updated_at := NEW.created_at;
    ELSE
        -- No generic plan/instrument/owner rebind. Future typed update/transfer owners must extend
        -- this guard with their real operation and authority evidence, never a compatibility path.
        IF (NEW.subscription_id, NEW.tenant_id, NEW.account_uuid, NEW.owner_version, NEW.owner_generation,
            NEW.plan_code, NEW.plan_quote_digest, NEW.payment_instrument_id, NEW.reserved_instrument_version,
            NEW.customer_provisioning_request_id, NEW.provider_customer_id, NEW.creation_request_id, NEW.created_at)
            IS DISTINCT FROM (OLD.subscription_id, OLD.tenant_id, OLD.account_uuid, OLD.owner_version, OLD.owner_generation,
                OLD.plan_code, OLD.plan_quote_digest, OLD.payment_instrument_id, OLD.reserved_instrument_version,
                OLD.customer_provisioning_request_id, OLD.provider_customer_id, OLD.creation_request_id, OLD.created_at)
            OR OLD.superseded_at IS NOT NULL OR NEW.row_version <> OLD.row_version + 1
            OR (OLD.provider_subscription_id IS NOT NULL AND NEW.provider_subscription_id IS DISTINCT FROM OLD.provider_subscription_id)
            OR (OLD.terminal_resolution_id IS NOT NULL AND NEW.terminal_resolution_id IS DISTINCT FROM OLD.terminal_resolution_id)
            OR (OLD.status = 'canceled' AND NEW.status <> 'canceled') THEN
            RAISE EXCEPTION 'Subscription identity, terminal state or row-version fence rejected' USING ERRCODE = '23514';
        END IF;
        IF NEW.superseded_at IS NOT NULL THEN
            IF OLD.status <> 'canceled' OR OLD.terminal_resolution_id IS NULL OR NEW.status <> OLD.status
                OR NEW.provider_subscription_id IS DISTINCT FROM OLD.provider_subscription_id
                OR NEW.terminal_resolution_id IS DISTINCT FROM OLD.terminal_resolution_id THEN
                RAISE EXCEPTION 'Only terminal canceled current subscriptions may be superseded' USING ERRCODE = '23514';
            END IF;
            NEW.superseded_at := clock_timestamp();
        END IF;
        NEW.updated_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_record_observation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO subscription_current_observations VALUES (NEW.subscription_id, NEW.row_version, NEW.status,
        NEW.provider_subscription_id, NEW.terminal_resolution_id, NEW.superseded_at, NEW.superseded_by_request_id, NEW.updated_at);
    RETURN NEW;
END;
$$;
CREATE FUNCTION account_subscription_observation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_row subscription%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    IF (NEW.row_version, NEW.status, NEW.provider_subscription_id, NEW.terminal_resolution_id,
        NEW.superseded_at, NEW.superseded_by_request_id, NEW.recorded_at) IS DISTINCT FROM
        (current_row.row_version, current_row.status, current_row.provider_subscription_id, current_row.terminal_resolution_id,
            current_row.superseded_at, current_row.superseded_by_request_id, current_row.updated_at) THEN
        RAISE EXCEPTION 'Subscription observation differs from its current owner' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_reservation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.terminal_resolution_id IS NOT NULL OR NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'Subscription reservation must begin held' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_OP = 'DELETE' OR OLD.released_at IS NOT NULL
        OR (NEW.request_id, NEW.subscription_id, NEW.payment_instrument_id, NEW.instrument_version)
            IS DISTINCT FROM (OLD.request_id, OLD.subscription_id, OLD.payment_instrument_id, OLD.instrument_version)
        OR NEW.terminal_resolution_id IS NULL OR NEW.released_at IS NULL THEN
        RAISE EXCEPTION 'Subscription reservation identity or terminal release fence rejected' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_final_pair_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_row subscription%ROWTYPE;
    hold subscription_instrument_reservations%ROWTYPE;
    resolution subscription_provider_terminal_resolutions%ROWTYPE;
    successor subscription_create_operations%ROWTYPE;
    wallet payment_instrument%ROWTYPE;
    owner account_tenant_billing_owners%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id;
    SELECT * INTO STRICT hold FROM subscription_instrument_reservations WHERE subscription_id = NEW.subscription_id;
    IF current_row.row_version = 1 AND current_row.status = 'pending' THEN
        SELECT * INTO STRICT wallet FROM payment_instrument WHERE instrument_uuid = current_row.payment_instrument_id;
        SELECT * INTO STRICT owner FROM account_tenant_billing_owners WHERE tenant_uuid = current_row.tenant_id;
        IF (owner.owner_account_uuid, owner.owner_version, owner.owner_generation)
                IS DISTINCT FROM (current_row.account_uuid, current_row.owner_version, current_row.owner_generation)
            OR (wallet.account_uuid, wallet.row_version, wallet.customer_provisioning_request_id, wallet.provider_customer_id, wallet.state)
                IS DISTINCT FROM (current_row.account_uuid, current_row.reserved_instrument_version,
                    current_row.customer_provisioning_request_id, current_row.provider_customer_id, 'ATTACHABLE'::varchar) THEN
            RAISE EXCEPTION 'Subscription commit owner/instrument fence rejected' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF current_row.status = 'canceled' AND current_row.terminal_resolution_id IS NOT NULL THEN
        SELECT * INTO STRICT resolution FROM subscription_provider_terminal_resolutions
            WHERE resolution_id = current_row.terminal_resolution_id;
        IF hold.terminal_resolution_id IS DISTINCT FROM current_row.terminal_resolution_id OR hold.released_at IS NULL
            OR resolution.provider_subscription_id IS DISTINCT FROM current_row.provider_subscription_id THEN
            RAISE EXCEPTION 'Subscription terminal result requires exact provider resolution and reservation release' USING ERRCODE = '23514';
        END IF;
    ELSIF hold.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'Nonterminal subscription must retain its instrument reservation' USING ERRCODE = '23514';
    END IF;
    IF current_row.superseded_at IS NOT NULL THEN
        SELECT * INTO STRICT successor FROM subscription_create_operations WHERE request_id = current_row.superseded_by_request_id;
        IF successor.replaced_subscription_id IS DISTINCT FROM current_row.subscription_id
            OR successor.replaced_row_version <> current_row.row_version - 1 OR successor.tenant_id <> current_row.tenant_id THEN
            RAISE EXCEPTION 'Subscription supersession requires the exact atomic replacement operation' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE FUNCTION account_subscription_terminal_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_row subscription%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    IF NEW.request_id IS DISTINCT FROM current_row.creation_request_id
        OR (NEW.provider_subscription_id IS DISTINCT FROM current_row.provider_subscription_id
            AND NOT (current_row.provider_subscription_id IS NULL AND NEW.provider_subscription_id IS NOT NULL
                AND EXISTS (SELECT 1 FROM subscription_provider_history h
                    WHERE h.subscription_id = NEW.subscription_id AND h.provider_subscription_id = NEW.provider_subscription_id)))
        OR (NEW.provider_subscription_id IS NULL AND EXISTS (
            SELECT 1 FROM subscription_provider_history WHERE subscription_id = NEW.subscription_id)) THEN
        RAISE EXCEPTION 'Subscription terminal evidence differs from its exact provider mapping' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION account_subscription_provider_history_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_row subscription%ROWTYPE;
BEGIN
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    IF current_row.status = 'canceled' OR current_row.superseded_at IS NOT NULL
        OR EXISTS (SELECT 1 FROM subscription_provider_terminal_resolutions WHERE subscription_id = NEW.subscription_id) THEN
        RAISE EXCEPTION 'Terminal subscription cannot acquire a new provider mapping' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER subscription_create_source_guard BEFORE INSERT ON subscription_create_operations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_create_source_guard();
CREATE TRIGGER subscription_create_provider_keys AFTER INSERT ON subscription_create_operations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_record_provider_identities();
CREATE TRIGGER subscription_provider_key_guard BEFORE INSERT ON subscription_create_provider_identities
    FOR EACH ROW EXECUTE FUNCTION account_subscription_provider_identity_guard();
CREATE TRIGGER subscription_current_guard BEFORE INSERT OR UPDATE OR DELETE ON subscription
    FOR EACH ROW EXECUTE FUNCTION account_subscription_current_guard();
CREATE TRIGGER subscription_current_history AFTER INSERT OR UPDATE ON subscription
    FOR EACH ROW EXECUTE FUNCTION account_subscription_record_observation();
CREATE TRIGGER subscription_observation_guard BEFORE INSERT ON subscription_current_observations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_observation_guard();
CREATE TRIGGER subscription_terminal_source_guard BEFORE INSERT ON subscription_provider_terminal_resolutions
    FOR EACH ROW EXECUTE FUNCTION account_subscription_terminal_source_guard();
CREATE TRIGGER subscription_provider_history_source_guard BEFORE INSERT ON subscription_provider_history
    FOR EACH ROW EXECUTE FUNCTION account_subscription_provider_history_source_guard();
CREATE TRIGGER subscription_reservation_guard BEFORE INSERT OR UPDATE OR DELETE ON subscription_instrument_reservations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_reservation_guard();
CREATE CONSTRAINT TRIGGER subscription_final_pair_guard AFTER INSERT OR UPDATE ON subscription
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_final_pair_guard();
CREATE CONSTRAINT TRIGGER subscription_reservation_final_pair_guard AFTER INSERT OR UPDATE ON subscription_instrument_reservations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_final_pair_guard();

CREATE TRIGGER subscription_create_immutable BEFORE UPDATE OR DELETE ON subscription_create_operations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_create_no_truncate BEFORE TRUNCATE ON subscription_create_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provider_keys_immutable BEFORE UPDATE OR DELETE ON subscription_create_provider_identities
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provider_keys_no_truncate BEFORE TRUNCATE ON subscription_create_provider_identities
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_no_truncate BEFORE TRUNCATE ON subscription
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_reservation_no_truncate BEFORE TRUNCATE ON subscription_instrument_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_outbox_immutable BEFORE UPDATE OR DELETE ON subscription_create_outbox
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_outbox_no_truncate BEFORE TRUNCATE ON subscription_create_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provider_history_immutable BEFORE UPDATE OR DELETE ON subscription_provider_history
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provider_history_no_truncate BEFORE TRUNCATE ON subscription_provider_history
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_terminal_immutable BEFORE UPDATE OR DELETE ON subscription_provider_terminal_resolutions
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_terminal_no_truncate BEFORE TRUNCATE ON subscription_provider_terminal_resolutions
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_observation_immutable BEFORE UPDATE OR DELETE ON subscription_current_observations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_observation_no_truncate BEFORE TRUNCATE ON subscription_current_observations
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
-- [jooq ignore stop]
