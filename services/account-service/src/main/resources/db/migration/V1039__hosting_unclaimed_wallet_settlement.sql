-- A wallet observation can invalidate dispatch before the first worker claim. Retain a terminal
-- operator item without asserting provider absence, canceling the intent, or releasing its hold.
-- [jooq ignore start]
ALTER TABLE subscription_provisioning_operator_outbox
    DROP CONSTRAINT subscription_provisioning_operator_outbox_reason_check;
ALTER TABLE subscription_provisioning_operator_outbox
    ADD CONSTRAINT subscription_provisioning_operator_outbox_reason_check
    CHECK (reason IN ('ABSOLUTE_DEADLINE', 'ATTEMPT_BUDGET',
        'STALLED_WITHOUT_COMMITTED_PROGRESS', 'RESERVED_INSTRUMENT_CHANGED'));
-- [jooq ignore stop]
