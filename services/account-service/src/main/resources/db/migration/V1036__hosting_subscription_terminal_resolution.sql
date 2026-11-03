-- Internal cancellation-only recovery. No actor authentication, provider absence, transfer,
-- caller registration or operational permission is established by these stored comparison facts.
CREATE TABLE subscription_lifecycle_terminal_resolutions (
    resolution_request_id UUID PRIMARY KEY CHECK (resolution_request_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    failed_request_id UUID NOT NULL REFERENCES subscription_lifecycle_operations(request_id) ON DELETE RESTRICT,
    creation_request_id UUID NOT NULL REFERENCES subscription_create_operations(request_id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL REFERENCES subscription(subscription_id) ON DELETE RESTRICT,
    tenant_id UUID NOT NULL,
    expected_failed_version BIGINT NOT NULL CHECK (expected_failed_version > 0),
    expected_subscription_version BIGINT NOT NULL CHECK (expected_subscription_version > 0 AND expected_subscription_version < 9223372036854775805),
    actor_account_id UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    control_ui_issuance_operation_id UUID NOT NULL CHECK (control_ui_issuance_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    control_ui_token_hash VARCHAR(64) NOT NULL CHECK (control_ui_token_hash ~ '^[0-9a-f]{64}$'),
    audit_event_id UUID NOT NULL UNIQUE REFERENCES account_audit_outbox(audit_event_id) ON DELETE RESTRICT,
    audit_reason TEXT NOT NULL CHECK (octet_length(audit_reason) BETWEEN 1 AND 1000),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) BETWEEN 1 AND 32768),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_payload), 'hex')),
    attempt_id UUID NOT NULL UNIQUE,
    captured_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > captured_at),
    authenticated_at TIMESTAMPTZ NOT NULL,
    authorization_outcome VARCHAR(32) NOT NULL DEFAULT 'PRIMARY_AUTHENTICATED' CHECK (authorization_outcome = 'PRIMARY_AUTHENTICATED'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PREPARED', 'COMMITTED')),
    result_row_version BIGINT,
    receipt_digest VARCHAR(71),
    committed_at TIMESTAMPTZ,
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    CHECK (resolution_request_id <> failed_request_id),
    CHECK (authenticated_at >= captured_at AND authenticated_at < expires_at),
    CHECK ((state = 'PREPARED' AND result_row_version IS NULL AND receipt_digest IS NULL AND committed_at IS NULL)
        OR (state = 'COMMITTED' AND result_row_version IS NOT NULL AND receipt_digest IS NOT NULL
            AND result_row_version = expected_subscription_version + 2
            AND receipt_digest ~ '^sha256:[0-9a-f]{64}$' AND committed_at IS NOT NULL)),
    UNIQUE (failed_request_id)
);

-- Failure history stays immutable. Its retained subscription fence still blocks normal prepare;
-- only the journal-backed atomic cancellation transition may replace that fence.
DROP INDEX subscription_lifecycle_one_inflight;
CREATE UNIQUE INDEX subscription_lifecycle_one_inflight ON subscription_lifecycle_operations(subscription_id)
    WHERE state = 'PENDING';

-- [jooq ignore start]
ALTER TABLE subscription_lifecycle_terminal_resolutions
    ADD CONSTRAINT subscription_terminal_resolution_reason_nfc CHECK (audit_reason IS NFC NORMALIZED);

