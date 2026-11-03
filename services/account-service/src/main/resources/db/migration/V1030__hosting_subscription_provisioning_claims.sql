-- Local durable provisioning ownership only. No runtime reader, provider dispatch or entitlement
-- receipt is enabled by these rows. Failure never proves provider absence or releases a hold.
CREATE TABLE subscription_provisioning_work (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    phase VARCHAR(48) NOT NULL CHECK (phase IN ('RECONCILING','CLAIMED','CONFIRMED','BILLING_RECONCILIATION_TERMINAL_FAILURE')),
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    max_stalled_attempts INTEGER NOT NULL CHECK (max_stalled_attempts > 0),
    lease_millis BIGINT NOT NULL CHECK (lease_millis > 0),
    backoff_millis BIGINT NOT NULL CHECK (backoff_millis > 0),
    deadline_at TIMESTAMPTZ NOT NULL,
    retry_at TIMESTAMPTZ NOT NULL,
    last_attempt_id UUID,
    subscription_row_version BIGINT CHECK (subscription_row_version > 1),
    lease_expires_at TIMESTAMPTZ,
    failure_reason VARCHAR(64),
    terminal_at TIMESTAMPTZ,
    CHECK ((attempt_count = 0) = (last_attempt_id IS NULL)),
    CHECK ((last_attempt_id IS NULL) = (subscription_row_version IS NULL)),
    CHECK ((last_attempt_id IS NULL) = (lease_expires_at IS NULL)),
    CHECK (phase <> 'CLAIMED' OR last_attempt_id IS NOT NULL),
    CHECK ((phase = 'BILLING_RECONCILIATION_TERMINAL_FAILURE') = (terminal_at IS NOT NULL)),
    CHECK (phase <> 'BILLING_RECONCILIATION_TERMINAL_FAILURE' OR failure_reason IS NOT NULL),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_create_outbox(request_id, subscription_id) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id)
);

