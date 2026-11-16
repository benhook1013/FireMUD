-- Existing UUID REGION component storage only. Never APPLIED, visibility or Account permission.
-- Creation mappings and all earlier storage/result bytes remain immutable.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_protect_mapped_topology_row() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity
        WHERE family = CASE TG_TABLE_NAME WHEN 'world_entity_spawn_binding'
            THEN 'WORLD_ENTITY_SPAWN_BINDING' ELSE upper(TG_TABLE_NAME) END
            AND private_row_key = OLD.id) THEN
        -- This trigger sorts before trg_reserve_region_tenant_key. Inspect, never consume:
        -- the unchanged V1041 HUMAN consumer remains the sole source-qualified one-use gate.
        IF TG_TABLE_NAME = 'region' AND TG_OP = 'UPDATE' AND EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest m
            JOIN "${serviceSchema}".world_authored_topology_identity i
                ON i.family='REGION' AND i.private_row_key=OLD.id
            JOIN "${serviceSchema}".world_authored_version_identity v
                ON v.operation_id=i.version_identity_operation_id
            JOIN "${serviceSchema}".world_design_publication_fence_owner o
                ON o.target_namespace=v.target_namespace AND o.canonical_tenant_id=v.canonical_tenant_id
                AND o.local_tenant_key=v.local_tenant_key AND o.version_id=v.local_version_key
            WHERE m.transaction_id=txid_current() AND m.preparation_authority_kind='HUMAN'
                AND (i.preparation_authority_kind IS NULL OR i.preparation_authority_kind='HUMAN')
                AND m.table_name='region' AND m.row_operation='UPDATE' AND m.row_id=OLD.id
                AND m.expected_old=to_jsonb(OLD) AND m.expected_new=to_jsonb(NEW)
                AND OLD.tenant_id=NEW.tenant_id AND OLD.version_id=NEW.version_id AND OLD.id=NEW.id
                AND m.local_tenant_key=OLD.tenant_id AND m.local_version_key=OLD.version_id
                AND i.tenant_id=OLD.tenant_id AND i.version_id=OLD.version_id
                AND i.target_namespace=v.target_namespace AND i.canonical_tenant_id=v.canonical_tenant_id
                AND i.canonical_version_id=v.canonical_version_id
                AND m.version_identity_operation_id=v.operation_id
                AND m.owner_binding->>'versionIdentityOperationId'=v.operation_id::TEXT
                AND m.owner_binding->>'targetNamespace'=v.target_namespace
                AND m.owner_binding->>'canonicalTenantId'=v.canonical_tenant_id::TEXT
                AND m.owner_binding->>'canonicalVersionId'=v.canonical_version_id::TEXT
                AND m.complete_binding->>'canonicalTenantId'=v.canonical_tenant_id::TEXT
                AND m.complete_binding->>'canonicalVersionId'=v.canonical_version_id::TEXT
                AND m.operation_id::TEXT=m.complete_binding->>'commitId'
                AND (SELECT count(*)=2 AND count(DISTINCT unit->>'scopeType')=2
                    AND bool_and(unit->>'aggregateType'='REGION'
                        AND unit->>'aggregateId'=i.template_id::TEXT
                        AND unit->>'scopeId'=i.template_id::TEXT
                        AND unit->>'scopeType' IN ('AGGREGATE','REGION_SUBTREE'))
                    FROM jsonb_array_elements(m.complete_binding->'affectedUnits') unit
                    WHERE unit->>'owner'='WORLD_MANAGEMENT')
                AND o.owner_freeze_phase='OPEN' AND o.current_publication_fence IS NULL
        ) THEN RETURN NEW; END IF;
        RAISE EXCEPTION 'Mapped UUID World content cannot be changed by numeric writers'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

