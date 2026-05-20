UPDATE version AS v
SET version_number = (
    SELECT COUNT(*)
    FROM version AS ranked
    WHERE ranked.tenant_id = v.tenant_id
      AND ranked.id <= v.id
);

ALTER TABLE version
    ALTER COLUMN version_number DROP DEFAULT;

ALTER TABLE version
    ADD CONSTRAINT uq_version_tenant_version_number UNIQUE (tenant_id, version_number);