CREATE TABLE subscription_provisioning_attempts (
    attempt_id UUID PRIMARY KEY CHECK (attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version > 0),
    claimed_row_version BIGINT NOT NULL CHECK (claimed_row_version = expected_row_version + 1),
    claimed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > claimed_at),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_provisioning_work(request_id, subscription_id) ON DELETE RESTRICT,
    UNIQUE (request_id, attempt_number),
    UNIQUE (attempt_id, request_id, subscription_id, claimed_row_version)
);
ALTER TABLE subscription_provisioning_work ADD CONSTRAINT subscription_provisioning_last_attempt_fk
    FOREIGN KEY (last_attempt_id, request_id, subscription_id, subscription_row_version)
    REFERENCES subscription_provisioning_attempts(attempt_id, request_id, subscription_id, claimed_row_version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE subscription_provisioning_operator_outbox (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    reason VARCHAR(64) NOT NULL CHECK (reason IN ('ABSOLUTE_DEADLINE','ATTEMPT_BUDGET','STALLED_WITHOUT_COMMITTED_PROGRESS')),
    recorded_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_provisioning_work(request_id, subscription_id) ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_subscription_provisioning_work_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Provisioning work is retained' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'RECONCILING' OR NEW.attempt_count <> 0 OR NEW.row_version <> 1
            OR NEW.failure_reason IS NOT NULL OR NEW.terminal_at IS NOT NULL THEN
            RAISE EXCEPTION 'Provisioning work must start unclaimed' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF OLD.phase IN ('CONFIRMED','BILLING_RECONCILIATION_TERMINAL_FAILURE')
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

CREATE FUNCTION account_subscription_provisioning_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    work subscription_provisioning_work%ROWTYPE;
    attempt subscription_provisioning_attempts%ROWTYPE;
    current_row subscription%ROWTYPE;
    notice subscription_provisioning_operator_outbox%ROWTYPE;
BEGIN
    SELECT * INTO STRICT work FROM subscription_provisioning_work WHERE request_id = NEW.request_id;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = work.subscription_id;
    IF work.last_attempt_id IS NOT NULL THEN
        SELECT * INTO STRICT attempt FROM subscription_provisioning_attempts WHERE attempt_id = work.last_attempt_id;
        IF (attempt.request_id, attempt.subscription_id, attempt.attempt_number, attempt.claimed_row_version, attempt.expires_at)
                IS DISTINCT FROM (work.request_id, work.subscription_id, work.attempt_count, work.subscription_row_version, work.lease_expires_at)
            OR attempt.expires_at > work.deadline_at
            OR (work.phase <> 'CONFIRMED' AND (current_row.status <> 'provisioning' OR current_row.row_version < attempt.claimed_row_version
                OR current_row.superseded_at IS NOT NULL)) THEN
            RAISE EXCEPTION 'Provisioning attempt lacks its exact current CAS' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF work.phase = 'BILLING_RECONCILIATION_TERMINAL_FAILURE' THEN
        SELECT * INTO STRICT notice FROM subscription_provisioning_operator_outbox WHERE request_id = work.request_id;
        IF (notice.subscription_id, notice.reason, notice.recorded_at)
                IS DISTINCT FROM (work.subscription_id, work.failure_reason, work.terminal_at) THEN
            RAISE EXCEPTION 'Provisioning failure lacks its exact operator work item' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM subscription_provisioning_operator_outbox WHERE request_id = work.request_id) THEN
        RAISE EXCEPTION 'Operator resolution work requires terminal reconciliation failure' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_subscription_provisioning_attempt_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    work subscription_provisioning_work%ROWTYPE;
    current_row subscription%ROWTYPE;
BEGIN
    SELECT * INTO STRICT work FROM subscription_provisioning_work WHERE request_id = NEW.request_id FOR UPDATE;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id;
    IF work.phase = 'BILLING_RECONCILIATION_TERMINAL_FAILURE'
        OR work.subscription_id <> NEW.subscription_id OR current_row.status <> 'provisioning'
        OR current_row.provider_subscription_id IS NOT NULL OR current_row.superseded_at IS NOT NULL
        OR current_row.row_version <> NEW.claimed_row_version
        OR NEW.attempt_number <> work.attempt_count + 1
        OR NEW.attempt_number > work.max_attempts OR NEW.attempt_number > work.max_stalled_attempts
        OR NEW.expected_row_version <> coalesce(work.subscription_row_version, 1)
        OR NEW.claimed_at < current_row.created_at OR NEW.claimed_at < work.retry_at
        OR NEW.claimed_at >= work.deadline_at OR NEW.expires_at > work.deadline_at
        OR (work.phase = 'CLAIMED' AND NEW.claimed_at < work.lease_expires_at)
        OR extract(epoch FROM (NEW.expires_at - NEW.claimed_at)) * 1000 > work.lease_millis THEN
        RAISE EXCEPTION 'Provisioning attempt lacks its exact work and current-row fence' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER subscription_provisioning_work_guard BEFORE INSERT OR UPDATE OR DELETE ON subscription_provisioning_work
    FOR EACH ROW EXECUTE FUNCTION account_subscription_provisioning_work_guard();
CREATE TRIGGER subscription_provisioning_attempt_guard BEFORE INSERT ON subscription_provisioning_attempts
    FOR EACH ROW EXECUTE FUNCTION account_subscription_provisioning_attempt_guard();
CREATE CONSTRAINT TRIGGER subscription_provisioning_work_consistency AFTER INSERT OR UPDATE ON subscription_provisioning_work
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_provisioning_consistency();
CREATE CONSTRAINT TRIGGER subscription_provisioning_attempt_consistency AFTER INSERT ON subscription_provisioning_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_provisioning_consistency();
CREATE CONSTRAINT TRIGGER subscription_provisioning_notice_consistency AFTER INSERT ON subscription_provisioning_operator_outbox
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_provisioning_consistency();
CREATE TRIGGER subscription_provisioning_work_no_truncate BEFORE TRUNCATE ON subscription_provisioning_work
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provisioning_attempt_immutable BEFORE UPDATE OR DELETE ON subscription_provisioning_attempts
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provisioning_attempt_no_truncate BEFORE TRUNCATE ON subscription_provisioning_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provisioning_notice_immutable BEFORE UPDATE OR DELETE ON subscription_provisioning_operator_outbox
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_provisioning_notice_no_truncate BEFORE TRUNCATE ON subscription_provisioning_operator_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
-- [jooq ignore stop]

-- Provider reads are outside SQL. One durable, row-version-bound reconciliation operation fences
-- currentness before the SDK read starts. It is not a provider mutation or a reused create command.
CREATE TABLE subscription_lifecycle_operations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    creation_request_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version > 0),
    reserved_row_version BIGINT NOT NULL CHECK (reserved_row_version = expected_row_version + 1),
    provider_subscription_id VARCHAR(255) NOT NULL CHECK (provider_subscription_id ~ '^sub_[A-Za-z0-9]+$'),
    provisioning_attempt_id UUID REFERENCES subscription_provisioning_attempts(attempt_id) ON DELETE RESTRICT,
    request_payload BYTEA NOT NULL,
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    state VARCHAR(48) NOT NULL CHECK (state IN ('PENDING','COMMITTED','BILLING_RECONCILIATION_TERMINAL_FAILURE')),
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    stalled_count INTEGER NOT NULL DEFAULT 0 CHECK (stalled_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    max_stalled_attempts INTEGER NOT NULL CHECK (max_stalled_attempts > 0),
    lease_millis BIGINT NOT NULL CHECK (lease_millis > 0),
    backoff_millis BIGINT NOT NULL CHECK (backoff_millis > 0),
    observation_age_millis BIGINT NOT NULL CHECK (observation_age_millis > 0),
    warning_millis BIGINT NOT NULL CHECK (warning_millis > 0),
    grace_millis BIGINT NOT NULL CHECK (grace_millis > 0),
    deadline_at TIMESTAMPTZ NOT NULL,
    retry_at TIMESTAMPTZ NOT NULL,
    last_attempt_id UUID,
    last_facts_digest VARCHAR(71),
    failure_reason VARCHAR(96),
    created_at TIMESTAMPTZ NOT NULL,
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (creation_request_id, subscription_id) REFERENCES subscription_create_operations(request_id, subscription_id) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id),
    UNIQUE (request_id, tenant_id),
    CHECK (octet_length(request_payload) BETWEEN 1 AND 32768)
);
CREATE UNIQUE INDEX subscription_lifecycle_one_inflight ON subscription_lifecycle_operations(subscription_id)
    WHERE state IN ('PENDING','BILLING_RECONCILIATION_TERMINAL_FAILURE');

