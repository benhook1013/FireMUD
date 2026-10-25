-- Immutable tenant-to-tag assignments for tenant-local gameplay Redis key co-location.
-- The mapping provides topology only; it is not admission or storage-incarnation evidence.
CREATE TABLE game_session_canonical_tenant_gameplay_tag (
    tenant_id UUID NOT NULL,
    allocation_version INTEGER NOT NULL,
    tenant_gameplay_tag VARCHAR(44) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_tenant_gameplay_tag PRIMARY KEY (tenant_id),
    CONSTRAINT uq_gs_canonical_tenant_gameplay_tag_value UNIQUE (tenant_gameplay_tag),
    CONSTRAINT chk_gs_canonical_tenant_gameplay_tag CHECK (
        tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND allocation_version > 0
        AND tenant_gameplay_tag ~ '^g[1-9][0-9]{0,9}-[0-9a-f]{32}$'
        AND octet_length(tenant_gameplay_tag) BETWEEN 35 AND 44)
);

-- [jooq ignore start]
CREATE FUNCTION reject_gs_canonical_tenant_gameplay_tag_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Canonical tenant gameplay tag assignments are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER gs_canonical_tenant_gameplay_tag_immutable
BEFORE UPDATE OR DELETE ON game_session_canonical_tenant_gameplay_tag
FOR EACH ROW EXECUTE FUNCTION reject_gs_canonical_tenant_gameplay_tag_mutation();

CREATE TRIGGER gs_canonical_tenant_gameplay_tag_no_truncate
BEFORE TRUNCATE ON game_session_canonical_tenant_gameplay_tag
FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_canonical_tenant_gameplay_tag_mutation();
-- [jooq ignore stop]