-- Only trusted local owner code may supply the typed projection of the immutable original
-- ProtoJSON. SQL checks revision identity and complete owned tuples, not ProtoJSON semantic
-- normalization. Before recording history in the same transaction, the unregistered repository
-- independently checks physical properties against the original immutable parsed plan.
-- This is the existing fresh-topology trusted projection boundary, never public caller input
-- or authentication; PUBLIC execution remains revoked.
CREATE FUNCTION world_store_guarded_existing_region(
    p_owner JSONB, p_binding JSONB, p_execution_revision JSONB
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    identity_row "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    intake_row "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    mapping "${serviceSchema}".world_authored_topology_identity%ROWTYPE;
    old_region "${serviceSchema}".region%ROWTYPE;
    new_region "${serviceSchema}".region%ROWTYPE;
    old_aggregate "${serviceSchema}".world_design_aggregate_epoch%ROWTYPE;
    new_aggregate "${serviceSchema}".world_design_aggregate_epoch%ROWTYPE;
    old_scope "${serviceSchema}".world_design_scope_epoch%ROWTYPE;
    new_scope "${serviceSchema}".world_design_scope_epoch%ROWTYPE;
    revision JSONB;
    mutation JSONB;
    payload JSONB;
    expected_units JSONB;
    supplied_units JSONB;
    expected_aggregate BIGINT;
    expected_scope BIGINT;
    region_uuid UUID;
    affected INTEGER;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Existing REGION storage requires writable READ COMMITTED' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT identity_row FROM "${serviceSchema}".world_authored_version_identity
        WHERE operation_id=(p_owner->>'versionIdentityOperationId')::UUID FOR SHARE;
    SELECT * INTO STRICT intake_row FROM "${serviceSchema}".world_authored_source_intake
        WHERE operation_id=identity_row.intake_operation_id;
    IF p_owner IS DISTINCT FROM jsonb_build_object(
        'targetNamespace',identity_row.target_namespace,
        'canonicalTenantId',identity_row.canonical_tenant_id,
        'canonicalVersionId',identity_row.canonical_version_id,
        'versionIdentityOperationId',identity_row.operation_id,
        'gameDesignVersionId',identity_row.game_design_version_id,
        'intakeRequestId',identity_row.intake_request_id,
        'intakeOperationId',identity_row.intake_operation_id,
        'intakeRequestDigest',identity_row.intake_request_digest,
        'sourceOperationId',identity_row.source_operation_id,
        'sourceEvidenceDigest',identity_row.source_evidence_digest,
        'intakeReceiptDigest',identity_row.intake_receipt_digest)
        OR p_binding->>'canonicalTenantId' IS DISTINCT FROM identity_row.canonical_tenant_id::TEXT
        OR p_binding->>'canonicalVersionId' IS DISTINCT FROM identity_row.canonical_version_id::TEXT
        OR p_binding->'target' IS DISTINCT FROM jsonb_build_object(
            'gameDesignVersionRowId',identity_row.game_design_version_id::TEXT,
            'gameDesignVersionTenantKey',intake_row.source_game_tenant_key,
            'sourceGameRowId',intake_row.source_game_row_id::TEXT,
            'sourceGameTenantKey',intake_row.source_game_tenant_key,
            'sourceProvenanceKind',intake_row.source_provenance_kind)
        OR intake_row.source_provenance_kind <> 'NEW_GAME_ROW' THEN
        RAISE EXCEPTION 'Existing REGION differs from exact retained source/Version' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace=identity_row.target_namespace
            AND canonical_tenant_id=identity_row.canonical_tenant_id
            AND local_tenant_key=identity_row.local_tenant_key
            AND version_id=identity_row.local_version_key FOR UPDATE;
    IF owner_row.owner_freeze_phase <> 'OPEN' OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'Existing REGION requires exact OPEN V25 owner' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest
        WHERE transaction_id=txid_current()) THEN
        RAISE EXCEPTION 'Existing REGION cannot adopt pending execution manifests' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_commit
        WHERE request_id=(p_binding->>'requestId')::UUID OR commit_id=(p_binding->>'commitId')::UUID)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id=(p_binding->>'requestId')::UUID OR commit_id=(p_binding->>'commitId')::UUID) THEN
        RAISE EXCEPTION 'Existing REGION request/commit has retained history' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_array_elements(p_binding->'revisions')
        WHERE value->>'owner'='WORLD_MANAGEMENT') <> 1 THEN
        RAISE EXCEPTION 'Exactly one existing REGION revision required' USING ERRCODE='23514';
    END IF;
    SELECT value INTO STRICT revision FROM jsonb_array_elements(p_binding->'revisions')
        WHERE value->>'owner'='WORLD_MANAGEMENT';
    IF (p_execution_revision - 'payload') IS DISTINCT FROM (revision - 'payload') THEN
        RAISE EXCEPTION 'Existing REGION typed revision identity/order differs' USING ERRCODE='23514';
    END IF;
    mutation := (p_execution_revision->>'payload')::JSONB;
    IF mutation->>'aggregateType' IS DISTINCT FROM 'WORLD_DESIGN_AGGREGATE_TYPE_REGION'
        OR mutation->>'scopeType' IS DISTINCT FROM 'WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE'
        OR mutation->>'operation' IS DISTINCT FROM 'WORLD_DESIGN_MUTATION_OPERATION_UPSERT'
        OR mutation->>'commitId' IS DISTINCT FROM p_binding->>'commitId'
        OR mutation->>'logicalRevisionId' IS DISTINCT FROM revision->>'revisionId'
        OR coalesce(mutation->>'scopeMutationPolicy','WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED')
            <> 'WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED'
        OR mutation ? 'freshGraphDeclaration'
        OR mutation->>'aggregateId' IS DISTINCT FROM mutation->>'scopeId'
        OR mutation->>'aggregateId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR p_binding->>'baseCommitId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR (p_binding->>'baseCommitId')::UUID='00000000-0000-0000-0000-000000000000'::UUID THEN
        RAISE EXCEPTION 'Unsupported exact authored existing REGION mutation' USING ERRCODE='23514';
    END IF;
    region_uuid := (mutation->>'aggregateId')::UUID;
    expected_aggregate := coalesce(mutation->>'expectedDraftRevisionEpoch','0')::BIGINT;
    expected_scope := coalesce(mutation->>'expectedDraftScopeRevisionEpoch','0')::BIGINT;
    IF expected_aggregate < 0 OR expected_aggregate=9223372036854775807
        OR expected_scope < 0 OR expected_scope=9223372036854775807 THEN
        RAISE EXCEPTION 'Existing REGION epochs are not incrementable' USING ERRCODE='23514';
    END IF;
    expected_units := jsonb_build_array(
        jsonb_build_object('owner','WORLD_MANAGEMENT','aggregateType','REGION',
            'aggregateId',region_uuid::TEXT,'scopeType','AGGREGATE',
            'scopeId',region_uuid::TEXT,'expectedEpoch',expected_aggregate::TEXT),
        jsonb_build_object('owner','WORLD_MANAGEMENT','aggregateType','REGION',
            'aggregateId',region_uuid::TEXT,'scopeType','REGION_SUBTREE',
            'scopeId',region_uuid::TEXT,'expectedEpoch',expected_scope::TEXT));
    SELECT jsonb_agg(value ORDER BY value->>'aggregateType',value->>'aggregateId',value->>'scopeType',value->>'scopeId')
        INTO supplied_units FROM jsonb_array_elements(p_binding->'affectedUnits')
        WHERE value->>'owner'='WORLD_MANAGEMENT';
    IF supplied_units IS DISTINCT FROM expected_units THEN
        RAISE EXCEPTION 'Existing REGION requires complete exact UUID aggregate/subtree tuples' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT mapping FROM "${serviceSchema}".world_authored_topology_identity
        WHERE target_namespace=identity_row.target_namespace
            AND canonical_tenant_id=identity_row.canonical_tenant_id
            AND canonical_version_id=identity_row.canonical_version_id
            AND version_identity_operation_id=identity_row.operation_id
            AND family='REGION' AND template_id=region_uuid
            AND tenant_id=identity_row.local_tenant_key AND version_id=identity_row.local_version_key
            AND (preparation_authority_kind IS NULL OR preparation_authority_kind='HUMAN') FOR SHARE;
    SELECT * INTO STRICT old_region FROM "${serviceSchema}".region
        WHERE id=mapping.private_row_key AND tenant_id=mapping.tenant_id
            AND version_id=mapping.version_id FOR UPDATE;
    SELECT * INTO STRICT old_aggregate FROM "${serviceSchema}".world_design_aggregate_epoch
        WHERE tenant_id=mapping.tenant_id AND version_id=mapping.version_id
            AND aggregate_type='REGION' AND aggregate_id=mapping.private_row_key FOR UPDATE;
    SELECT * INTO STRICT old_scope FROM "${serviceSchema}".world_design_scope_epoch
        WHERE tenant_id=mapping.tenant_id AND version_id=mapping.version_id
            AND scope_type='REGION_SUBTREE' AND scope_id=region_uuid::TEXT FOR UPDATE;
    IF old_aggregate.draft_revision_epoch <> expected_aggregate
        OR old_scope.draft_scope_revision_epoch <> expected_scope THEN
        RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: existing REGION aggregate/subtree' USING ERRCODE='23514';
    END IF;
    new_region := old_region;
    payload := mutation->'region';
    new_region.name := coalesce(payload->>'name','');
    new_region.shard_id := coalesce((payload->>'shardId')::INTEGER,0);
    new_region.weather := coalesce(payload->>'weather','');
    new_region.generation_seed := coalesce((payload->>'generationSeed')::BIGINT,0);
    new_region.generator_type := coalesce(payload->>'generatorType','');
    new_region.generator_params := coalesce(payload->>'generatorParams','');
    new_region.spacing_multiplier := coalesce(nullif((payload->>'spacingMultiplier')::FLOAT8,0),1);
    IF payload IS NULL OR btrim(new_region.name)='' OR new_region.shard_id<0
        OR new_region.spacing_multiplier<=0
        OR new_region.spacing_multiplier IN ('NaN'::FLOAT8,'Infinity'::FLOAT8) THEN
        RAISE EXCEPTION 'Invalid existing REGION properties' USING ERRCODE='23514';
    END IF;
    new_aggregate := old_aggregate;
    new_aggregate.draft_revision_epoch := expected_aggregate+1;
    new_aggregate.updated_at := CURRENT_TIMESTAMP;
    new_scope := old_scope;
    new_scope.draft_scope_revision_epoch := expected_scope+1;
    new_scope.updated_at := CURRENT_TIMESTAMP;
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest
        (transaction_id,operation_id,table_name,row_id,row_operation,version_identity_operation_id,
         local_tenant_key,local_version_key,owner_binding,complete_binding,expected_old,expected_new)
    VALUES (txid_current(),(p_binding->>'commitId')::UUID,'region',old_region.id,'UPDATE',
        identity_row.operation_id,mapping.tenant_id,mapping.version_id,p_owner,p_binding,to_jsonb(old_region),to_jsonb(new_region)),
        (txid_current(),(p_binding->>'commitId')::UUID,'world_design_aggregate_epoch',old_aggregate.id,'UPDATE',
        identity_row.operation_id,mapping.tenant_id,mapping.version_id,p_owner,p_binding,to_jsonb(old_aggregate),to_jsonb(new_aggregate)),
        (txid_current(),(p_binding->>'commitId')::UUID,'world_design_scope_epoch',old_scope.id,'UPDATE',
        identity_row.operation_id,mapping.tenant_id,mapping.version_id,p_owner,p_binding,to_jsonb(old_scope),to_jsonb(new_scope));
    UPDATE "${serviceSchema}".region SET name=new_region.name,shard_id=new_region.shard_id,
        weather=new_region.weather,generation_seed=new_region.generation_seed,
        generator_type=new_region.generator_type,generator_params=new_region.generator_params,
        spacing_multiplier=new_region.spacing_multiplier
        WHERE id=old_region.id AND to_jsonb(region)=to_jsonb(old_region);
    GET DIAGNOSTICS affected=ROW_COUNT;
    IF affected<>1 THEN RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: existing REGION row' USING ERRCODE='23514'; END IF;
    UPDATE "${serviceSchema}".world_design_aggregate_epoch
        SET draft_revision_epoch=new_aggregate.draft_revision_epoch,updated_at=new_aggregate.updated_at
        WHERE id=old_aggregate.id AND draft_revision_epoch=expected_aggregate;
    GET DIAGNOSTICS affected=ROW_COUNT;
    IF affected<>1 THEN RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: existing REGION aggregate CAS' USING ERRCODE='23514'; END IF;
    UPDATE "${serviceSchema}".world_design_scope_epoch
        SET draft_scope_revision_epoch=new_scope.draft_scope_revision_epoch,updated_at=new_scope.updated_at
        WHERE id=old_scope.id AND draft_scope_revision_epoch=expected_scope;
    GET DIAGNOSTICS affected=ROW_COUNT;
    IF affected<>1 THEN RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: existing REGION subtree CAS' USING ERRCODE='23514'; END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest
        WHERE transaction_id=txid_current()) THEN
        RAISE EXCEPTION 'Existing REGION left unconsumed manifests' USING ERRCODE='23514';
    END IF;
    RETURN jsonb_build_object('mapping',to_jsonb(mapping),'oldRegion',to_jsonb(old_region),
        'newRegion',to_jsonb(new_region),'aggregate',to_jsonb(new_aggregate),'scope',to_jsonb(new_scope));
