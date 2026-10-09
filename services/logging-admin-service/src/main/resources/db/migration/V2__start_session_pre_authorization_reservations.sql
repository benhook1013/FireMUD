CREATE TABLE start_session_pre_authorization_reservations (
    control_plane_request_id VARCHAR(128) PRIMARY KEY,
    pre_authorization_tuple_json VARCHAR(8192) NOT NULL,
    mutation_digest VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL
        CHECK (phase = 'ACCOUNT_AUTHORIZATION'),
    state VARCHAR(40) NOT NULL
        CHECK (state IN ('RESERVED', 'AUTHORIZATION_PENDING')),
    reservation_owner_id UUID NOT NULL,
    reservation_claim_fence BIGINT NOT NULL
        CHECK (reservation_claim_fence = 1),
    claim_owner_id UUID,
    claim_fence BIGINT NOT NULL
        CHECK (claim_fence >= reservation_claim_fence),
    claim_expires_at_epoch_ms BIGINT NOT NULL
        CHECK (claim_expires_at_epoch_ms > 0),
    claim_state VARCHAR(16) NOT NULL
        CHECK (claim_state IN ('ACTIVE', 'EXPIRED')),
    created_at_epoch_ms BIGINT NOT NULL
        CHECK (created_at_epoch_ms > 0),
    updated_at_epoch_ms BIGINT NOT NULL
        CHECK (updated_at_epoch_ms > 0),
    CHECK (
        (claim_state = 'ACTIVE' AND claim_owner_id IS NOT NULL)
        OR (claim_state = 'EXPIRED' AND claim_owner_id IS NULL)
    )
);
