ALTER TABLE publish_attempt
    ADD COLUMN base_version_id BIGINT,
    ADD COLUMN request_digest VARCHAR(64);

-- Existing SCRIPT_PATCH attempts predate the durable base/request binding.  The
-- base can be recovered only when the attempt's tenant, version id, and patch
-- identity point at one exact script-only version row.  In particular, do not
-- infer a base from a tenant-wide patch lookup or derive a request digest from
-- an old workflow UUID.
UPDATE publish_attempt AS attempt
SET base_version_id = (
    SELECT version_row.base_version_id
    FROM version AS version_row
    WHERE version_row.id = attempt.version_id
      AND version_row.tenant_id = attempt.tenant_id
      AND version_row.is_script_only = TRUE
      AND version_row.script_patch_version = attempt.script_patch_version
      AND version_row.base_version_id IS NOT NULL
)
WHERE attempt.publish_type = 'SCRIPT_PATCH'
  AND attempt.base_version_id IS NULL
  AND attempt.version_id IS NOT NULL
  AND attempt.script_patch_version IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM version AS version_row
      WHERE version_row.id = attempt.version_id
        AND version_row.tenant_id = attempt.tenant_id
        AND version_row.is_script_only = TRUE
        AND version_row.script_patch_version = attempt.script_patch_version
        AND version_row.base_version_id IS NOT NULL
  );

-- A legacy patch attempt without the complete stable request identity cannot
-- be made safely retryable.  Quarantine it as retained terminal evidence;
-- neither the attempt nor its candidate version is deleted.  A request digest
-- is intentionally not fabricated, even when an old workflow string happens
-- to look similar to the current identity.
UPDATE publish_attempt
SET status = 'FAILED',
    failure_code = 'LEGACY_REQUEST_IDENTITY_UNAVAILABLE',
    failure_message = 'legacy script-patch attempt lacks a stable publish request identity',
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
WHERE publish_type = 'SCRIPT_PATCH'
  AND status = 'PENDING'
  AND (
      request_digest IS NULL
      OR publish_workflow_id IS NULL
      OR publish_workflow_id !~ '^publish-script-patch:[^:]+:publish-request:[^:]+$'
  );
