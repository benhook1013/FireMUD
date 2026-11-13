-- Account retains a durable original checkout operation before returning its first usable proof.
-- New first consent and explicit pre-row recovery compete for this exact fence; neither the
-- nullable status projection nor unbound historical reauthentication evidence grants authority.
ALTER TABLE account_recent_reauthentication_evidence
    ADD COLUMN billing_context_subscription_id UUID,
    ADD COLUMN billing_context_original_request_id UUID,
    ADD COLUMN billing_context_tuple_digest VARCHAR(71),
    ADD COLUMN billing_context_expected_row_version BIGINT,
    ADD COLUMN billing_context_invoice_expires_at TIMESTAMPTZ,
    ADD COLUMN billing_context_authorization_request_id UUID,
    ADD COLUMN billing_context_expected_authorization_version BIGINT,
    ADD COLUMN billing_context_authorization_purpose VARCHAR(32),
    ADD COLUMN billing_context_digest VARCHAR(71),
    ADD CONSTRAINT account_recent_reauthentication_billing_context_all_or_none CHECK (
        (billing_context_subscription_id IS NULL
            AND billing_context_original_request_id IS NULL
            AND billing_context_tuple_digest IS NULL
            AND billing_context_expected_row_version IS NULL
            AND billing_context_invoice_expires_at IS NULL
            AND billing_context_authorization_request_id IS NULL
            AND billing_context_expected_authorization_version IS NULL
            AND billing_context_authorization_purpose IS NULL
            AND billing_context_digest IS NULL)
        OR
        (billing_context_subscription_id IS NOT NULL
            AND billing_context_original_request_id IS NOT NULL
            AND billing_context_tuple_digest IS NOT NULL
            AND billing_context_expected_row_version IS NOT NULL
            AND billing_context_invoice_expires_at IS NOT NULL
            AND billing_context_authorization_request_id IS NOT NULL
            AND billing_context_expected_authorization_version IS NOT NULL
            AND billing_context_authorization_purpose IS NOT NULL
            AND billing_context_digest IS NOT NULL)
    ),
    ADD CONSTRAINT account_recent_reauthentication_billing_context_purpose_check CHECK (
        billing_context_digest IS NULL
        OR (purpose = 'HOSTING_FIRST_INVOICE_PAYMENT'
            AND billing_context_tuple_digest ~ '^sha256:[0-9a-f]{64}$'
            AND billing_context_expected_row_version > 0
            AND billing_context_expected_authorization_version >= 0
            AND billing_context_digest ~ '^sha256:[0-9a-f]{64}$'
            AND billing_context_authorization_purpose IN
                ('INITIAL_CONFIRMATION', 'PRE_ROW_RENEWAL', 'FOLLOWUP_RENEWAL'))
    );

CREATE TABLE subscription_first_invoice_operation_fences (
    subscription_id UUID PRIMARY KEY REFERENCES subscription_first_invoices(subscription_id) ON DELETE RESTRICT,
    request_id UUID NOT NULL UNIQUE CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tuple_digest VARCHAR(71) NOT NULL CHECK (tuple_digest ~ '^sha256:[0-9a-f]{64}$'),
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version > 0),
    invoice_expires_at TIMESTAMPTZ NOT NULL,
    reauthentication_evidence_id UUID NOT NULL UNIQUE
        REFERENCES account_recent_reauthentication_evidence(evidence_id) ON DELETE RESTRICT,
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (subscription_id, tuple_digest)
        REFERENCES subscription_first_invoices(subscription_id, tuple_digest) ON DELETE RESTRICT,
    UNIQUE (request_id, subscription_id)
);

-- [jooq ignore start]
CREATE FUNCTION account_recent_reauthentication_billing_context_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    expected_context_digest TEXT;
    expected_request_payload TEXT;
    expiry_text TEXT;
