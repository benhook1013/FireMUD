-- Extend the retained owner with disjoint historical creation provenance. No old bytes change.
-- [jooq ignore start]
CREATE FUNCTION world_player_created_origin_valid(origin JSONB)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE
SET search_path = pg_catalog
AS $$
    SELECT (jsonb_typeof(origin) = 'object'
        AND (SELECT count(*) FROM jsonb_object_keys(origin)) = 5
        AND origin ?& ARRAY['targetNamespace','creationOperationId','mutationDigest','terminalId','terminalSha256']
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(origin) p WHERE jsonb_typeof(p.value) <> 'string')
        AND length(origin->>'targetNamespace') BETWEEN 1 AND 63
        AND origin->>'targetNamespace' ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        AND origin->>'creationOperationId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND origin->>'terminalId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND origin->>'creationOperationId' <> '00000000-0000-0000-0000-000000000000'
        AND origin->>'terminalId' <> '00000000-0000-0000-0000-000000000000'
        AND origin->>'mutationDigest' ~ '^[0-9a-f]{64}$'
        AND origin->>'terminalSha256' ~ '^[0-9a-f]{64}$') IS TRUE;
$$;
REVOKE ALL ON FUNCTION world_player_created_origin_valid(JSONB) FROM PUBLIC;
-- [jooq ignore stop]

ALTER TABLE character_location
    ALTER COLUMN entity_assignment_operation_id DROP NOT NULL,
    ALTER COLUMN entity_assignment_digest DROP NOT NULL,
    ADD COLUMN entity_created_origin JSONB;

-- [jooq ignore start]
ALTER TABLE character_location
    ADD CONSTRAINT ck_world_character_location_origin CHECK ((
        (entity_created_origin IS NULL AND entity_assignment_operation_id IS NOT NULL
            AND entity_assignment_digest IS NOT NULL)
        OR (entity_assignment_operation_id IS NULL AND entity_assignment_digest IS NULL
            AND world_player_created_origin_valid(entity_created_origin))
    ) IS TRUE);
CREATE UNIQUE INDEX uq_world_character_created_operation ON character_location
    (canonical_tenant_id,playable_state_namespace_id,canonical_game_instance_id,
        (entity_created_origin->>'targetNamespace'),(entity_created_origin->>'creationOperationId'))
    WHERE entity_created_origin IS NOT NULL;

-- Amend exact live function anchors so every prior lifecycle, hold, room, checksum,
-- immutability, fixture refusal and retention guard survives without reconstruction.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT;
    replacement TEXT;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_guard_initial_location_operation()'::REGPROCEDURE)
        INTO STRICT original;
    anchor := $anchor$request->>'schema' IS DISTINCT FROM 'world-canonical-initial-player-location-request/v1'$anchor$;
    replacement := $replacement$(request->>'schema' IS NULL OR request->>'schema' NOT IN
        ('world-canonical-initial-player-location-request/v1','world-canonical-initial-player-location-request/v2'))$replacement$;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact initial-location request schema guard';
    END IF;
    original := replace(original,anchor,replacement);
    anchor := $anchor$    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;$anchor$;
    replacement := $replacement$    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(request) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Initial-location request must be a closed object' USING ERRCODE = '23514';
    END IF;
    IF NOT (request ?& ARRAY['activeLifecycleEvidence','canonicalAccountId','canonicalGameInstanceId','canonicalTenantId','catalogRevision','characterId','initialAdmissionHoldFence','initialAdmissionHoldId','initialAdmissionOrigin','initialAdmissionOwnerProofDigest','initialAdmissionOwnerProofId','initialAdmissionRequestDigest','initialAdmissionRequestId','operationId','playableStateNamespaceId','playableStateScope','pointerAuditId','pointerVersion','realmId','schema','worldSlug'])
        OR EXISTS (SELECT 1 FROM jsonb_each(request) p
            WHERE p.key <> 'activeLifecycleEvidence' AND p.key <> 'entityCreatedOrigin'
                AND jsonb_typeof(p.value) <> 'string')
        OR NOT (
            (request->>'schema' = 'world-canonical-initial-player-location-request/v1'
                AND (SELECT count(*) FROM jsonb_object_keys(request)) = 23
                AND request ?& ARRAY['entityAssignmentOperationId','entityAssignmentDigest']
                AND NOT request ? 'entityCreatedOrigin'
                AND request->>'entityAssignmentOperationId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                AND request->>'entityAssignmentOperationId' <> '00000000-0000-0000-0000-000000000000'
                AND request->>'entityAssignmentDigest' ~ '^[0-9a-f]{64}$')
            OR (request->>'schema' = 'world-canonical-initial-player-location-request/v2'
                AND (SELECT count(*) FROM jsonb_object_keys(request)) = 22
                AND NOT request ? 'entityAssignmentOperationId' AND NOT request ? 'entityAssignmentDigest'
                AND "${serviceSchema}".world_player_created_origin_valid(request->'entityCreatedOrigin')
                AND request->'entityCreatedOrigin'->>'targetNamespace' =
                    request->'activeLifecycleEvidence'->'request'->>'targetNamespace')
        ) IS TRUE THEN
        RAISE EXCEPTION 'Initial-location request requires exactly one closed actor origin' USING ERRCODE = '23514';
    END IF;$replacement$;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact initial-location request origin validation anchor';
    END IF;
    EXECUTE replace(original,anchor,replacement);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$OR request->>'entityAssignmentDigest' IS DISTINCT FROM NEW.entity_assignment_digest$anchor$;
    replacement TEXT := $replacement$OR request->>'entityAssignmentDigest' IS DISTINCT FROM NEW.entity_assignment_digest
        OR request->'entityCreatedOrigin' IS DISTINCT FROM NEW.entity_created_origin$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_guard_initial_character_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact initial-character origin binding guard';
    END IF;
    EXECUTE replace(original,anchor,replacement);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$AND l.entity_assignment_operation_id = (convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'entityAssignmentOperationId')::UUID
            AND l.entity_assignment_digest = convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'entityAssignmentDigest'$anchor$;
    replacement TEXT := $replacement$AND l.entity_assignment_operation_id IS NOT DISTINCT FROM (request->>'entityAssignmentOperationId')::UUID
            AND l.entity_assignment_digest IS NOT DISTINCT FROM request->>'entityAssignmentDigest'
            AND l.entity_created_origin IS NOT DISTINCT FROM request->'entityCreatedOrigin'$replacement$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_require_initial_location_operation_result()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact atomic initial-character origin guard';
    END IF;
    EXECUTE replace(original,anchor,replacement);
END;
$migration$;
-- [jooq ignore stop]
