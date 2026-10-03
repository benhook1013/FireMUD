-- Preserve the original V1 pending-only diagnostic rule while retaining a later unavailable
-- attempt when a V2 canonical JOIN recovers its already-bound available policy result.
ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_pending_attempt_failure_check;

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_pending_attempt_failure_check
        CHECK (last_attempt_failure_code IS NULL
            OR (status = 'PENDING' AND btrim(last_attempt_failure_code) <> '')
            OR (operation_representation_version = 2
                AND status = 'COMMITTED'
                AND entitlement_authority_availability = 'AVAILABLE'
                AND entitlement_version IS NOT NULL
                AND entitlement_version > 0
                AND allow_public_join IS TRUE
                AND request_digest_version IS NOT NULL
                AND request_digest_version = 2
                AND request_digest IS NOT NULL
                AND request_digest ~ '^sha256:[0-9a-f]{64}$'
                AND last_attempt_authority_availability = 'UNAVAILABLE'
                AND btrim(last_attempt_failure_code) <> ''));
