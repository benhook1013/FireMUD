ALTER TABLE revision
    ADD COLUMN version_id BIGINT;

UPDATE revision r
SET version_id = (
    SELECT v.id
    FROM version v
    WHERE v.tenant_id = r.tenant_id
    ORDER BY v.created_at DESC, v.id DESC
    LIMIT 1
);

ALTER TABLE revision
    ALTER COLUMN version_id SET NOT NULL;

ALTER TABLE revision
    ADD COLUMN revision_kind VARCHAR(64) NOT NULL DEFAULT 'GENERIC',
    ADD COLUMN logical_revision_id VARCHAR(128);

CREATE INDEX idx_revision_tenant_version ON revision (tenant_id, version_id);
CREATE INDEX idx_revision_logical_key ON revision (tenant_id, version_id, revision_kind, logical_revision_id);
