-- A retained transaction ID can date from a migration. Only a fresh guarded insert
-- can attest to the original marker transaction. Existing receipts are unaffected.
-- [jooq ignore start]
LOCK TABLE subscription_first_invoice_expired_before_post,
    subscription_first_invoice_no_send_cancellations IN ACCESS EXCLUSIVE MODE NOWAIT;
-- [jooq ignore stop]

ALTER TABLE subscription_first_invoice_expired_before_post
    ADD COLUMN original_creation_proven BOOLEAN NOT NULL DEFAULT FALSE;

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_first_invoice_expired_before_post_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE claim_transaction BIGINT; authorization_expiry TIMESTAMPTZ;
BEGIN
    SELECT c.created_transaction_id, a.valid_until INTO STRICT claim_transaction, authorization_expiry
    FROM subscription_first_invoice_command_claims c
    JOIN subscription_first_invoice_authorizations a USING (authorization_request_id, request_id, subscription_id)
    JOIN subscription s ON s.subscription_id = c.subscription_id
    WHERE c.request_id = NEW.request_id FOR UPDATE OF s;
    IF claim_transaction = txid_current() OR NEW.observed_at < authorization_expiry THEN
        RAISE EXCEPTION 'First-invoice no-send evidence requires an independently committed claim and expired authorization' USING ERRCODE = '23514';
    END IF;
    IF NEW.observed_at > clock_timestamp() THEN
        RAISE EXCEPTION 'First-invoice no-send observation is later than the database clock'
            USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    NEW.original_creation_proven := TRUE;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_first_invoice_no_send_cancellation_creation_proof() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM subscription_first_invoice_expired_before_post
        WHERE request_id = NEW.confirmation_request_id AND original_creation_proven
    ) THEN
        RAISE EXCEPTION 'Claimed first-invoice cancellation requires proof of original no-send marker creation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER first_invoice_no_send_cancellation_creation_proof
    BEFORE INSERT ON subscription_first_invoice_no_send_cancellations
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_no_send_cancellation_creation_proof();
-- [jooq ignore stop]
