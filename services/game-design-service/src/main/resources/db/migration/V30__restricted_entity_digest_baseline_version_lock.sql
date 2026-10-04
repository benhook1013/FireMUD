-- [jooq ignore start]
CREATE FUNCTION game_design_service.lock_version_for_entity_digest_baseline_migration(
    p_tenant_id VARCHAR(36),
    p_version_id BIGINT
)
RETURNS TABLE (
    id BIGINT,
    tenant_id VARCHAR(36),
    version_number INTEGER,
    version_state VARCHAR(32),
    version_state_epoch BIGINT,
    script_patch_version VARCHAR(100),
    base_version_id BIGINT,
    is_script_only BOOLEAN,
    notes VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
)
LANGUAGE SQL
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT
        version_row.id,
        version_row.tenant_id,
        version_row.version_number,
        version_row.version_state,
        version_row.version_state_epoch,
        version_row.script_patch_version,
        version_row.base_version_id,
        version_row.is_script_only,
        version_row.notes,
        version_row.created_at,
        version_row.updated_at
    FROM game_design_service.version AS version_row
    WHERE version_row.tenant_id = p_tenant_id
      AND version_row.id = p_version_id
    FOR UPDATE
$$;

REVOKE EXECUTE ON FUNCTION game_design_service.lock_version_for_entity_digest_baseline_migration(
    VARCHAR(36), BIGINT
) FROM PUBLIC;
-- [jooq ignore stop]
