-- Extend V43's canonical activation proof to retain and validate the complete operational REGION
-- assignment map added to WorldCanonicalInstanceLifecycleEvidence.
-- [jooq ignore start]
DO $migration$
DECLARE
    definition TEXT;
    original_oid OID;
    original_acl ACLITEM[];
    old_fragment TEXT;
    new_fragment TEXT;
    anchor TEXT;
    replacement TEXT;
BEGIN
    SELECT p.oid, p.proacl
        INTO STRICT original_oid, original_acl
        FROM pg_proc p
        WHERE p.oid = '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE;
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE)
        INTO STRICT definition;

    old_fragment := '(SELECT count(*) FROM jsonb_object_keys(preparing)) <> 11';
    new_fragment := '(SELECT count(*) FROM jsonb_object_keys(preparing)) <> 12';
    IF (length(definition)-length(replace(definition, old_fragment, '')))/length(old_fragment) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one closed PREPARING lifecycle object count in V43 activation guard';
    END IF;
    definition := replace(definition, old_fragment, new_fragment);

    old_fragment := '(SELECT count(*) FROM jsonb_object_keys(normalized_request->''preparingEvidence'')) <> 11';
    new_fragment := '(SELECT count(*) FROM jsonb_object_keys(normalized_request->''preparingEvidence'')) <> 12';
    IF (length(definition)-length(replace(definition, old_fragment, '')))/length(old_fragment) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one closed normalized PREPARING lifecycle object count in V43 activation guard';
    END IF;
    definition := replace(definition, old_fragment, new_fragment);

    old_fragment := '(SELECT count(*) FROM jsonb_object_keys(lifecycle)) <> 11';
    new_fragment := '(SELECT count(*) FROM jsonb_object_keys(lifecycle)) <> 12';
    IF (length(definition)-length(replace(definition, old_fragment, '')))/length(old_fragment) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one closed result lifecycle object count in V43 activation guard';
    END IF;
    definition := replace(definition, old_fragment, new_fragment);

    anchor := $$    IF jsonb_typeof(result) IS DISTINCT FROM 'object'$$;
    IF (length(definition)-length(replace(definition, anchor, '')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one result-evidence validation anchor in V43 activation guard';
    END IF;
    replacement := $replacement$    IF NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_canonical_instance_preparation p
            ON p.canonical_game_instance_id=a.canonical_game_instance_id
        CROSS JOIN LATERAL (
            SELECT count(*) AS row_count,
                count(*) FILTER (WHERE m.id IS NULL OR ri.id IS NULL
                    OR m.world_instance_id IS DISTINCT FROM a.world_instance_id
                    OR m.canonical_game_instance_id IS DISTINCT FROM a.canonical_game_instance_id
                    OR m.family IS DISTINCT FROM 'REGION'
                    OR m.runtime_row_id IS DISTINCT FROM ri.id
                    OR ri.world_instance_id IS DISTINCT FROM a.world_instance_id
                    OR ri.tenant_id IS DISTINCT FROM a.local_tenant_key
                    OR ri.game_instance_id IS DISTINCT FROM a.private_game_instance_key
                    OR m.runtime_identity IS NULL
                    OR m.runtime_identity = '00000000-0000-0000-0000-000000000000'::UUID
                    OR ri.canonical_region_instance_id IS DISTINCT FROM m.runtime_identity
                    OR ri.operational_region_id IS NULL
                    OR ri.operational_region_id = '00000000-0000-0000-0000-000000000000'::UUID
                    OR ri.operational_region_id = m.runtime_identity) AS invalid_count,
                count(DISTINCT m.runtime_identity) AS canonical_identity_count,
                count(DISTINCT ri.operational_region_id) AS operational_identity_count,
                jsonb_object_agg(m.runtime_identity::TEXT, ri.operational_region_id::TEXT)
                    FILTER (WHERE m.runtime_identity IS NOT NULL AND ri.operational_region_id IS NOT NULL)
                    AS assignment_map
            FROM "${serviceSchema}".world_canonical_instance_topology_identity m
            FULL OUTER JOIN "${serviceSchema}".region_instance ri
                ON m.family='REGION' AND m.runtime_row_id=ri.id
            WHERE (m.world_instance_id=a.world_instance_id AND m.family='REGION')
                OR ri.world_instance_id=a.world_instance_id
                OR (ri.tenant_id=a.local_tenant_key AND ri.game_instance_id=a.private_game_instance_key)
        ) scoped_regions
        WHERE a.canonical_game_instance_id=NEW.canonical_game_instance_id
            AND a.world_instance_id=NEW.world_instance_id
            AND p.region_count > 0
            AND p.region_count=scoped_regions.row_count
            AND scoped_regions.row_count=scoped_regions.canonical_identity_count
            AND scoped_regions.row_count=scoped_regions.operational_identity_count
            AND scoped_regions.invalid_count=0
            AND jsonb_typeof(preparing->'operationalRegionAssignments')='object'
            AND CASE WHEN jsonb_typeof(preparing->'operationalRegionAssignments')='object'
                THEN (SELECT count(*) FROM jsonb_object_keys(preparing->'operationalRegionAssignments'))
                ELSE -1 END = p.region_count
            AND scoped_regions.assignment_map=preparing->'operationalRegionAssignments'
    ) THEN
        RAISE EXCEPTION 'Canonical activation lifecycle evidence lacks the exact complete operational REGION assignment map'
            USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(result) IS DISTINCT FROM 'object'$replacement$;
    definition := replace(definition, anchor, replacement);

    EXECUTE definition;
    IF '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE
            IS DISTINCT FROM original_oid
        OR (SELECT p.proacl FROM pg_proc p WHERE p.oid = original_oid)
            IS DISTINCT FROM original_acl THEN
        RAISE EXCEPTION 'Canonical activation guard replacement changed function identity or grants';
    END IF;
END;
$migration$;
-- [jooq ignore stop]