CREATE FUNCTION account_subscription_terminal_resolution_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE failed subscription_lifecycle_operations%ROWTYPE; current_row subscription%ROWTYPE;
    audit account_audit_outbox%ROWTYPE; payload JSONB; field_count INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Terminal lifecycle resolution is retained' USING ERRCODE = '23514';
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.state <> 'PREPARED' OR NEW.state <> 'COMMITTED'
            OR (to_jsonb(NEW) - ARRAY['state','result_row_version','receipt_digest','committed_at'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','result_row_version','receipt_digest','committed_at'])
            OR OLD.created_transaction_id <> txid_current() THEN
            RAISE EXCEPTION 'Terminal lifecycle resolution is immutable outside its owner commit' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT failed FROM subscription_lifecycle_operations WHERE request_id = NEW.failed_request_id FOR UPDATE;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    SELECT * INTO STRICT audit FROM account_audit_outbox WHERE audit_event_id = NEW.audit_event_id;
    payload := convert_from(NEW.request_payload, 'UTF8')::jsonb;
    SELECT count(*) INTO field_count FROM jsonb_object_keys(payload);
    IF NEW.state <> 'PREPARED' OR failed.state <> 'BILLING_RECONCILIATION_TERMINAL_FAILURE'
        OR failed.row_version <> NEW.expected_failed_version
        OR failed.creation_request_id <> NEW.creation_request_id OR failed.subscription_id <> NEW.subscription_id
        OR failed.tenant_id <> NEW.tenant_id OR current_row.tenant_id <> NEW.tenant_id
        OR current_row.account_uuid <> NEW.actor_account_id OR current_row.creation_request_id <> NEW.creation_request_id
        OR current_row.row_version <> NEW.expected_subscription_version OR current_row.superseded_at IS NOT NULL
        OR current_row.lifecycle_inflight_request_id IS DISTINCT FROM NEW.failed_request_id
        OR audit.scope <> 'tenant' OR audit.tenant_identity_version <> 2 OR audit.tenant_uuid IS DISTINCT FROM NEW.tenant_id
        OR audit.producer_service <> 'account-service' OR audit.event_type <> 'HOSTING_SUBSCRIPTION_TERMINAL_RESOLVED'
        OR audit.payload_digest <> NEW.request_digest OR audit.payload IS DISTINCT FROM convert_from(NEW.request_payload, 'UTF8')
        OR field_count <> 14 OR payload->>'schemaVersion' IS DISTINCT FROM 'account-hosting-terminal-resolution/v1'
        OR payload->>'outcome' IS DISTINCT FROM 'CANCELED'
        OR payload->>'resolutionRequestId' IS DISTINCT FROM NEW.resolution_request_id::text
        OR payload->>'failedRequestId' IS DISTINCT FROM NEW.failed_request_id::text
        OR payload->>'creationRequestId' IS DISTINCT FROM NEW.creation_request_id::text
        OR payload->>'subscriptionId' IS DISTINCT FROM NEW.subscription_id::text
        OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_id::text
        OR payload->>'expectedFailedOperationVersion' IS DISTINCT FROM NEW.expected_failed_version::text
        OR payload->>'expectedSubscriptionVersion' IS DISTINCT FROM NEW.expected_subscription_version::text
        OR payload->>'actorAccountId' IS DISTINCT FROM NEW.actor_account_id::text
        OR payload->>'controlUiIssuanceOperationId' IS DISTINCT FROM NEW.control_ui_issuance_operation_id::text
        OR payload->>'controlUiTokenHash' IS DISTINCT FROM NEW.control_ui_token_hash
        OR payload->>'auditEventId' IS DISTINCT FROM NEW.audit_event_id::text
        OR payload->>'auditReason' IS DISTINCT FROM NEW.audit_reason
        OR btrim(NEW.audit_reason, E'\t\n\f\r ' || chr(11) || chr(133) || chr(160) || chr(5760)
            || chr(8192) || chr(8193) || chr(8194) || chr(8195) || chr(8196) || chr(8197)
            || chr(8198) || chr(8199) || chr(8200) || chr(8201) || chr(8202) || chr(8232)
            || chr(8233) || chr(8239) || chr(8287) || chr(12288)) = '' THEN
        RAISE EXCEPTION 'Terminal lifecycle resolution lacks its exact failed target and audit' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER subscription_terminal_resolution_guard BEFORE INSERT OR UPDATE OR DELETE ON subscription_lifecycle_terminal_resolutions
    FOR EACH ROW EXECUTE FUNCTION account_subscription_terminal_resolution_guard();

CREATE OR REPLACE FUNCTION account_subscription_lifecycle_current_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
            IF NEW.current_receipt_request_id IS DISTINCT FROM OLD.current_receipt_request_id
                OR NOT EXISTS (SELECT 1 FROM subscription_lifecycle_terminal_resolutions resolution
                    JOIN subscription_lifecycle_operations operation ON operation.request_id = resolution.resolution_request_id
                    WHERE resolution.failed_request_id = OLD.lifecycle_inflight_request_id
                        AND resolution.resolution_request_id = NEW.lifecycle_inflight_request_id
                        AND resolution.subscription_id = OLD.subscription_id AND resolution.state = 'PREPARED'
                        AND resolution.expected_subscription_version = OLD.row_version
                        AND resolution.created_transaction_id = txid_current()
                        AND operation.state = 'PENDING' AND operation.request_digest = resolution.request_digest
                        AND operation.reserved_row_version = NEW.row_version
                        AND operation.creation_request_id = OLD.creation_request_id
                        AND operation.tenant_id = OLD.tenant_id) THEN
                RAISE EXCEPTION 'In-flight lifecycle ownership cannot be replaced without exact resolution' USING ERRCODE = '23514';
            END IF;
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

CREATE FUNCTION account_subscription_terminal_resolution_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE resolution subscription_lifecycle_terminal_resolutions%ROWTYPE; operation subscription_lifecycle_operations%ROWTYPE;
    receipt subscription_lifecycle_receipts%ROWTYPE; observation subscription_lifecycle_observations%ROWTYPE;
    current_row subscription%ROWTYPE; failed subscription_lifecycle_operations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT resolution FROM subscription_lifecycle_terminal_resolutions WHERE resolution_request_id = NEW.resolution_request_id;
    SELECT * INTO STRICT failed FROM subscription_lifecycle_operations WHERE request_id = resolution.failed_request_id;
    SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = resolution.resolution_request_id;
    SELECT * INTO STRICT receipt FROM subscription_lifecycle_receipts WHERE request_id = resolution.resolution_request_id;
    SELECT * INTO STRICT observation FROM subscription_lifecycle_observations WHERE attempt_id = resolution.attempt_id;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = resolution.subscription_id;
    IF resolution.state <> 'COMMITTED' OR resolution.created_transaction_id <> txid_current()
        OR failed.state <> 'BILLING_RECONCILIATION_TERMINAL_FAILURE' OR failed.row_version <> resolution.expected_failed_version
        OR operation.state <> 'COMMITTED' OR operation.request_digest <> resolution.request_digest
        OR operation.request_payload <> resolution.request_payload OR operation.creation_request_id <> resolution.creation_request_id
        OR receipt.status <> 'canceled' OR receipt.request_digest <> resolution.request_digest
        OR receipt.subscription_id <> resolution.subscription_id OR receipt.tenant_id <> resolution.tenant_id
        OR receipt.subscription_row_version <> resolution.result_row_version OR receipt.receipt_digest <> resolution.receipt_digest
        OR receipt.attempt_id <> resolution.attempt_id OR receipt.committed_transaction_id <> txid_current()
        OR observation.raw_status <> 'canceled' OR observation.recorded_transaction_id <> txid_current()
        OR convert_from(observation.payload, 'UTF8')::jsonb->>'kind' IS DISTINCT FROM 'SDK_OBSERVED'
        OR convert_from(observation.payload, 'UTF8')::jsonb->>'providerStatus' IS DISTINCT FROM 'canceled'
        OR observation.read_started_at < resolution.captured_at OR observation.observed_at > resolution.authenticated_at
        OR resolution.authenticated_at >= resolution.expires_at OR resolution.committed_at >= resolution.expires_at
        OR current_row.current_receipt_request_id IS DISTINCT FROM resolution.resolution_request_id
        OR current_row.lifecycle_inflight_request_id IS NOT NULL OR current_row.row_version <> resolution.result_row_version
        OR current_row.status <> 'canceled' OR current_row.terminal_resolution_id IS NULL
        OR NOT EXISTS (SELECT 1 FROM subscription_instrument_reservations reservation
            WHERE reservation.request_id = resolution.creation_request_id AND reservation.subscription_id = resolution.subscription_id
                AND reservation.terminal_resolution_id = current_row.terminal_resolution_id AND reservation.released_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Cancellation resolution lacks its atomic receipt, retained failure and terminal reservation' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER subscription_terminal_resolution_consistency AFTER INSERT OR UPDATE ON subscription_lifecycle_terminal_resolutions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_terminal_resolution_consistency();
CREATE TRIGGER subscription_terminal_resolution_no_truncate BEFORE TRUNCATE ON subscription_lifecycle_terminal_resolutions
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();

-- Replacing the partial index must not make unowned dangling PENDING rows committable.
CREATE FUNCTION account_subscription_pending_owner_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation subscription_lifecycle_operations%ROWTYPE; current_row subscription%ROWTYPE;
BEGIN
    SELECT * INTO STRICT operation FROM subscription_lifecycle_operations WHERE request_id = NEW.request_id;
    IF operation.state = 'PENDING' THEN
        SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = operation.subscription_id;
        IF current_row.lifecycle_inflight_request_id IS DISTINCT FROM operation.request_id THEN
            RAISE EXCEPTION 'Pending lifecycle requires its exact retained current fence' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER subscription_pending_owner_consistency AFTER INSERT OR UPDATE ON subscription_lifecycle_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_subscription_pending_owner_consistency();
-- [jooq ignore stop]