BEGIN
    IF NEW.billing_context_digest IS NULL THEN
        IF NEW.purpose = 'HOSTING_FIRST_INVOICE_PAYMENT' THEN
            RAISE EXCEPTION 'New billing reauthentication evidence requires an exact typed context'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.purpose <> 'HOSTING_FIRST_INVOICE_PAYMENT'
        OR NEW.creation_operation_id IS DISTINCT FROM NEW.billing_context_authorization_request_id
        OR (NEW.billing_context_authorization_purpose = 'INITIAL_CONFIRMATION'
            AND (NEW.billing_context_original_request_id IS DISTINCT FROM NEW.creation_operation_id
                OR NEW.billing_context_expected_authorization_version <> 0))
        OR (NEW.billing_context_authorization_purpose = 'PRE_ROW_RENEWAL'
            AND (NEW.billing_context_original_request_id = NEW.creation_operation_id
                OR NEW.billing_context_expected_authorization_version <> 0))
        OR (NEW.billing_context_authorization_purpose = 'FOLLOWUP_RENEWAL'
            AND (NEW.billing_context_original_request_id = NEW.creation_operation_id
                OR NEW.billing_context_expected_authorization_version < 1)) THEN
        RAISE EXCEPTION 'Typed billing reauthentication context has an invalid operation binding'
            USING ERRCODE = '23514';
    END IF;

    -- Java's canonical Instant representation uses six fractional digits for this
    -- database-bound context. PostgreSQL timestamptz precision is microseconds.
    expiry_text := to_char(NEW.billing_context_invoice_expires_at AT TIME ZONE 'UTC',
        'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
    expected_context_digest := 'sha256:' || encode(sha256(convert_to(
        'account-hosting-billing-reauth-context/v1' || E'\n'
        || 'accountId=' || NEW.account_uuid::TEXT || E'\n'
        || 'subscriptionId=' || NEW.billing_context_subscription_id::TEXT || E'\n'
        || 'originalRequestId=' || NEW.billing_context_original_request_id::TEXT || E'\n'
        || 'tupleDigest=' || NEW.billing_context_tuple_digest || E'\n'
        || 'expectedRowVersion=' || NEW.billing_context_expected_row_version::TEXT || E'\n'
        || 'invoiceExpiresAt=' || expiry_text || E'\n'
        || 'authorizationRequestId=' || NEW.billing_context_authorization_request_id::TEXT || E'\n'
        || 'expectedAuthorizationVersion=' || NEW.billing_context_expected_authorization_version::TEXT || E'\n'
        || 'authorizationPurpose=' || NEW.billing_context_authorization_purpose || E'\n', 'UTF8')), 'hex');
    IF NEW.billing_context_digest IS DISTINCT FROM expected_context_digest THEN
        RAISE EXCEPTION 'Typed billing reauthentication digest does not match its immutable context'
            USING ERRCODE = '23514';
    END IF;

    expected_request_payload := 'account-recent-reauthentication-request/v2' || E'\n'
        || 'requestId=' || NEW.request_id::TEXT || E'\n'
        || 'accountId=' || NEW.account_uuid::TEXT || E'\n'
        || 'purpose=' || NEW.purpose || E'\n'
        || 'creationOperationId=' || NEW.creation_operation_id::TEXT || E'\n'
        || 'billingContextDigest=' || NEW.billing_context_digest || E'\n';
    IF NEW.request_payload IS DISTINCT FROM convert_to(expected_request_payload, 'UTF8')
        OR NEW.request_digest IS DISTINCT FROM 'sha256:' || encode(
            sha256(convert_to(expected_request_payload, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'Typed billing reauthentication request digest is not canonical'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_recent_reauthentication_billing_context_guard
    BEFORE INSERT ON account_recent_reauthentication_evidence
    FOR EACH ROW EXECUTE FUNCTION account_recent_reauthentication_billing_context_guard();

CREATE FUNCTION account_first_invoice_operation_fence_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    invoice subscription_first_invoices%ROWTYPE;
    evidence account_recent_reauthentication_evidence%ROWTYPE;
    current_row RECORD;
BEGIN
    SELECT * INTO STRICT invoice FROM subscription_first_invoices
        WHERE subscription_id = NEW.subscription_id FOR SHARE;
    SELECT s.status, s.row_version, s.provider_subscription_id, w.phase INTO STRICT current_row
        FROM subscription s JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id
        WHERE s.subscription_id = NEW.subscription_id AND w.request_id = invoice.creation_request_id
            AND s.superseded_at IS NULL FOR UPDATE OF s;
    SELECT * INTO STRICT evidence FROM account_recent_reauthentication_evidence
        WHERE evidence_id = NEW.reauthentication_evidence_id FOR UPDATE;
    IF current_row.status <> 'provisioning' OR current_row.phase <> 'AWAITING_PAYMENT'
        OR current_row.row_version IS DISTINCT FROM NEW.expected_row_version
        OR current_row.provider_subscription_id IS DISTINCT FROM invoice.provider_subscription_id
        OR invoice.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR invoice.tuple_digest IS DISTINCT FROM NEW.tuple_digest
        OR invoice.expires_at IS DISTINCT FROM NEW.invoice_expires_at
        OR NEW.request_id IS DISTINCT FROM evidence.creation_operation_id
        OR NEW.account_uuid IS DISTINCT FROM evidence.account_uuid
        OR evidence.purpose <> 'HOSTING_FIRST_INVOICE_PAYMENT'
        OR evidence.billing_context_authorization_purpose <> 'INITIAL_CONFIRMATION'
        OR evidence.billing_context_subscription_id IS DISTINCT FROM NEW.subscription_id
        OR evidence.billing_context_original_request_id IS DISTINCT FROM NEW.request_id
        OR evidence.billing_context_tuple_digest IS DISTINCT FROM NEW.tuple_digest
        OR evidence.billing_context_expected_row_version IS DISTINCT FROM NEW.expected_row_version
        OR evidence.billing_context_invoice_expires_at IS DISTINCT FROM NEW.invoice_expires_at
        OR evidence.billing_context_authorization_request_id IS DISTINCT FROM NEW.request_id
        OR evidence.billing_context_expected_authorization_version <> 0
        OR evidence.billing_context_digest IS DISTINCT FROM NEW.binding_digest
        OR EXISTS (SELECT 1 FROM account_recent_reauthentication_consumptions c
            WHERE c.evidence_id = evidence.evidence_id)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_confirmations c
            WHERE c.subscription_id = NEW.subscription_id)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_authorizations a
            WHERE a.subscription_id = NEW.subscription_id) THEN
        RAISE EXCEPTION 'First-invoice operation fence requires exact unconsumed opening proof and current invoice'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_operation_fence
    BEFORE INSERT ON subscription_first_invoice_operation_fences
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_operation_fence_guard();
CREATE TRIGGER first_invoice_operation_fences_immutable BEFORE UPDATE OR DELETE
    ON subscription_first_invoice_operation_fences
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER first_invoice_operation_fences_no_truncate BEFORE TRUNCATE
    ON subscription_first_invoice_operation_fences
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();

CREATE OR REPLACE FUNCTION account_first_invoice_consent_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    auth account_recent_reauthentication_evidence%ROWTYPE;
    invoice subscription_first_invoices%ROWTYPE;
    current_row RECORD;
    fence subscription_first_invoice_operation_fences%ROWTYPE;
BEGIN
    SELECT * INTO STRICT auth FROM account_recent_reauthentication_evidence
        WHERE evidence_id = NEW.reauthentication_evidence_id;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices
        WHERE subscription_id = NEW.subscription_id;
    SELECT s.status, s.row_version, s.provider_subscription_id, w.phase INTO STRICT current_row
        FROM subscription s JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id
        WHERE s.subscription_id = NEW.subscription_id AND w.request_id = invoice.creation_request_id
            AND s.superseded_at IS NULL FOR SHARE OF s, w;
    IF current_row.status <> 'provisioning' OR current_row.phase <> 'AWAITING_PAYMENT'
        OR current_row.row_version <> NEW.expected_row_version
        OR current_row.provider_subscription_id IS DISTINCT FROM invoice.provider_subscription_id
        OR auth.purpose <> 'HOSTING_FIRST_INVOICE_PAYMENT'
        OR auth.account_uuid <> NEW.account_uuid OR invoice.account_uuid <> NEW.account_uuid
        OR invoice.tuple_digest <> NEW.tuple_digest
        OR NEW.consented_at < auth.issued_at
        OR NEW.consented_at >= NEW.valid_until
        OR NEW.valid_until > auth.issued_at + (auth.recent_age_seconds - auth.max_clock_skew_seconds) * INTERVAL '1 second'
        OR NEW.valid_until > invoice.expires_at
        OR NOT EXISTS (SELECT 1 FROM account_recent_reauthentication_consumptions c
            WHERE c.evidence_id = auth.evidence_id AND c.creation_operation_id = auth.creation_operation_id
                AND c.account_uuid = auth.account_uuid AND c.purpose = auth.purpose) THEN
        RAISE EXCEPTION 'First-invoice consent requires exact current billing-purpose reauthentication'
            USING ERRCODE = '23514';
    END IF;

    IF auth.billing_context_authorization_purpose = 'INITIAL_CONFIRMATION' THEN
        SELECT * INTO STRICT fence FROM subscription_first_invoice_operation_fences
            WHERE subscription_id = NEW.subscription_id;
        IF NEW.request_id IS DISTINCT FROM auth.creation_operation_id
            OR NEW.request_id IS DISTINCT FROM auth.billing_context_original_request_id
            OR auth.billing_context_authorization_request_id IS DISTINCT FROM NEW.request_id
            OR auth.billing_context_expected_authorization_version <> 0
            OR auth.billing_context_subscription_id IS DISTINCT FROM NEW.subscription_id
            OR auth.billing_context_tuple_digest IS DISTINCT FROM NEW.tuple_digest
            OR auth.billing_context_expected_row_version IS DISTINCT FROM NEW.expected_row_version
            OR auth.billing_context_invoice_expires_at IS DISTINCT FROM invoice.expires_at
            OR fence.request_id IS DISTINCT FROM NEW.request_id
            OR fence.account_uuid IS DISTINCT FROM NEW.account_uuid
            OR fence.tuple_digest IS DISTINCT FROM NEW.tuple_digest
            OR fence.expected_row_version IS DISTINCT FROM NEW.expected_row_version
            OR fence.invoice_expires_at IS DISTINCT FROM invoice.expires_at
            OR fence.reauthentication_evidence_id IS DISTINCT FROM auth.evidence_id
            OR fence.binding_digest IS DISTINCT FROM auth.billing_context_digest THEN
            RAISE EXCEPTION 'Initial first-invoice consent differs from its durable original operation fence'
                USING ERRCODE = '23514';
        END IF;
    ELSIF auth.billing_context_authorization_purpose = 'PRE_ROW_RENEWAL' THEN
        SELECT * INTO STRICT fence FROM subscription_first_invoice_operation_fences
            WHERE subscription_id = NEW.subscription_id;
        IF auth.creation_operation_id IS DISTINCT FROM auth.billing_context_authorization_request_id
            OR auth.billing_context_original_request_id IS DISTINCT FROM NEW.request_id
            OR auth.billing_context_expected_authorization_version <> 0
            OR auth.billing_context_subscription_id IS DISTINCT FROM NEW.subscription_id
            OR auth.billing_context_tuple_digest IS DISTINCT FROM NEW.tuple_digest
            OR auth.billing_context_expected_row_version IS DISTINCT FROM NEW.expected_row_version
            OR auth.billing_context_invoice_expires_at IS DISTINCT FROM invoice.expires_at
            OR fence.request_id IS DISTINCT FROM NEW.request_id
            OR fence.account_uuid IS DISTINCT FROM NEW.account_uuid
            OR fence.tuple_digest IS DISTINCT FROM NEW.tuple_digest
            OR fence.expected_row_version IS DISTINCT FROM NEW.expected_row_version
            OR fence.invoice_expires_at IS DISTINCT FROM invoice.expires_at THEN
            RAISE EXCEPTION 'Pre-row first-invoice consent differs from its durable original operation fence'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'First-invoice consent requires typed opening or pre-row billing proof'
            USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_first_invoice_authorization_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    consent subscription_first_invoice_confirmations%ROWTYPE;
    auth account_recent_reauthentication_evidence%ROWTYPE;
    invoice subscription_first_invoices%ROWTYPE;
    current_row RECORD;
    fence subscription_first_invoice_operation_fences%ROWTYPE;
    latest BIGINT;
BEGIN
    SELECT s.status, s.row_version, s.provider_subscription_id, w.phase INTO STRICT current_row
        FROM subscription s JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id
        WHERE s.subscription_id = NEW.subscription_id AND s.superseded_at IS NULL FOR UPDATE OF s;
    SELECT * INTO STRICT consent FROM subscription_first_invoice_confirmations
        WHERE request_id = NEW.request_id;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices
        WHERE subscription_id = NEW.subscription_id;
    SELECT * INTO STRICT auth FROM account_recent_reauthentication_evidence
        WHERE evidence_id = NEW.reauthentication_evidence_id;
    SELECT COALESCE(MAX(authorization_version), 0) INTO latest
        FROM subscription_first_invoice_authorizations WHERE subscription_id = NEW.subscription_id;
    IF consent.subscription_id <> NEW.subscription_id OR current_row.status <> 'provisioning'
        OR current_row.phase <> 'AWAITING_PAYMENT'
        OR current_row.row_version <> consent.expected_row_version
        OR current_row.provider_subscription_id <> invoice.provider_subscription_id
        OR NEW.authorization_version <> latest + 1
        OR NEW.request_id <> consent.request_id
        OR auth.account_uuid <> consent.account_uuid
        OR auth.purpose <> 'HOSTING_FIRST_INVOICE_PAYMENT'
        OR auth.creation_operation_id <> NEW.authorization_request_id
        OR NEW.consented_at < auth.issued_at OR NEW.consented_at >= NEW.valid_until
        OR NEW.valid_until > invoice.expires_at
        OR NEW.valid_until > auth.issued_at + (auth.recent_age_seconds - auth.max_clock_skew_seconds) * INTERVAL '1 second'
        OR NOT EXISTS (SELECT 1 FROM account_recent_reauthentication_consumptions c
            WHERE c.evidence_id = auth.evidence_id
                AND c.creation_operation_id = NEW.authorization_request_id
                AND c.account_uuid = consent.account_uuid AND c.purpose = auth.purpose) THEN
        RAISE EXCEPTION 'First-invoice authorization requires fresh exact consumed consent and current version'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.authorization_version = 1 THEN
        SELECT * INTO STRICT fence FROM subscription_first_invoice_operation_fences
            WHERE subscription_id = NEW.subscription_id;
        IF consent.created_transaction_id <> txid_current()
            OR NEW.reauthentication_evidence_id <> consent.reauthentication_evidence_id
            OR auth.billing_context_subscription_id IS DISTINCT FROM NEW.subscription_id
            OR auth.billing_context_original_request_id IS DISTINCT FROM NEW.request_id
            OR auth.billing_context_tuple_digest IS DISTINCT FROM consent.tuple_digest
            OR auth.billing_context_expected_row_version IS DISTINCT FROM consent.expected_row_version
            OR auth.billing_context_invoice_expires_at IS DISTINCT FROM invoice.expires_at
            OR auth.billing_context_authorization_request_id IS DISTINCT FROM NEW.authorization_request_id
            OR auth.billing_context_expected_authorization_version <> 0
            OR fence.request_id IS DISTINCT FROM NEW.request_id
            OR fence.account_uuid IS DISTINCT FROM consent.account_uuid
            OR fence.tuple_digest IS DISTINCT FROM consent.tuple_digest
            OR fence.expected_row_version IS DISTINCT FROM consent.expected_row_version
            OR fence.invoice_expires_at IS DISTINCT FROM invoice.expires_at THEN
            RAISE EXCEPTION 'First-invoice winner must bind exact consent to the original operation fence'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.authorization_request_id = NEW.request_id THEN
            IF auth.billing_context_authorization_purpose <> 'INITIAL_CONFIRMATION'
                OR fence.reauthentication_evidence_id IS DISTINCT FROM auth.evidence_id
                OR fence.binding_digest IS DISTINCT FROM auth.billing_context_digest THEN
                RAISE EXCEPTION 'Original confirmation must win only with its retained opening proof'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF auth.billing_context_authorization_purpose <> 'PRE_ROW_RENEWAL' THEN
            RAISE EXCEPTION 'Distinct first authorization must be an explicit pre-row renewal'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.authorization_request_id = NEW.request_id
            OR auth.billing_context_authorization_purpose <> 'FOLLOWUP_RENEWAL'
            OR auth.billing_context_original_request_id IS DISTINCT FROM NEW.request_id
            OR auth.billing_context_expected_authorization_version <> NEW.authorization_version - 1
            OR auth.billing_context_subscription_id IS DISTINCT FROM NEW.subscription_id
            OR auth.billing_context_tuple_digest IS DISTINCT FROM consent.tuple_digest
            OR auth.billing_context_expected_row_version IS DISTINCT FROM consent.expected_row_version
            OR auth.billing_context_invoice_expires_at IS DISTINCT FROM invoice.expires_at
            OR auth.billing_context_authorization_request_id IS DISTINCT FROM NEW.authorization_request_id THEN
            RAISE EXCEPTION 'Follow-up first-invoice authorization requires exact retained operation binding'
                USING ERRCODE = '23514';
        END IF;
        IF EXISTS (SELECT 1 FROM subscription_first_invoice_operation_fences f
            WHERE f.subscription_id = NEW.subscription_id)
            AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_operation_fences f
                WHERE f.subscription_id = NEW.subscription_id AND f.request_id = NEW.request_id
                    AND f.account_uuid = consent.account_uuid AND f.tuple_digest = consent.tuple_digest
                    AND f.expected_row_version = consent.expected_row_version
                    AND f.invoice_expires_at = invoice.expires_at) THEN
            RAISE EXCEPTION 'Follow-up authorization differs from its retained operation fence'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
