-- Preserve exact consent identity and the original upper authorization deadline.
-- [jooq ignore start]
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
        OR NEW.consented_at < auth.issued_at - auth.max_clock_skew_seconds * INTERVAL '1 second'
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
        OR NEW.consented_at < auth.issued_at - auth.max_clock_skew_seconds * INTERVAL '1 second' OR NEW.consented_at >= NEW.valid_until
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
