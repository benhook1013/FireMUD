-- The base/patch tuple is the effective identity of a script-only artifact. Do
-- not choose a winner or delete retained rows while installing that invariant.
-- A failed migration leaves the existing evidence available for adjudication.
SELECT CAST(NULLIF(
    CASE
      WHEN EXISTS (
          SELECT 1
          FROM version
          WHERE is_script_only = TRUE
            AND (
                base_version_id IS NULL
                OR base_version_id <= 0
                OR script_patch_version IS NULL
                OR script_patch_version = ''
            )
      ) THEN 'V27 script-only version has incomplete effective artifact identity'
      WHEN EXISTS (
          SELECT 1
          FROM version
          WHERE is_script_only = TRUE
          GROUP BY tenant_id, base_version_id, script_patch_version
          HAVING COUNT(*) > 1
      ) THEN 'V27 duplicate retained script-only effective artifact identity'
      ELSE '1'
    END,
    '1'
) AS INTEGER);

ALTER TABLE version
    ADD CONSTRAINT chk_script_only_effective_artifact_identity
    CHECK (
        is_script_only = FALSE
        OR (
            base_version_id IS NOT NULL
            AND base_version_id > 0
            AND script_patch_version IS NOT NULL
            AND script_patch_version <> ''
        )
    );

-- Full-version rows are intentionally excluded and retain their existing
-- identity semantics. PostgreSQL's partial unique index makes reservation
-- atomic while the service-level tenant lock provides the deterministic
-- mutation-free conflict response.
CREATE UNIQUE INDEX uq_version_script_patch_effective_artifact
    ON version (tenant_id, base_version_id, script_patch_version)
    WHERE is_script_only = TRUE;
