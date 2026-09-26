ALTER TABLE publish_attempt_participant_digest
    ADD COLUMN base_version_id BIGINT;

ALTER TABLE publish_recorded_participant_digest
    ADD COLUMN base_version_id BIGINT;

-- Retained participant evidence belongs to the exact patch attempt that produced
-- it.  The parent attempt is the only authoritative source for that scope; do
-- not recover it from a tenant-wide patch lookup.
UPDATE publish_attempt_participant_digest AS participant
SET base_version_id = (
    SELECT attempt.base_version_id
    FROM publish_attempt AS attempt
    WHERE attempt.id = participant.publish_attempt_id
      AND attempt.publish_type = 'SCRIPT_PATCH'
      AND attempt.base_version_id IS NOT NULL
      AND attempt.base_version_id > 0
)
WHERE participant.base_version_id IS NULL
  AND EXISTS (
      SELECT 1
      FROM publish_attempt AS attempt
      WHERE attempt.id = participant.publish_attempt_id
        AND attempt.publish_type = 'SCRIPT_PATCH'
        AND attempt.base_version_id IS NOT NULL
        AND attempt.base_version_id > 0
  );

-- Recorded digests predate the explicit base column.  A patch scope can be
-- recovered only when the retained published script-only versions identify one
-- positive base for the exact tenant and patch.  Ambiguous or missing evidence
-- is deliberately left unresolved and rejected below.
UPDATE publish_recorded_participant_digest AS recorded
SET base_version_id = (
    SELECT MIN(version_row.base_version_id)
    FROM version AS version_row
    WHERE version_row.tenant_id = recorded.tenant_id
      AND version_row.script_patch_version = recorded.scope_value
      AND version_row.is_script_only = TRUE
      AND version_row.version_state = 'PUBLISHED'
      AND version_row.base_version_id IS NOT NULL
      AND version_row.base_version_id > 0
    GROUP BY version_row.tenant_id, version_row.script_patch_version
    HAVING COUNT(DISTINCT version_row.base_version_id) = 1
)
WHERE recorded.publish_type = 'SCRIPT_PATCH'
  AND recorded.base_version_id IS NULL
  AND (
      SELECT COUNT(DISTINCT version_row.base_version_id)
      FROM version AS version_row
      WHERE version_row.tenant_id = recorded.tenant_id
        AND version_row.script_patch_version = recorded.scope_value
        AND version_row.is_script_only = TRUE
        AND version_row.version_state = 'PUBLISHED'
        AND version_row.base_version_id IS NOT NULL
        AND version_row.base_version_id > 0
  ) = 1;

-- Do not silently leave retained patch evidence unscoped.  These scalar guards
-- deliberately fail the migration when the subquery finds unresolved rows;
-- they use plain SQL so the repository's jOOQ/H2 schema parser exercises the
-- same migration ordering.  Full-version rows intentionally retain NULL
-- base_version_id and are excluded from both guards.
SELECT CAST(NULLIF(
    CASE WHEN EXISTS (
        SELECT 1
        FROM publish_attempt_participant_digest AS participant
        JOIN publish_attempt AS attempt
          ON attempt.id = participant.publish_attempt_id
        WHERE attempt.publish_type = 'SCRIPT_PATCH'
          AND participant.base_version_id IS NULL
    ) THEN 'V26 unresolved SCRIPT_PATCH attempt participant evidence'
      ELSE '1' END,
    '1'
) AS INTEGER);

SELECT CAST(NULLIF(
    CASE WHEN EXISTS (
        SELECT 1
        FROM publish_recorded_participant_digest AS recorded
        WHERE recorded.publish_type = 'SCRIPT_PATCH'
          AND recorded.base_version_id IS NULL
    ) THEN 'V26 unresolved SCRIPT_PATCH recorded participant evidence'
      ELSE '1' END,
    '1'
) AS INTEGER);

ALTER TABLE publish_recorded_participant_digest
    ADD CONSTRAINT chk_recorded_participant_digest_patch_scope
    CHECK (publish_type <> 'SCRIPT_PATCH' OR base_version_id IS NOT NULL);

ALTER TABLE publish_recorded_participant_digest
    ADD CONSTRAINT chk_recorded_participant_digest_full_scope
    CHECK (publish_type <> 'FULL_VERSION' OR base_version_id IS NULL);

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
