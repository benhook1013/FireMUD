-- A verified late payment can require operator work even without local confirmation.
ALTER TABLE subscription_first_invoice_late_paid_conflicts
    ALTER COLUMN request_id DROP NOT NULL;
ALTER TABLE subscription_first_invoice_late_paid_conflicts
    ALTER COLUMN lifecycle_request_id DROP NOT NULL;
ALTER TABLE subscription_first_invoice_late_paid_conflicts
    ADD CONSTRAINT first_invoice_late_conflict_confirmation_pair
        CHECK ((request_id IS NULL) = (lifecycle_request_id IS NULL));

-- [jooq ignore start]
DO $migration$
DECLARE
    definition TEXT;
    previous_clause TEXT;
    next_clause TEXT;
BEGIN
    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) || '.account_first_invoice_late_paid_conflict_guard()')::regprocedure)
        INTO STRICT definition;

    previous_clause := $previous$
        SELECT * INTO STRICT confirmation FROM subscription_first_invoice_confirmations
            WHERE request_id = NEW.request_id AND subscription_id = NEW.subscription_id;
$previous$;
    next_clause := $next$
        IF NEW.request_id IS NULL THEN
            IF NEW.lifecycle_request_id IS NOT NULL OR EXISTS (
                SELECT 1 FROM subscription_first_invoice_confirmations
                WHERE subscription_id = NEW.subscription_id
            ) THEN
                RAISE EXCEPTION 'Late-payment conflict must preserve any retained confirmation' USING ERRCODE = '23514';
            END IF;
        ELSE
            SELECT * INTO STRICT confirmation FROM subscription_first_invoice_confirmations
                WHERE request_id = NEW.request_id AND subscription_id = NEW.subscription_id;
        END IF;
$next$;
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one late-payment confirmation lookup';
    END IF;
    definition := replace(definition, previous_clause, next_clause);

    previous_clause := $previous$
            OR (confirmation.lifecycle_request_id, confirmation.tuple_digest, confirmation.account_uuid)
                IS DISTINCT FROM (NEW.lifecycle_request_id, NEW.tuple_digest, first_invoice.account_uuid)
$previous$;
    next_clause := $next$
            OR (NEW.request_id IS NOT NULL
                AND (confirmation.lifecycle_request_id, confirmation.tuple_digest, confirmation.account_uuid)
                    IS DISTINCT FROM (NEW.lifecycle_request_id, NEW.tuple_digest, first_invoice.account_uuid))
$next$;
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one late-payment confirmation tuple guard';
    END IF;
    definition := replace(definition, previous_clause, next_clause);

    previous_clause := 'inbox.provider_created_at_epoch_seconds < floor(EXTRACT(EPOCH FROM confirmation.consented_at))';
    next_clause := 'inbox.provider_created_at_epoch_seconds < floor(EXTRACT(EPOCH FROM coalesce(confirmation.consented_at, first_invoice.captured_at)))';
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one late-payment chronology guard';
    END IF;
    EXECUTE replace(definition, previous_clause, next_clause);
END;
$migration$;
-- [jooq ignore stop]
