-- Retain the V35 function identity, access grants and complete original guard body. Add the
-- immutable release/checkpoint join before either retry readback or runtime allocation.
-- [jooq ignore start]
DO $migration$
DECLARE
    preparation_function REGPROCEDURE :=
        '"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE;
    original_definition TEXT;
    insertion_point TEXT := $anchor$    IF identity_row.operation_id::TEXT IS DISTINCT FROM topology->>'versionIdentityOperationId'$anchor$;
    join_guard TEXT := $guard$    IF binding_row.release_attestation_json::JSONB->>'targetNamespace'
            IS DISTINCT FROM capture_row.freeze_request_json::JSONB->>'targetNamespace'
        OR binding_row.release_attestation_json::JSONB->>'targetNamespace'
            IS DISTINCT FROM identity_row.target_namespace
        OR binding_row.release_attestation_json::JSONB->>'canonicalTenantId'
            IS DISTINCT FROM identity_row.canonical_tenant_id::TEXT
        OR binding_row.release_attestation_json::JSONB->>'canonicalTenantId'
            IS DISTINCT FROM capture_row.freeze_request_json::JSONB->>'canonicalTenantId'
        OR binding_row.release_attestation_json::JSONB->>'canonicalVersionId'
            IS DISTINCT FROM identity_row.canonical_version_id::TEXT
        OR binding_row.release_attestation_json::JSONB->>'canonicalVersionId'
            IS DISTINCT FROM capture_row.freeze_request_json::JSONB->>'canonicalVersionId'
        OR binding_row.release_attestation_json::JSONB->>'canonicalVersionId'
            IS DISTINCT FROM capture_row.binding_json::JSONB->>'canonicalVersionId'
        OR binding_row.release_attestation_json::JSONB->>'canonicalTenantId'
            IS DISTINCT FROM capture_row.binding_json::JSONB->>'canonicalTenantId'
        OR binding_row.release_attestation_json::JSONB->>'commitId'
            IS DISTINCT FROM capture_row.commit_id::TEXT
        OR binding_row.release_attestation_json::JSONB->>'commitId'
            IS DISTINCT FROM capture_row.binding_json::JSONB->>'commitId'
        OR binding_row.release_attestation_json::JSONB->>'commitId'
            IS DISTINCT FROM capture_row.freeze_request_json::JSONB->>'appliedCommitId'
        OR binding_row.release_attestation_json::JSONB->>'publishWorkflowId'
            IS DISTINCT FROM capture_row.freeze_request_json::JSONB->>'publishWorkflowId'
        OR (SELECT count(*) FROM jsonb_array_elements(
                CASE WHEN jsonb_typeof(binding_row.release_attestation_json::JSONB->'participantDigests') = 'array'
                    THEN binding_row.release_attestation_json::JSONB->'participantDigests'
                    ELSE '[]'::JSONB END) participant
            WHERE participant->>'participantKey' = 'WORLD_MANAGEMENT') <> 1
        OR NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(
                CASE WHEN jsonb_typeof(binding_row.release_attestation_json::JSONB->'participantDigests') = 'array'
                    THEN binding_row.release_attestation_json::JSONB->'participantDigests'
                    ELSE '[]'::JSONB END) participant
            WHERE participant->>'participantKey' = 'WORLD_MANAGEMENT'
                AND participant->>'scopeValue' = identity_row.game_design_version_id::TEXT
                AND participant->>'baseVersionIdPresent' = 'false'
                AND participant->'baseVersionId' = 'null'::JSONB
                AND participant->>'appliedCommitId' = capture_row.freeze_request_json::JSONB->>'appliedCommitId'
                AND participant->>'contentDigest' = capture_row.freeze_request_json::JSONB->>'contentDigest'
                AND participant->>'digestSchemaVersion' = capture_row.freeze_request_json::JSONB->>'digestSchemaVersion'
                AND participant->>'digestSchemaVersion' = '3'
        ) THEN
        RAISE EXCEPTION 'Canonical preparation release differs from the exact selected frozen World graph'
            USING ERRCODE = '23514';
    END IF;

$guard$;
BEGIN
    SELECT pg_get_functiondef(preparation_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, insertion_point, '')))
            / length(insertion_point) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V35 canonical preparation release-join insertion point';
    END IF;
    EXECUTE replace(original_definition, insertion_point, join_guard || insertion_point);
END;
$migration$;
-- [jooq ignore stop]
