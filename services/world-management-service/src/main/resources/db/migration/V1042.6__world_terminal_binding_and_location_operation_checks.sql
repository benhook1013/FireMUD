-- Keep terminal binding evidence complete and APPLIED placement bound to its own operation.
-- Published V1010/V1019 bytes and all other guards remain unchanged.
-- [jooq ignore start]
ALTER TABLE world_draft_terminal_outcome DROP CONSTRAINT ck_world_draft_terminal_binding;
ALTER TABLE world_draft_terminal_outcome ADD CONSTRAINT ck_world_draft_terminal_binding CHECK ((
    jsonb_typeof(binding_json::jsonb) = 'object'
    AND binding_json::jsonb->>'canonicalTenantId' = canonical_tenant_id::TEXT
    AND binding_json::jsonb->>'canonicalVersionId' = canonical_version_id::TEXT
    AND binding_json::jsonb->>'requestId' = request_id::TEXT
    AND binding_json::jsonb->>'commitId' = commit_id::TEXT
    AND jsonb_typeof(owner_binding_json::jsonb) = 'object'
    AND owner_binding_json::jsonb->>'targetNamespace' = target_namespace
    AND owner_binding_json::jsonb->>'canonicalTenantId' = canonical_tenant_id::TEXT
    AND owner_binding_json::jsonb->>'canonicalVersionId' = canonical_version_id::TEXT
    AND owner_binding_json::jsonb->>'versionIdentityOperationId' = version_identity_operation_id::TEXT
) IS TRUE);

DO $migration$
DECLARE
    original TEXT;
    location_anchor TEXT := $anchor$AND l.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND l.canonical_account_id = (convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'canonicalAccountId')::UUID$anchor$;
    location_replacement TEXT := $replacement$AND l.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND l.initial_location_operation_id = NEW.operation_id
            AND l.canonical_account_id = (convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'canonicalAccountId')::UUID$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_require_initial_location_operation_result()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,location_anchor,'')))/length(location_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one initial-location APPLIED operation binding anchor';
    END IF;
    EXECUTE replace(original, location_anchor, location_replacement);
END;
$migration$;
-- [jooq ignore stop]
