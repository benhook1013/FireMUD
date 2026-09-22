ALTER TABLE publish_attempt_participant_digest
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE publish_recorded_participant_digest
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE publish_recorded_participant_digest
    DROP CONSTRAINT uq_recorded_participant_digest;

-- Full-version records retain their existing tenant/type/participant/scope/commit identity.
-- Patch records add the positive base version to that identity. Partial indexes keep the
-- nullable full-version column from making distinct rows compare equal through NULL semantics.
CREATE UNIQUE INDEX uq_recorded_participant_digest_full
    ON publish_recorded_participant_digest (
        tenant_id,
        publish_type,
        participant_key,
        scope_value,
        applied_commit_id
    )
    WHERE base_version_id IS NULL;

CREATE UNIQUE INDEX uq_recorded_participant_digest_patch
    ON publish_recorded_participant_digest (
        tenant_id,
        publish_type,
        participant_key,
        base_version_id,
        scope_value,
        applied_commit_id
    )
    WHERE base_version_id IS NOT NULL;
