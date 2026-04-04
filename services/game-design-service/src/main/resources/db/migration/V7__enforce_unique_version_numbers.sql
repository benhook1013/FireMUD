WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY id) AS version_rank
    FROM version
)
UPDATE version AS v
SET version_number = ranked.version_rank
FROM ranked
WHERE v.id = ranked.id;

ALTER TABLE version
    ALTER COLUMN version_number DROP DEFAULT;

ALTER TABLE version
    ADD CONSTRAINT uq_version_tenant_version_number UNIQUE (tenant_id, version_number);
