-- Preserve the complete immutable Game Design receipt on new Game Session associations.
-- Historical V25 rows remain nullable and are not reconstructed from their partial columns.
ALTER TABLE game_session_fresh_tenant_association
    ADD COLUMN complete_source_evidence JSONB;

-- [jooq ignore start]
CREATE FUNCTION validate_game_session_fresh_tenant_complete_source_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    evidence JSONB;
BEGIN
    evidence := NEW.complete_source_evidence;
    IF evidence IS NULL THEN
        RAISE EXCEPTION 'Fresh Game Session tenant association requires complete source evidence'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_fresh_tenant_complete_source_evidence';
    END IF;
    IF jsonb_typeof(evidence) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Fresh Game Session tenant source evidence must be an object'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_fresh_tenant_complete_source_evidence';
    END IF;
    IF (SELECT COUNT(*) FROM jsonb_object_keys(evidence)) <> 10
       OR NOT (evidence ?& ARRAY[
           'schemaVersion', 'targetNamespace', 'creationRequestId', 'operationId',
           'requestDigest', 'canonicalTenantId', 'sourceGameRowId',
           'sourceGameTenantKey', 'provenanceKind', 'evidenceDigest'
       ]) THEN
        RAISE EXCEPTION 'Fresh Game Session tenant source evidence has an incomplete or open shape'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_fresh_tenant_complete_source_evidence';
    END IF;

    IF jsonb_typeof(evidence -> 'schemaVersion') IS DISTINCT FROM 'number'
       OR evidence ->> 'schemaVersion' IS DISTINCT FROM NEW.source_schema_version::TEXT
       OR jsonb_typeof(evidence -> 'targetNamespace') IS DISTINCT FROM 'string'
       OR evidence ->> 'targetNamespace' IS DISTINCT FROM NEW.source_target_namespace
       OR NEW.source_target_namespace IS DISTINCT FROM NEW.target_namespace
       OR jsonb_typeof(evidence -> 'creationRequestId') IS DISTINCT FROM 'string'
       OR evidence ->> 'creationRequestId' IS DISTINCT FROM NEW.source_request_id::TEXT
       OR NEW.source_request_id IS DISTINCT FROM NEW.association_request_id
       OR jsonb_typeof(evidence -> 'operationId') IS DISTINCT FROM 'string'
       OR (evidence ->> 'operationId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR evidence ->> 'operationId' = '00000000-0000-0000-0000-000000000000'
       OR jsonb_typeof(evidence -> 'requestDigest') IS DISTINCT FROM 'string'
       OR (evidence ->> 'requestDigest') !~ '^sha256:[0-9a-f]{64}$'
       OR jsonb_typeof(evidence -> 'canonicalTenantId') IS DISTINCT FROM 'string'
       OR evidence ->> 'canonicalTenantId' IS DISTINCT FROM NEW.source_canonical_tenant_id::TEXT
       OR NEW.source_canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
       OR jsonb_typeof(evidence -> 'sourceGameRowId') IS DISTINCT FROM 'number'
       OR evidence ->> 'sourceGameRowId' IS DISTINCT FROM NEW.source_game_row_id::TEXT
       OR jsonb_typeof(evidence -> 'sourceGameTenantKey') IS DISTINCT FROM 'string'
       OR evidence ->> 'sourceGameTenantKey' IS DISTINCT FROM NEW.source_game_tenant_key
       OR jsonb_typeof(evidence -> 'provenanceKind') IS DISTINCT FROM 'string'
       OR evidence ->> 'provenanceKind' IS DISTINCT FROM NEW.provenance_kind
       OR jsonb_typeof(evidence -> 'evidenceDigest') IS DISTINCT FROM 'string'
       OR (evidence ->> 'evidenceDigest') !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Fresh Game Session tenant source evidence differs from its immutable receipt columns'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_fresh_tenant_complete_source_evidence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_fresh_tenant_complete_source_evidence_guard
    BEFORE INSERT ON game_session_fresh_tenant_association
    FOR EACH ROW EXECUTE FUNCTION validate_game_session_fresh_tenant_complete_source_evidence();
-- [jooq ignore stop]