END;
$$;
REVOKE ALL ON FUNCTION world_store_guarded_existing_region(JSONB,JSONB,JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION world_protect_mapped_topology_row() FROM PUBLIC;
CREATE OR REPLACE FUNCTION world_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1046' AND script='V1046__world_owner_runtime_family_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1047' AND script='V1047__world_catalog_snapshot_exact_frames.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1048' AND script='V1048__world_protected_installation_registry.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1049'
        AND script='V1049__world_private_fresh_operation.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1050'
        AND script='V1050__world_private_fresh_settlement_binding.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1051'
        AND script='V1051__world_run_owned_fixture_activation.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1052'
        AND script='V1052__world_fixture_activation_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1053'
        AND script='V1053__world_fixture_initial_hold.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1054'
        AND script='V1054__world_guarded_existing_region_updates.sql' AND success);
$$;

-- Extend the installer fact/exclusion, then append a new immutable catalog epoch.
DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1053','V1053__world_fixture_initial_hold.sql'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1053 catalog migration fact';
    END IF;
    IF (length(original)-length(replace(original,$history$AND version IS DISTINCT FROM '1053'$history$,'')))
        /length($history$AND version IS DISTINCT FROM '1053'$history$)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1053 catalog history exclusion';
    END IF;
    original := replace(original,$history$AND version IS DISTINCT FROM '1053'$history$,
        $history$AND version IS DISTINCT FROM '1053' AND version IS DISTINCT FROM '1054'$history$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1054','V1054__world_guarded_existing_region_updates.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
