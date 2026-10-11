-- The Account capture source fence is independent from the owner issuance fence.
-- Preserve the canonical positive BIGINT decimal carried by the immutable bundle reference.
-- jOOQ's H2 model gives unnamed historical checks non-portable generated names.
-- [jooq ignore start]
ALTER TABLE account_start_session_operator_authorizations
    DROP CONSTRAINT account_start_session_operator_authorizations_bundle_source_fence_check;

ALTER TABLE account_start_session_operator_authorizations
    ADD CONSTRAINT account_ss_operator_bundle_source_fence_canonical_check
    CHECK (bundle_source_fence ~ '^[1-9][0-9]{0,18}$'
        AND bundle_source_fence::NUMERIC <= 9223372036854775807);
-- [jooq ignore end]
