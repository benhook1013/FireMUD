-- V13 inferred PUBLISHED for every retained version because it had no
-- publication evidence to consult.  A full version is publish-complete only
-- when its exact tenant/version release bundle exists, so retain (but
-- quarantine) the unattested legacy rows for later investigation.
--
-- The V13 epoch=1 marker makes this correction narrow and replay-safe.  Do not
-- touch script-only publication rows: their publication evidence follows the
-- script-patch lifecycle rather than the full-version release bundle.
UPDATE version AS version_row
SET version_state = 'FAILED',
    version_state_epoch = version_row.version_state_epoch + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE version_row.is_script_only = FALSE
  AND version_row.version_state = 'PUBLISHED'
  AND version_row.version_state_epoch = 1
  AND NOT EXISTS (
      SELECT 1
      FROM published_release_bundle AS bundle
      WHERE bundle.tenant_id = version_row.tenant_id
        AND bundle.version_id = version_row.id
  );