CREATE TABLE subscription_lifecycle_attempts (
    attempt_id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES subscription_lifecycle_operations(request_id) ON DELETE RESTRICT,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > started_at),
    UNIQUE (request_id, attempt_number),
    UNIQUE (request_id, attempt_id)
);
ALTER TABLE subscription_lifecycle_operations ADD CONSTRAINT subscription_lifecycle_last_attempt_fk
    FOREIGN KEY (request_id, last_attempt_id) REFERENCES subscription_lifecycle_attempts(request_id, attempt_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE subscription_lifecycle_observations (
    attempt_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 32768),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest = 'sha256:' || encode(sha256(payload), 'hex')),
    facts_digest VARCHAR(71) NOT NULL CHECK (facts_digest ~ '^sha256:[0-9a-f]{64}$'),
    raw_status VARCHAR(32),
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    read_started_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    mapping_reason VARCHAR(64) NOT NULL,
    recorded_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (request_id, attempt_id) REFERENCES subscription_lifecycle_attempts(request_id, attempt_id) ON DELETE RESTRICT,
    UNIQUE (request_id, attempt_id, evidence_digest)
);

CREATE TABLE subscription_lifecycle_receipts (
    request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    subscription_row_version BIGINT NOT NULL CHECK (subscription_row_version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('trialing','active','past_due','grace','suspended','canceled')),
    plan_quote_digest VARCHAR(71) NOT NULL,
    provider_subscription_id VARCHAR(255) NOT NULL,
    attempt_id UUID NOT NULL,
    observation_digest VARCHAR(71) NOT NULL,
    receipt_payload BYTEA NOT NULL CHECK (octet_length(receipt_payload) BETWEEN 1 AND 32768),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest = 'sha256:' || encode(sha256(receipt_payload), 'hex')),
    valid_until TIMESTAMPTZ NOT NULL,
    cutoff_at TIMESTAMPTZ,
    past_due_since TIMESTAMPTZ,
    warning_millis BIGINT NOT NULL CHECK (warning_millis > 0),
    grace_millis BIGINT NOT NULL CHECK (grace_millis > 0),
    entitlement_version BIGINT NOT NULL CHECK (entitlement_version > 0),
    billing_sequence BIGINT NOT NULL CHECK (billing_sequence > 0),
    billing_event_id UUID NOT NULL,
    billing_event_digest VARCHAR(71) NOT NULL,
    authority_generation BIGINT NOT NULL CHECK (authority_generation > 1),
    authority_source_version BIGINT NOT NULL CHECK (authority_source_version > 1),
    authority_sequence BIGINT NOT NULL CHECK (authority_sequence > 0),
    authority_event_id VARCHAR(512) NOT NULL,
    authority_event_digest VARCHAR(71) NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    committed_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    FOREIGN KEY (request_id, subscription_id) REFERENCES subscription_lifecycle_operations(request_id, subscription_id) ON DELETE RESTRICT,
    FOREIGN KEY (request_id, attempt_id, observation_digest) REFERENCES subscription_lifecycle_observations(request_id, attempt_id, evidence_digest) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, billing_sequence, billing_event_id, billing_event_digest)
        REFERENCES account_tenant_entitlement_outbox_events(tenant_uuid, tenant_billing_sequence, event_id, event_digest) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id),
    UNIQUE (subscription_id, subscription_row_version)
);
CREATE TABLE subscription_lifecycle_operator_outbox (
    request_id UUID PRIMARY KEY REFERENCES subscription_lifecycle_operations(request_id) ON DELETE RESTRICT,
    reason VARCHAR(96) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE subscription ADD COLUMN lifecycle_inflight_request_id UUID,
    ADD COLUMN current_receipt_request_id UUID;
ALTER TABLE subscription ADD CONSTRAINT subscription_lifecycle_inflight_fk
    FOREIGN KEY (lifecycle_inflight_request_id, subscription_id) REFERENCES subscription_lifecycle_operations(request_id, subscription_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE subscription ADD CONSTRAINT subscription_current_receipt_fk
    FOREIGN KEY (current_receipt_request_id, subscription_id) REFERENCES subscription_lifecycle_receipts(request_id, subscription_id)
    DEFERRABLE INITIALLY DEFERRED;

-- [jooq ignore start]
CREATE FUNCTION account_subscription_lifecycle_current_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_inflight_request_id IS NOT NULL OR NEW.current_receipt_request_id IS NOT NULL THEN
            RAISE EXCEPTION 'Subscription cannot begin with lifecycle authority' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.lifecycle_inflight_request_id IS NOT NULL THEN
        IF NEW.lifecycle_inflight_request_id IS NULL THEN
            IF NEW.current_receipt_request_id IS DISTINCT FROM OLD.lifecycle_inflight_request_id THEN
                RAISE EXCEPTION 'Lifecycle fence may clear only into its exact receipt' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.lifecycle_inflight_request_id IS DISTINCT FROM OLD.lifecycle_inflight_request_id
            OR NEW.current_receipt_request_id IS DISTINCT FROM OLD.current_receipt_request_id THEN
            RAISE EXCEPTION 'In-flight lifecycle ownership cannot be replaced' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.current_receipt_request_id IS DISTINCT FROM OLD.current_receipt_request_id THEN
            RAISE EXCEPTION 'Committed current receipt cannot be detached or replaced without a lifecycle fence' USING ERRCODE = '23514';
        END IF;
        IF NEW.lifecycle_inflight_request_id IS NOT NULL
            AND (NEW.status, NEW.provider_subscription_id, NEW.terminal_resolution_id)
                IS DISTINCT FROM (OLD.status, OLD.provider_subscription_id, OLD.terminal_resolution_id) THEN
            RAISE EXCEPTION 'Lifecycle preparation cannot pre-commit provider state' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER subscription_lifecycle_current_guard BEFORE INSERT OR UPDATE ON subscription
    FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_current_guard();

CREATE FUNCTION account_subscription_lifecycle_operation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Lifecycle operation is retained' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PENDING' OR NEW.row_version <> 1 OR NEW.attempt_count <> 0 OR NEW.last_attempt_id IS NOT NULL THEN
            RAISE EXCEPTION 'Lifecycle operation must start pending' USING ERRCODE = '23514';
        END IF;
        NEW.created_transaction_id := txid_current();
    ELSE
        IF OLD.state <> 'PENDING' OR NEW.row_version <> OLD.row_version + 1
            OR (NEW.request_id, NEW.creation_request_id, NEW.subscription_id, NEW.tenant_id, NEW.expected_row_version,
                NEW.reserved_row_version, NEW.provider_subscription_id, NEW.provisioning_attempt_id, NEW.request_payload,
                NEW.request_digest, NEW.max_attempts, NEW.max_stalled_attempts, NEW.lease_millis, NEW.backoff_millis,
                NEW.observation_age_millis, NEW.warning_millis, NEW.grace_millis, NEW.deadline_at, NEW.created_at, NEW.created_transaction_id)
                IS DISTINCT FROM (OLD.request_id, OLD.creation_request_id, OLD.subscription_id, OLD.tenant_id, OLD.expected_row_version,
                    OLD.reserved_row_version, OLD.provider_subscription_id, OLD.provisioning_attempt_id, OLD.request_payload,
                    OLD.request_digest, OLD.max_attempts, OLD.max_stalled_attempts, OLD.lease_millis, OLD.backoff_millis,
                    OLD.observation_age_millis, OLD.warning_millis, OLD.grace_millis, OLD.deadline_at, OLD.created_at, OLD.created_transaction_id)
            OR NEW.attempt_count NOT BETWEEN OLD.attempt_count AND OLD.attempt_count + 1 THEN
            RAISE EXCEPTION 'Lifecycle immutable identity or transition CAS rejected' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_lifecycle_observation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt subscription_lifecycle_attempts%ROWTYPE; operation subscription_lifecycle_operations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT attempt FROM subscription_lifecycle_attempts WHERE attempt_id = NEW.attempt_id;
    SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = NEW.request_id;
    IF operation.state <> 'PENDING' OR operation.last_attempt_id IS DISTINCT FROM NEW.attempt_id
        OR NEW.read_started_at < attempt.started_at OR NEW.observed_at < NEW.read_started_at
        OR NEW.observed_at >= attempt.expires_at THEN
        RAISE EXCEPTION 'SDK observation is not from the exact live read attempt' USING ERRCODE = '23514';
    END IF;
    NEW.recorded_transaction_id := txid_current();
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_lifecycle_receipt_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE operation subscription_lifecycle_operations%ROWTYPE; observation subscription_lifecycle_observations%ROWTYPE;
    current_row subscription%ROWTYPE; creation subscription_create_operations%ROWTYPE; payload JSONB;
BEGIN
    SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = NEW.request_id;
    SELECT * INTO STRICT observation FROM subscription_lifecycle_observations WHERE attempt_id = NEW.attempt_id;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id;
    SELECT * INTO STRICT creation FROM subscription_create_operations WHERE request_id = operation.creation_request_id;
    payload := convert_from(NEW.receipt_payload, 'UTF8')::jsonb;
    IF operation.state <> 'PENDING' OR current_row.lifecycle_inflight_request_id IS DISTINCT FROM NEW.request_id
        OR current_row.row_version <> operation.reserved_row_version
        OR NEW.subscription_row_version <> current_row.row_version + 1
        OR NEW.request_digest IS DISTINCT FROM operation.request_digest OR NEW.tenant_id <> operation.tenant_id
        OR NEW.provider_subscription_id <> operation.provider_subscription_id
        OR NEW.plan_quote_digest <> creation.plan_quote_digest
        OR observation.recorded_transaction_id <> txid_current()
        OR payload->>'requestId' IS DISTINCT FROM NEW.request_id::text
        OR payload->>'subscriptionId' IS DISTINCT FROM NEW.subscription_id::text
        OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_id::text
        OR payload->>'subscriptionRowVersion' IS DISTINCT FROM NEW.subscription_row_version::text
        OR payload->>'subscriptionStatus' IS DISTINCT FROM NEW.status
        OR payload->>'planQuoteDigest' IS DISTINCT FROM NEW.plan_quote_digest
        OR payload->>'planQuote' IS DISTINCT FROM convert_from(creation.plan_quote_payload, 'UTF8')
        OR payload->>'billingOwnerAccountId' IS DISTINCT FROM creation.account_uuid::text
        OR payload->>'billingOwnerVersion' IS DISTINCT FROM creation.owner_version::text
        OR payload->>'billingOwnerGeneration' IS DISTINCT FROM creation.owner_generation::text
        OR payload->>'paymentInstrumentId' IS DISTINCT FROM creation.payment_instrument_id::text
        OR payload->>'reservedInstrumentVersion' IS DISTINCT FROM creation.reserved_instrument_version::text
        OR payload->>'customerProvisioningRequestId' IS DISTINCT FROM creation.customer_provisioning_request_id::text
        OR payload->>'providerCustomerId' IS DISTINCT FROM creation.provider_customer_id
        OR payload->>'entitlementVersion' IS DISTINCT FROM NEW.entitlement_version::text
        OR payload->>'observationDigest' IS DISTINCT FROM NEW.observation_digest
        OR payload->>'providerSubscriptionId' IS DISTINCT FROM NEW.provider_subscription_id THEN
        RAISE EXCEPTION 'Hosting receipt lacks its exact same-transaction provider/current CAS' USING ERRCODE = '23514';
    END IF;
    NEW.committed_transaction_id := txid_current();
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_subscription_lifecycle_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_row subscription%ROWTYPE; operation subscription_lifecycle_operations%ROWTYPE;
    receipt subscription_lifecycle_receipts%ROWTYPE; notice subscription_lifecycle_operator_outbox%ROWTYPE;
    source_head account_tenant_authority_source_records%ROWTYPE; generation_row RECORD;
BEGIN
    IF TG_TABLE_NAME = 'subscription' THEN
        SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id;
    ELSE
        SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = NEW.request_id;
        SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = operation.subscription_id;
        IF operation.state = 'COMMITTED' THEN
            SELECT * INTO STRICT receipt FROM subscription_lifecycle_receipts WHERE request_id = operation.request_id;
            IF EXISTS (SELECT 1 FROM subscription_lifecycle_operator_outbox WHERE request_id = operation.request_id) THEN
                RAISE EXCEPTION 'Committed lifecycle cannot carry terminal-failure work' USING ERRCODE = '23514';
            END IF;
        ELSIF operation.state = 'BILLING_RECONCILIATION_TERMINAL_FAILURE' THEN
            SELECT * INTO STRICT notice FROM subscription_lifecycle_operator_outbox WHERE request_id = operation.request_id;
            IF notice.reason IS DISTINCT FROM operation.failure_reason THEN
                RAISE EXCEPTION 'Lifecycle failure lacks its exact operator work item' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    IF current_row.lifecycle_inflight_request_id IS NOT NULL THEN
        SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = current_row.lifecycle_inflight_request_id;
        IF operation.state = 'COMMITTED' OR current_row.row_version <> operation.reserved_row_version
            OR current_row.tenant_id <> operation.tenant_id THEN
            RAISE EXCEPTION 'In-flight lifecycle operation lacks its exact current fence' USING ERRCODE = '23514';
        END IF;
    ELSIF current_row.current_receipt_request_id IS NOT NULL THEN
        SELECT * INTO STRICT receipt FROM subscription_lifecycle_receipts WHERE request_id = current_row.current_receipt_request_id;
        SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = receipt.request_id;
        IF operation.state <> 'COMMITTED' OR current_row.status <> receipt.status
            OR current_row.provider_subscription_id IS DISTINCT FROM receipt.provider_subscription_id
            OR current_row.plan_quote_digest <> receipt.plan_quote_digest
            OR (current_row.superseded_at IS NULL AND current_row.row_version <> receipt.subscription_row_version) THEN
            RAISE EXCEPTION 'Hosting current row lacks its exact committed lifecycle receipt' USING ERRCODE = '23514';
        END IF;
        IF current_row.superseded_at IS NULL THEN
            SELECT * INTO STRICT source_head FROM account_tenant_authority_source_records WHERE tenant_uuid = current_row.tenant_id;
            SELECT generation, source_version INTO STRICT generation_row FROM account_authority_generations
                WHERE scope_kind = 'TENANT' AND tenant_uuid = current_row.tenant_id;
            IF source_head.last_request_id IS DISTINCT FROM receipt.request_id
                OR source_head.last_request_digest IS DISTINCT FROM receipt.request_digest
                OR source_head.current_generation <> receipt.authority_generation
                OR source_head.current_source_version <> receipt.authority_source_version
                OR source_head.last_outbox_sequence <> receipt.authority_sequence
                OR source_head.last_event_id IS DISTINCT FROM receipt.authority_event_id
                OR source_head.last_event_digest IS DISTINCT FROM receipt.authority_event_digest
                OR source_head.tenant_billing_sequence <> receipt.billing_sequence
                OR source_head.tenant_billing_event_id IS DISTINCT FROM receipt.billing_event_id
                OR source_head.tenant_billing_event_digest IS DISTINCT FROM receipt.billing_event_digest
                OR generation_row.generation <> receipt.authority_generation OR generation_row.source_version <> receipt.authority_source_version THEN
                RAISE EXCEPTION 'Hosting current receipt requires its atomic canonical source checkpoint' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

-- Extend only the canonical source's receipt branch. Original demo receipt semantics remain intact.
CREATE OR REPLACE FUNCTION account_tenant_authority_source_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE head RECORD; generation_row RECORD; stream_sequence BIGINT; operation_row RECORD; entitlement_row RECORD;
    hosting subscription_lifecycle_receipts%ROWTYPE; current_row subscription%ROWTYPE; billing RECORD;
BEGIN
    SELECT * INTO head FROM account_tenant_authority_source_records WHERE outbox_stream_key = NEW.outbox_stream_key;
    SELECT generation, source_version INTO generation_row FROM account_authority_generations WHERE scope_kind = 'TENANT' AND tenant_uuid = NEW.tenant_uuid;
    SELECT last_sequence INTO stream_sequence FROM account_authority_outbox_streams WHERE outbox_stream_key = NEW.outbox_stream_key;
    IF head.outbox_stream_key IS NULL OR head.last_outbox_sequence <= 0
        OR head.current_generation IS DISTINCT FROM generation_row.generation
        OR head.current_source_version IS DISTINCT FROM generation_row.source_version OR stream_sequence IS DISTINCT FROM head.last_outbox_sequence THEN
        RAISE EXCEPTION 'Tenant authority source checkpoint is not the current Account generation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_consistent';
    END IF;
    SELECT * INTO hosting FROM subscription_lifecycle_receipts WHERE request_id = head.last_request_id;
    IF FOUND THEN
        SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = hosting.subscription_id;
        SELECT * INTO STRICT operation_row FROM subscription_lifecycle_operations WHERE request_id = hosting.request_id;
        SELECT * INTO STRICT billing FROM account_tenant_entitlement_outbox_events
            WHERE tenant_uuid = hosting.tenant_id AND tenant_billing_sequence = hosting.billing_sequence;
        IF operation_row.state <> 'COMMITTED' OR hosting.tenant_id <> NEW.tenant_uuid
            OR current_row.superseded_at IS NOT NULL OR current_row.current_receipt_request_id IS DISTINCT FROM hosting.request_id
            OR current_row.lifecycle_inflight_request_id IS NOT NULL OR current_row.row_version <> hosting.subscription_row_version
            OR current_row.status <> hosting.status OR current_row.provider_subscription_id IS DISTINCT FROM hosting.provider_subscription_id
            OR hosting.request_digest IS DISTINCT FROM head.last_request_digest
            OR hosting.authority_generation <> head.current_generation OR hosting.authority_source_version <> head.current_source_version
            OR hosting.authority_sequence <> head.last_outbox_sequence OR hosting.authority_event_id IS DISTINCT FROM head.last_event_id
            OR hosting.authority_event_digest IS DISTINCT FROM head.last_event_digest
            OR hosting.billing_sequence <> head.tenant_billing_sequence OR hosting.billing_event_id IS DISTINCT FROM head.tenant_billing_event_id
            OR hosting.billing_event_digest IS DISTINCT FROM head.tenant_billing_event_digest
            OR convert_from(billing.payload, 'UTF8')::jsonb->'receipt' IS DISTINCT FROM convert_from(hosting.receipt_payload, 'UTF8')::jsonb THEN
            RAISE EXCEPTION 'Hosting tenant source lacks its exact current receipt and billing event' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT request_id, tenant_uuid, request_digest, status, tenant_billing_sequence, event_id, event_digest,
           tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence, tenant_authority_event_id, tenant_authority_event_digest
        INTO operation_row FROM account_demo_tenant_entitlement_operations WHERE request_id = head.last_request_id;
    SELECT * INTO entitlement_row FROM account_demo_tenant_entitlements WHERE tenant_uuid = NEW.tenant_uuid;
    IF operation_row.status IS DISTINCT FROM 'COMMITTED' OR operation_row.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR operation_row.request_digest IS DISTINCT FROM head.last_request_digest
        OR operation_row.tenant_billing_sequence IS DISTINCT FROM head.tenant_billing_sequence
        OR operation_row.event_id IS DISTINCT FROM head.tenant_billing_event_id OR operation_row.event_digest IS DISTINCT FROM head.tenant_billing_event_digest
        OR operation_row.tenant_authority_outbox_stream_key IS DISTINCT FROM head.outbox_stream_key
        OR operation_row.tenant_authority_outbox_sequence IS DISTINCT FROM head.last_outbox_sequence
        OR operation_row.tenant_authority_event_id IS DISTINCT FROM head.last_event_id OR operation_row.tenant_authority_event_digest IS DISTINCT FROM head.last_event_digest
        OR entitlement_row.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR entitlement_row.tenant_authority_generation IS DISTINCT FROM head.current_generation OR entitlement_row.tenant_authority_source_version IS DISTINCT FROM head.current_source_version
        OR entitlement_row.tenant_billing_sequence IS DISTINCT FROM head.tenant_billing_sequence
        OR entitlement_row.event_id IS DISTINCT FROM head.tenant_billing_event_id OR entitlement_row.event_digest IS DISTINCT FROM head.tenant_billing_event_digest
        OR entitlement_row.tenant_authority_outbox_stream_key IS DISTINCT FROM head.outbox_stream_key
        OR entitlement_row.tenant_authority_outbox_sequence IS DISTINCT FROM head.last_outbox_sequence
        OR entitlement_row.tenant_authority_event_id IS DISTINCT FROM head.last_event_id OR entitlement_row.tenant_authority_event_digest IS DISTINCT FROM head.last_event_digest THEN
        RAISE EXCEPTION 'Tenant authority source lacks its exact current Account receipt and entitlement'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_receipt';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER subscription_lifecycle_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON subscription_lifecycle_operations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_operation_guard();
CREATE TRIGGER subscription_lifecycle_observation_guard BEFORE INSERT ON subscription_lifecycle_observations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_observation_guard();
CREATE TRIGGER subscription_lifecycle_receipt_guard BEFORE INSERT ON subscription_lifecycle_receipts
    FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_receipt_guard();
CREATE CONSTRAINT TRIGGER subscription_lifecycle_current_consistency AFTER INSERT OR UPDATE ON subscription
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_consistency();
CREATE CONSTRAINT TRIGGER subscription_lifecycle_operation_consistency AFTER INSERT OR UPDATE ON subscription_lifecycle_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_consistency();
CREATE CONSTRAINT TRIGGER subscription_lifecycle_receipt_consistency AFTER INSERT ON subscription_lifecycle_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_consistency();
CREATE CONSTRAINT TRIGGER subscription_lifecycle_notice_consistency AFTER INSERT ON subscription_lifecycle_operator_outbox
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_lifecycle_consistency();

CREATE TRIGGER subscription_lifecycle_operation_no_truncate BEFORE TRUNCATE ON subscription_lifecycle_operations FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_attempt_immutable BEFORE UPDATE OR DELETE ON subscription_lifecycle_attempts FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_attempt_no_truncate BEFORE TRUNCATE ON subscription_lifecycle_attempts FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_observation_immutable BEFORE UPDATE OR DELETE ON subscription_lifecycle_observations FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_observation_no_truncate BEFORE TRUNCATE ON subscription_lifecycle_observations FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_receipt_immutable BEFORE UPDATE OR DELETE ON subscription_lifecycle_receipts FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_receipt_no_truncate BEFORE TRUNCATE ON subscription_lifecycle_receipts FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_notice_immutable BEFORE UPDATE OR DELETE ON subscription_lifecycle_operator_outbox FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER subscription_lifecycle_notice_no_truncate BEFORE TRUNCATE ON subscription_lifecycle_operator_outbox FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();

CREATE FUNCTION account_subscription_provisioning_confirmed_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE work subscription_provisioning_work%ROWTYPE;
BEGIN
    SELECT * INTO STRICT work FROM subscription_provisioning_work WHERE request_id = NEW.request_id;
    IF work.phase = 'CONFIRMED' AND NOT EXISTS (
        SELECT 1 FROM subscription_lifecycle_operations operation
        JOIN subscription_lifecycle_receipts receipt USING (request_id, subscription_id)
        WHERE operation.creation_request_id = work.request_id AND operation.subscription_id = work.subscription_id
            AND operation.provisioning_attempt_id = work.last_attempt_id AND operation.state = 'COMMITTED') THEN
        RAISE EXCEPTION 'Provisioning confirmation lacks its exact lifecycle receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER subscription_provisioning_confirmed_guard AFTER INSERT OR UPDATE ON subscription_provisioning_work
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_provisioning_confirmed_guard();
-- [jooq ignore stop]
