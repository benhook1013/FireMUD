-- Extend the single append-only abort resolution owner with a disjoint negative purpose.
ALTER TABLE account_gameplay_admission_abort_resolutions
    ALTER COLUMN binding_decision_id DROP NOT NULL;
ALTER TABLE account_gameplay_admission_abort_resolutions
    ADD COLUMN purpose TEXT NOT NULL DEFAULT 'provisional-decision/v1',
    ADD COLUMN transition_id UUID,
    ADD COLUMN abandonment_id UUID,
    ADD COLUMN candidate_sha256 VARCHAR(64),
    ADD COLUMN closure_readback_sha256 VARCHAR(64),
    ADD CONSTRAINT account_abort_resolution_purpose_shape CHECK (
        (purpose = 'provisional-decision/v1' AND binding_decision_id IS NOT NULL
            AND transition_id IS NULL AND abandonment_id IS NULL
            AND candidate_sha256 IS NULL AND closure_readback_sha256 IS NULL)
        OR
        (purpose = 'original-new-never-admitted-closure/v1' AND binding_decision_id IS NULL
            AND transition_id IS NOT NULL AND abandonment_id IS NOT NULL
            AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND abandonment_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND candidate_sha256 IS NOT NULL AND candidate_sha256 ~ '^[0-9a-f]{64}$'
            AND closure_readback_sha256 IS NOT NULL AND closure_readback_sha256 ~ '^[0-9a-f]{64}$')
    );
-- The existing immutable resolution trigger retains exact original ABORTED/null-decision linkage
-- and requires writable SERIALIZABLE Account-owner locking for both purposes.
