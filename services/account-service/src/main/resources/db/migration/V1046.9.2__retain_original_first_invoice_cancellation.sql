-- Retain late-paid evidence against its original terminal cancellation after later canceled receipts.
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
                JOIN subscription_lifecycle_operations canceled_operation
                    ON canceled_operation.request_id = current_row.current_receipt_request_id
                    AND canceled_operation.subscription_id = current_row.subscription_id
                JOIN subscription_lifecycle_receipts canceled_receipt
                    ON canceled_receipt.request_id = canceled_operation.request_id
                    AND canceled_receipt.subscription_id = canceled_operation.subscription_id
                JOIN subscription_lifecycle_observations cancellation_observation
                    ON (cancellation_observation.request_id, cancellation_observation.attempt_id,
                        cancellation_observation.evidence_digest)
                        = (canceled_receipt.request_id, canceled_receipt.attempt_id,
                            canceled_receipt.observation_digest)
                JOIN subscription_provider_terminal_resolutions terminal
                    ON terminal.resolution_id = current_row.terminal_resolution_id
                    AND terminal.subscription_id = current_row.subscription_id
$previous$;
    next_clause := $next$
                JOIN subscription_provider_terminal_resolutions terminal
                    ON terminal.resolution_id = current_row.terminal_resolution_id
                    AND terminal.subscription_id = current_row.subscription_id
                JOIN subscription_lifecycle_observations cancellation_observation
                    ON cancellation_observation.evidence_digest = terminal.evidence_digest
                JOIN subscription_lifecycle_receipts canceled_receipt
                    ON (canceled_receipt.request_id, canceled_receipt.attempt_id,
                        canceled_receipt.observation_digest)
                        = (cancellation_observation.request_id, cancellation_observation.attempt_id,
                            cancellation_observation.evidence_digest)
                JOIN subscription_lifecycle_operations canceled_operation
                    ON canceled_operation.request_id = canceled_receipt.request_id
                    AND canceled_operation.subscription_id = canceled_receipt.subscription_id
$next$;
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one current-receipt cancellation evidence join in late-paid guard';
    END IF;
    definition := replace(definition, previous_clause, next_clause);

    previous_clause := $previous$            OR (current_row.superseded_at IS NULL AND current_row.row_version <> NEW.cancellation_row_version)
$previous$;
    next_clause := $next$            OR current_row.row_version < NEW.cancellation_row_version
$next$;
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one current-row version guard in late-paid guard';
    END IF;
    definition := replace(definition, previous_clause, next_clause);

    previous_clause := $previous$                    AND (current_row.superseded_at IS NOT NULL
                        OR canceled_receipt.subscription_row_version = current_row.row_version)
$previous$;
    next_clause := $next$                    AND canceled_receipt.subscription_row_version <= current_row.row_version
                    AND EXISTS (
                        SELECT 1
                        FROM subscription_lifecycle_operations current_operation
                        JOIN subscription_lifecycle_receipts current_receipt
                            ON current_receipt.request_id = current_operation.request_id
                            AND current_receipt.subscription_id = current_operation.subscription_id
                        WHERE current_operation.request_id = current_row.current_receipt_request_id
                            AND current_operation.subscription_id = current_row.subscription_id
                            AND current_operation.state = 'COMMITTED'
                            AND current_operation.provider_subscription_id = first_invoice.provider_subscription_id
                            AND current_receipt.status = 'canceled'
                            AND current_receipt.provider_subscription_id = first_invoice.provider_subscription_id
                            AND current_receipt.subscription_row_version >= canceled_receipt.subscription_row_version
                            AND current_receipt.subscription_row_version <= current_row.row_version
                            AND (current_row.superseded_at IS NOT NULL
                                OR current_receipt.subscription_row_version = current_row.row_version)
                            AND current_receipt.committed_transaction_id <> txid_current()
                    )
$next$;
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one cancellation receipt row-version guard in late-paid guard';
    END IF;
    definition := replace(definition, previous_clause, next_clause);

    previous_clause := $previous$        IF NEW.cancellation_request_id IS DISTINCT FROM current_row.current_receipt_request_id
            OR NEW.cancellation_committed_transaction_id = txid_current()
$previous$;
    next_clause := $next$        IF NEW.cancellation_committed_transaction_id = txid_current()
$next$;
    IF length(definition) - length(replace(definition, previous_clause, '')) <> length(previous_clause) THEN
        RAISE EXCEPTION 'Expected one original-current cancellation identity guard in late-paid guard';
    END IF;
    definition := replace(definition, previous_clause, next_clause);

    EXECUTE definition;
END;
$migration$;
-- [jooq ignore stop]
