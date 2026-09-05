-- Canonicalize legacy script-only work-item provenance before making the identity fields required.
-- Plugin-backed rows retain their exact IDs; only absent plugin identity becomes the empty sentinel.
UPDATE script_work_items
SET plugin_id = COALESCE(plugin_id, ''),
    plugin_version_id = COALESCE(plugin_version_id, '')
WHERE plugin_id IS NULL OR plugin_version_id IS NULL;

ALTER TABLE script_work_items
    ALTER COLUMN plugin_id SET DEFAULT '';

ALTER TABLE script_work_items
    ALTER COLUMN plugin_version_id SET DEFAULT '';

ALTER TABLE script_work_items
    ALTER COLUMN plugin_id SET NOT NULL;

ALTER TABLE script_work_items
    ALTER COLUMN plugin_version_id SET NOT NULL;
