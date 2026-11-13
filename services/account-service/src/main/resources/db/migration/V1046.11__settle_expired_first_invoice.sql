-- Keep the exact raw Stripe terminal status in the unpaid first-invoice evidence.
-- [jooq ignore start]
DO $migration$
DECLARE
    target RECORD;
    definition TEXT;
    previous_clause TEXT;
    next_clause TEXT;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('account_first_invoice_no_send_cancellation_consistency()', 'observation'),
            ('account_subscription_provisioning_consistency()', 'observation'),
            ('account_first_invoice_late_paid_conflict_guard()', 'cancellation_observation'),
            ('account_first_invoice_late_paid_conflict_guard()', 'witness_observation')
        ) AS guards(routine, observation_alias)
    LOOP
        SELECT pg_get_functiondef((quote_ident(current_schema()) || '.' || target.routine)::regprocedure)
            INTO STRICT definition;
        previous_clause := 'AND ' || target.observation_alias || '.raw_status = ''canceled''';
        next_clause := 'AND ' || target.observation_alias || '.raw_status IN (''canceled'', ''incomplete_expired'')';
        IF (length(definition) - length(replace(definition, previous_clause, '')))
            / length(previous_clause) <> 1 THEN
            RAISE EXCEPTION 'Missing exact unpaid first-invoice terminal guard in %', target.routine;
        END IF;
        EXECUTE replace(definition, previous_clause, next_clause);
    END LOOP;
END;
$migration$;
-- [jooq ignore stop]
