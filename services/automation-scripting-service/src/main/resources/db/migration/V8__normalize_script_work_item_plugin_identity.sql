-- Canonicalize legacy script-only work-item provenance before making the identity fields required.
-- Plugin-backed rows retain their exact pair. A partial pair is not authoritative provenance, so
-- normalize both fields to the empty script-only sentinel before enforcing the tuple columns.
UPDATE script_work_items
SET plugin_id = CASE
        WHEN NULLIF(BTRIM(plugin_id), '') IS NULL
          OR NULLIF(BTRIM(plugin_version_id), '') IS NULL THEN ''
        ELSE plugin_id
    END,
    plugin_version_id = CASE
        WHEN NULLIF(BTRIM(plugin_id), '') IS NULL
          OR NULLIF(BTRIM(plugin_version_id), '') IS NULL THEN ''
        ELSE plugin_version_id
    END
WHERE NULLIF(BTRIM(plugin_id), '') IS NULL
   OR NULLIF(BTRIM(plugin_version_id), '') IS NULL;

ALTER TABLE script_work_items
    ALTER COLUMN plugin_id SET DEFAULT '';

ALTER TABLE script_work_items
    ALTER COLUMN plugin_version_id SET DEFAULT '';

ALTER TABLE script_work_items
    ALTER COLUMN plugin_id SET NOT NULL;

ALTER TABLE script_work_items
    ALTER COLUMN plugin_version_id SET NOT NULL;
