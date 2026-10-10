-- Fresh authored UUID topology is component storage, never permission/release evidence.
-- Retained numeric rows stay unmapped; no canonical identity is inferred from a private key.
ALTER TABLE world_entity_spawn_binding ALTER COLUMN entity_template_id DROP NOT NULL;
ALTER TABLE world_entity_spawn_binding
    ADD COLUMN entity_canonical_tenant_id UUID,
    ADD COLUMN entity_canonical_version_id UUID,
    ADD COLUMN entity_canonical_template_id UUID,
    ADD CONSTRAINT ck_world_spawn_reference_representation CHECK (
        (entity_template_id IS NOT NULL AND entity_canonical_tenant_id IS NULL
            AND entity_canonical_version_id IS NULL AND entity_canonical_template_id IS NULL)
        OR (entity_template_id IS NULL AND entity_canonical_tenant_id IS NOT NULL
            AND entity_canonical_version_id IS NOT NULL AND entity_canonical_template_id IS NOT NULL
            AND entity_canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND entity_canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND entity_canonical_template_id <> '00000000-0000-0000-0000-000000000000'::UUID)
    );
CREATE UNIQUE INDEX uq_world_spawn_canonical_reference ON world_entity_spawn_binding
    (tenant_id, version_id, room_id, entity_template_type,
        entity_canonical_tenant_id, entity_canonical_version_id, entity_canonical_template_id)
    WHERE entity_template_id IS NULL;

CREATE TABLE world_topology_draft_commit (
    request_id UUID PRIMARY KEY,
    commit_id UUID NOT NULL UNIQUE,
    version_identity_operation_id UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL CHECK (local_tenant_key > 0),
    local_version_key BIGINT NOT NULL CHECK (local_version_key > 0),
    owner_binding_json TEXT NOT NULL,
    binding_json TEXT NOT NULL,
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    graph_bytes BYTEA NOT NULL CHECK (octet_length(graph_bytes) > 0),
    graph_sha256 VARCHAR(64) NOT NULL CHECK (graph_sha256 ~ '^[0-9a-f]{64}$'),
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    storage_status VARCHAR(32) NOT NULL CHECK (storage_status = 'STORED_PERMISSION_UNVERIFIED'),
    stored_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (request_id, commit_id, version_identity_operation_id,
        target_namespace, canonical_tenant_id, canonical_version_id, local_tenant_key, local_version_key),
    FOREIGN KEY (version_identity_operation_id, target_namespace, canonical_tenant_id,
        canonical_version_id, local_version_key)
        REFERENCES world_authored_version_identity(operation_id, target_namespace,
            canonical_tenant_id, canonical_version_id, local_version_key)
);
CREATE TABLE world_authored_topology_identity (
    id BIGSERIAL PRIMARY KEY,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    family VARCHAR(50) NOT NULL CHECK (family IN (
        'REGION','ZONE','ROOM','ROOM_EXIT','GENERATION_RULE','WORLD_ENTITY_SPAWN_BINDING')),
    template_id UUID NOT NULL CHECK (template_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    private_row_key BIGINT NOT NULL CHECK (private_row_key > 0),
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    revision_order TEXT NOT NULL,
    UNIQUE (target_namespace, canonical_tenant_id, canonical_version_id, family, template_id),
    UNIQUE (family, private_row_key),
    FOREIGN KEY (request_id, commit_id, version_identity_operation_id,
        target_namespace, canonical_tenant_id, canonical_version_id, tenant_id, version_id)
        REFERENCES world_topology_draft_commit(request_id, commit_id, version_identity_operation_id,
            target_namespace, canonical_tenant_id, canonical_version_id, local_tenant_key, local_version_key)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (version_identity_operation_id, target_namespace, canonical_tenant_id,
        canonical_version_id, version_id)
        REFERENCES world_authored_version_identity(operation_id, target_namespace,
            canonical_tenant_id, canonical_version_id, local_version_key)
);

-- [jooq ignore start]
-- Reuse V29's exact OLD/NEW, source-qualified, transaction-local, consumed manifest.
ALTER TABLE world_region_draft_execution_manifest
    DROP CONSTRAINT world_region_draft_execution_manifest_table_name_check,
    DROP CONSTRAINT world_region_draft_execution_manifest_check;
ALTER TABLE world_region_draft_execution_manifest
    ADD CONSTRAINT ck_world_execution_table CHECK (table_name IN (
        'region','zone','room','room_exit','generation_rule','world_entity_spawn_binding',
        'world_authored_topology_identity','world_design_aggregate_epoch','world_design_scope_epoch')),
    ADD CONSTRAINT ck_world_execution_transition CHECK (
        (row_operation = 'INSERT' AND expected_old IS NULL)
        OR (row_operation = 'UPDATE' AND expected_old IS NOT NULL
            AND table_name IN ('region','world_design_aggregate_epoch','world_design_scope_epoch')));

CREATE TRIGGER trg_world_topology_history_immutable BEFORE UPDATE OR DELETE
    ON world_topology_draft_commit FOR EACH ROW
    EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE TRIGGER trg_world_topology_history_no_truncate BEFORE TRUNCATE
    ON world_topology_draft_commit FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE FUNCTION world_protect_topology_identity() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF TG_OP = 'INSERT' AND "${serviceSchema}".world_consume_region_execution_manifest(
        TG_TABLE_NAME, TG_OP, NULL, to_jsonb(NEW)) THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'World UUID mapping requires an exact fresh execution manifest and is immutable'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_world_topology_identity_guard BEFORE INSERT OR UPDATE OR DELETE
    ON world_authored_topology_identity FOR EACH ROW EXECUTE FUNCTION world_protect_topology_identity();
CREATE TRIGGER trg_world_topology_identity_no_truncate BEFORE TRUNCATE
    ON world_authored_topology_identity FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE FUNCTION world_protect_mapped_topology_row() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity
        WHERE family = CASE TG_TABLE_NAME WHEN 'world_entity_spawn_binding'
            THEN 'WORLD_ENTITY_SPAWN_BINDING' ELSE upper(TG_TABLE_NAME) END
            AND private_row_key = OLD.id) THEN
        RAISE EXCEPTION 'Mapped UUID World content cannot be changed by numeric writers'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_region_mapped_uuid_immutable BEFORE UPDATE OR DELETE ON region
    FOR EACH ROW EXECUTE FUNCTION world_protect_mapped_topology_row();

CREATE TRIGGER trg_zone_mapped_uuid_immutable BEFORE UPDATE OR DELETE ON zone
    FOR EACH ROW EXECUTE FUNCTION world_protect_mapped_topology_row();

CREATE TRIGGER trg_room_mapped_uuid_immutable BEFORE UPDATE OR DELETE ON room
    FOR EACH ROW EXECUTE FUNCTION world_protect_mapped_topology_row();

CREATE TRIGGER trg_room_exit_mapped_uuid_immutable BEFORE UPDATE OR DELETE ON room_exit
    FOR EACH ROW EXECUTE FUNCTION world_protect_mapped_topology_row();

CREATE TRIGGER trg_generation_rule_mapped_uuid_immutable BEFORE UPDATE OR DELETE ON generation_rule
    FOR EACH ROW EXECUTE FUNCTION world_protect_mapped_topology_row();

CREATE TRIGGER trg_world_entity_spawn_binding_mapped_uuid_immutable BEFORE UPDATE OR DELETE ON world_entity_spawn_binding
    FOR EACH ROW EXECUTE FUNCTION world_protect_mapped_topology_row();

-- The trusted local parsed projection uses canonical protobuf JSON for execution only.
-- Exact original full binding/payload bytes remain the manifest/history authority. As in V29,
-- this typed component seam is not a public caller field or permission evidence; the repository
-- verifies every actual stored payload against the original parsed plan before recording history.
CREATE FUNCTION world_store_guarded_uuid_topology(
    p_owner JSONB, p_binding JSONB, p_execution_revisions JSONB
)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    identity_row "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    intake_row "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    mapping "${serviceSchema}".world_authored_topology_identity%ROWTYPE;
    revision JSONB;
    mutation JSONB;
    payload JSONB;
    family TEXT;
    table_name TEXT;
    content JSONB;
    complete_row JSONB;
    scope_key BIGINT;
    parent_key BIGINT;
    other_key BIGINT;
    scope_type TEXT;
    scope_uuid UUID;
    expected_units JSONB := '[]'::JSONB;
    supplied_units JSONB;
    expected_sorted JSONB;
    epoch_row JSONB;
    nodes JSONB;
    original_revision_identities JSONB;
    execution_revision_identities JSONB;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'World topology requires writable READ COMMITTED' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT identity_row FROM "${serviceSchema}".world_authored_version_identity
        WHERE operation_id = (p_owner->>'versionIdentityOperationId')::UUID FOR SHARE;
    SELECT * INTO STRICT intake_row FROM "${serviceSchema}".world_authored_source_intake
        WHERE operation_id = identity_row.intake_operation_id;
    IF p_owner IS DISTINCT FROM jsonb_build_object(
        'targetNamespace', identity_row.target_namespace,
        'canonicalTenantId', identity_row.canonical_tenant_id,
        'canonicalVersionId', identity_row.canonical_version_id,
        'versionIdentityOperationId', identity_row.operation_id,
        'gameDesignVersionId', identity_row.game_design_version_id,
        'intakeRequestId', identity_row.intake_request_id,
        'intakeOperationId', identity_row.intake_operation_id,
        'intakeRequestDigest', identity_row.intake_request_digest,
        'sourceOperationId', identity_row.source_operation_id,
        'sourceEvidenceDigest', identity_row.source_evidence_digest,
        'intakeReceiptDigest', identity_row.intake_receipt_digest)
        OR p_binding->>'canonicalTenantId' IS DISTINCT FROM identity_row.canonical_tenant_id::TEXT
        OR p_binding->>'canonicalVersionId' IS DISTINCT FROM identity_row.canonical_version_id::TEXT
        OR p_binding->'target' IS DISTINCT FROM jsonb_build_object(
            'gameDesignVersionRowId', identity_row.game_design_version_id::TEXT,
            'gameDesignVersionTenantKey', intake_row.source_game_tenant_key,
            'sourceGameRowId', intake_row.source_game_row_id::TEXT,
            'sourceGameTenantKey', intake_row.source_game_tenant_key,
            'sourceProvenanceKind', intake_row.source_provenance_kind)
    THEN
        RAISE EXCEPTION 'Complete World region source binding differs from retained intake/V27'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace = identity_row.target_namespace
            AND canonical_tenant_id = identity_row.canonical_tenant_id
            AND local_tenant_key = identity_row.local_tenant_key
            AND version_id = identity_row.local_version_key FOR UPDATE;
    IF owner_row.owner_freeze_phase <> 'OPEN' OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'World topology storage requires the shared OPEN owner row'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest
        WHERE transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'World complete region storage already has an execution manifest'
            USING ERRCODE = '23514';
    END IF;

    IF jsonb_typeof(p_execution_revisions) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'World typed execution projection must be an array' USING ERRCODE = '23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('revisionOrder',value->>'revisionOrder',
        'revisionId',value->>'revisionId','owner',value->>'owner')
        ORDER BY ordinal) INTO original_revision_identities
        FROM jsonb_array_elements(p_binding->'revisions') WITH ORDINALITY AS original(value,ordinal)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT';
    SELECT jsonb_agg(jsonb_build_object('revisionOrder',value->>'revisionOrder',
        'revisionId',value->>'revisionId','owner',value->>'owner')
        ORDER BY ordinal) INTO execution_revision_identities
        FROM jsonb_array_elements(p_execution_revisions) WITH ORDINALITY AS execution(value,ordinal);
    IF original_revision_identities IS NULL
        OR execution_revision_identities IS DISTINCT FROM original_revision_identities THEN
        RAISE EXCEPTION 'World typed execution projection differs from exact original revision identities/order/owner'
            USING ERRCODE = '23514';
    END IF;
    SELECT jsonb_agg(value ORDER BY (value->>'revisionOrder')::NUMERIC) INTO nodes
        FROM jsonb_array_elements(p_execution_revisions);
    IF nodes IS NULL OR intake_row.source_provenance_kind <> 'NEW_GAME_ROW' THEN
        RAISE EXCEPTION 'Fresh topology requires World input and exact fresh source' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_commit
        WHERE request_id = (p_binding->>'requestId')::UUID OR commit_id = (p_binding->>'commitId')::UUID) THEN
        RAISE EXCEPTION 'World request/commit already has retained numeric history' USING ERRCODE = '23514';
    END IF;
    -- Require a wholly fresh local Version. Retained content is never relabelled or overwritten.
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".region
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained region' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".zone
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained zone' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".room
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained room' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".room_exit
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained room_exit' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".generation_rule
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained generation_rule' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_entity_spawn_binding
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained world_entity_spawn_binding' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_design_aggregate_epoch
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained world_design_aggregate_epoch' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_design_scope_epoch
        WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key) THEN
        RAISE EXCEPTION 'Fresh World topology conflicts with retained world_design_scope_epoch' USING ERRCODE = '23514';
    END IF;

    FOR revision IN SELECT value FROM jsonb_array_elements(nodes) LOOP
        mutation := (revision->>'payload')::JSONB;
        family := replace(mutation->>'aggregateType', 'WORLD_DESIGN_AGGREGATE_TYPE_', '');
        scope_type := replace(mutation->>'scopeType', 'WORLD_DESIGN_SCOPE_TYPE_', '');
        IF family NOT IN ('REGION','ZONE','ROOM','ROOM_EXIT','GENERATION_RULE','WORLD_ENTITY_SPAWN_BINDING')
            OR scope_type NOT IN ('REGION_SUBTREE','ZONE_SUBTREE')
            OR mutation->>'operation' IS DISTINCT FROM 'WORLD_DESIGN_MUTATION_OPERATION_UPSERT'
            OR mutation->>'commitId' IS DISTINCT FROM p_binding->>'commitId'
            OR mutation->>'logicalRevisionId' IS DISTINCT FROM revision->>'revisionId'
            OR coalesce(mutation->>'expectedDraftRevisionEpoch','0') <> '0'
            OR coalesce(mutation->>'expectedDraftScopeRevisionEpoch','0') <> '0'
            OR coalesce(mutation->>'scopeMutationPolicy','WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED')
                <> 'WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED' THEN
            RAISE EXCEPTION 'Unsupported exact fresh World revision' USING ERRCODE = '23514';
        END IF;
        table_name := lower(family);
        mapping.id := nextval('"${serviceSchema}".world_authored_topology_identity_id_seq');
        mapping.target_namespace := identity_row.target_namespace;
        mapping.canonical_tenant_id := identity_row.canonical_tenant_id;
        mapping.canonical_version_id := identity_row.canonical_version_id;
        mapping.family := family;
        mapping.template_id := (mutation->>'aggregateId')::UUID;
        mapping.private_row_key := nextval(
            format('%I.%I', '${serviceSchema}', table_name || '_id_seq')::REGCLASS);
        mapping.tenant_id := identity_row.local_tenant_key;
        mapping.version_id := identity_row.local_version_key;
        mapping.version_identity_operation_id := identity_row.operation_id;
        mapping.request_id := (p_binding->>'requestId')::UUID;
        mapping.commit_id := (p_binding->>'commitId')::UUID;
        mapping.revision_id := (revision->>'revisionId')::UUID;
        mapping.revision_order := revision->>'revisionOrder';
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(), mapping.commit_id, 'world_authored_topology_identity', mapping.id, 'INSERT',
            identity_row.operation_id, mapping.tenant_id, mapping.version_id,
            p_owner, p_binding, NULL, to_jsonb(mapping));
        INSERT INTO "${serviceSchema}".world_authored_topology_identity SELECT (mapping).*;
        expected_units := expected_units || jsonb_build_array(
            jsonb_build_object('owner','WORLD_MANAGEMENT','aggregateType',family,
                'aggregateId',mapping.template_id::TEXT,'scopeType','AGGREGATE',
                'scopeId',mapping.template_id::TEXT,'expectedEpoch','0'),
            jsonb_build_object('owner','WORLD_MANAGEMENT','aggregateType',family,
                'aggregateId',mapping.template_id::TEXT,'scopeType',scope_type,
                'scopeId',mutation->>'scopeId','expectedEpoch','0'));
    END LOOP;
    SELECT jsonb_agg(value ORDER BY value->>'aggregateType',value->>'aggregateId',value->>'scopeType',value->>'scopeId')
        INTO expected_sorted FROM jsonb_array_elements(expected_units);
    SELECT jsonb_agg(value ORDER BY value->>'aggregateType',value->>'aggregateId',value->>'scopeType',value->>'scopeId')
        INTO supplied_units FROM jsonb_array_elements(p_binding->'affectedUnits')
        WHERE value->>'owner' = 'WORLD_MANAGEMENT';
    IF expected_sorted IS DISTINCT FROM supplied_units THEN
        RAISE EXCEPTION 'World topology requires the exact aggregate and containing tuples'
            USING ERRCODE = '23514';
    END IF;
    -- Typed mappings are allocated first, then content is inserted in parent-before-child order.
    FOR mapping IN SELECT m.* FROM "${serviceSchema}".world_authored_topology_identity m
        WHERE m.request_id = (p_binding->>'requestId')::UUID
        ORDER BY CASE m.family WHEN 'REGION' THEN 1 WHEN 'ZONE' THEN 2 WHEN 'ROOM' THEN 3
            WHEN 'ROOM_EXIT' THEN 4 WHEN 'GENERATION_RULE' THEN 5 ELSE 6 END, m.revision_order::NUMERIC
    LOOP
        SELECT value INTO STRICT revision FROM jsonb_array_elements(nodes)
            WHERE value->>'revisionId' = mapping.revision_id::TEXT;
        mutation := (revision->>'payload')::JSONB;
        family := mapping.family;
        table_name := lower(family);
        scope_type := replace(mutation->>'scopeType','WORLD_DESIGN_SCOPE_TYPE_','');
        scope_uuid := (mutation->>'scopeId')::UUID;
        SELECT m.private_row_key INTO STRICT scope_key FROM "${serviceSchema}".world_authored_topology_identity m
            WHERE m.request_id = mapping.request_id AND m.template_id = scope_uuid
                AND m.family = CASE scope_type WHEN 'REGION_SUBTREE' THEN 'REGION' ELSE 'ZONE' END;
        content := jsonb_build_object('id',mapping.private_row_key,'tenant_id',mapping.tenant_id,
            'version_id',mapping.version_id,'version',0);
        CASE family

        WHEN 'REGION' THEN
            payload := mutation->'region';
            IF scope_type <> 'REGION_SUBTREE' OR scope_uuid <> mapping.template_id THEN
                RAISE EXCEPTION 'Region scope substitution' USING ERRCODE = '23514';
            END IF;
            content := content || jsonb_build_object(
                'name',coalesce(payload->>'name',''),'shard_id',coalesce((payload->>'shardId')::INT,0),
                'weather',coalesce(payload->>'weather',''),'generation_seed',coalesce((payload->>'generationSeed')::BIGINT,0),
                'generator_type',coalesce(payload->>'generatorType',''),'generator_params',coalesce(payload->>'generatorParams',''),
                'spacing_multiplier',coalesce(nullif((payload->>'spacingMultiplier')::FLOAT8,0),1));
        WHEN 'ZONE' THEN
            payload := mutation->'zone';
            SELECT m.private_row_key INTO STRICT parent_key FROM "${serviceSchema}".world_authored_topology_identity m
                WHERE m.request_id = mapping.request_id AND m.family = 'REGION'
                    AND m.template_id = (payload->>'regionId')::UUID;
            IF (scope_type = 'REGION_SUBTREE' AND scope_key <> parent_key)
                OR (scope_type = 'ZONE_SUBTREE' AND scope_uuid <> mapping.template_id) THEN
                RAISE EXCEPTION 'Zone scope substitution' USING ERRCODE = '23514';
            END IF;
            content := content || jsonb_build_object('name',coalesce(payload->>'name',''),'region_id',parent_key);
        WHEN 'ROOM' THEN
            payload := mutation->'room';
            SELECT m.private_row_key INTO STRICT parent_key FROM "${serviceSchema}".world_authored_topology_identity m
                WHERE m.request_id = mapping.request_id AND m.family = 'ZONE'
                    AND m.template_id = (payload->>'zoneId')::UUID;
            SELECT z.region_id INTO STRICT other_key FROM "${serviceSchema}".zone z WHERE z.id = parent_key;
            IF scope_key <> (CASE scope_type WHEN 'REGION_SUBTREE' THEN other_key ELSE parent_key END) THEN
                RAISE EXCEPTION 'Room scope substitution' USING ERRCODE = '23514';
            END IF;
            content := content || jsonb_build_object('name',coalesce(payload->>'name',''),
                'description',coalesce(payload->>'description',''),'zone_id',parent_key,
                'name_localized_variants_json',coalesce(payload->>'nameLocalizedVariantsJson',''),
                'description_localized_variants_json',coalesce(payload->>'descriptionLocalizedVariantsJson',''));
        WHEN 'ROOM_EXIT' THEN
            payload := mutation->'roomExit';
            SELECT m.private_row_key INTO STRICT parent_key FROM "${serviceSchema}".world_authored_topology_identity m
                WHERE m.request_id = mapping.request_id AND m.family = 'ROOM'
                    AND m.template_id = (payload->>'fromRoomId')::UUID;
            SELECT m.private_row_key INTO STRICT other_key FROM "${serviceSchema}".world_authored_topology_identity m
                WHERE m.request_id = mapping.request_id AND m.family = 'ROOM'
                    AND m.template_id = (payload->>'toRoomId')::UUID;
            content := content || jsonb_build_object('from_room_id',parent_key,'to_room_id',other_key,
                'direction',coalesce(payload->>'direction',''),'cost',coalesce(nullif((payload->>'cost')::INT,0),1));
        WHEN 'GENERATION_RULE' THEN
            payload := mutation->'generationRule';
            content := content || jsonb_build_object('name',coalesce(payload->>'name',''),
                'value',coalesce(payload->>'value',''),'scope_type',scope_type,'scope_id',scope_key::TEXT);
        WHEN 'WORLD_ENTITY_SPAWN_BINDING' THEN
            payload := mutation->'worldEntitySpawnBinding';
            SELECT m.private_row_key INTO STRICT parent_key FROM "${serviceSchema}".world_authored_topology_identity m
                WHERE m.request_id = mapping.request_id AND m.family = 'ROOM'
                    AND m.template_id = (payload->>'roomId')::UUID;
            content := content || jsonb_build_object('room_id',parent_key,
                'entity_template_type',replace(payload->>'entityTemplateType','ENTITY_TEMPLATE_REFERENCE_TYPE_',''),
                'entity_template_id',NULL,'entity_canonical_tenant_id',mapping.canonical_tenant_id,
                'entity_canonical_version_id',mapping.canonical_version_id,
                'entity_canonical_template_id',(payload->>'entityTemplateId')::UUID,
                'spawn_count',coalesce(nullif((payload->>'spawnCount')::INT,0),1),
                'respawn_delay_seconds',coalesce((payload->>'respawnDelaySeconds')::INT,0));
        ELSE RAISE EXCEPTION 'Unsupported World family';
        END CASE;
        IF payload IS NULL
            OR (content ? 'name' AND btrim(content->>'name') = '')
            OR coalesce((content->>'shard_id')::INT,0) < 0
            OR coalesce((content->>'spacing_multiplier')::FLOAT8,1) <= 0
            OR coalesce((content->>'spacing_multiplier')::FLOAT8,1) IN ('NaN'::FLOAT8,'Infinity'::FLOAT8)
            OR coalesce((content->>'cost')::INT,1) < 1
            OR coalesce((content->>'spawn_count')::INT,1) < 1
            OR coalesce((content->>'respawn_delay_seconds')::INT,0) < 0
            OR (family = 'WORLD_ENTITY_SPAWN_BINDING' AND content->>'entity_template_type' NOT IN ('ITEM','NPC'))
            OR (family = 'ROOM_EXIT' AND btrim(content->>'direction') = '') THEN
            RAISE EXCEPTION 'Invalid fresh World payload' USING ERRCODE = '23514';
        END IF;
        IF family IN ('ROOM_EXIT','WORLD_ENTITY_SPAWN_BINDING') THEN
            IF NOT EXISTS (SELECT 1 FROM "${serviceSchema}".room r
                JOIN "${serviceSchema}".zone z ON z.id = r.zone_id
                WHERE r.id = parent_key AND CASE scope_type
                    WHEN 'REGION_SUBTREE' THEN z.region_id ELSE z.id END = scope_key)
                OR (family = 'ROOM_EXIT' AND NOT EXISTS (
                    SELECT 1 FROM "${serviceSchema}".room r JOIN "${serviceSchema}".zone z ON z.id = r.zone_id
                    WHERE r.id = other_key AND CASE scope_type
                        WHEN 'REGION_SUBTREE' THEN z.region_id ELSE z.id END = scope_key)) THEN
                RAISE EXCEPTION 'World endpoint outside exact scope' USING ERRCODE = '23514';
            END IF;
        END IF;
        -- Populate the full physical row type so the manifest includes every actual column.
        EXECUTE format('SELECT to_jsonb(jsonb_populate_record(NULL::%I.%I, $1))',
            '${serviceSchema}',table_name) INTO complete_row USING content;
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(), mapping.commit_id, table_name, mapping.private_row_key, 'INSERT',
            identity_row.operation_id, mapping.tenant_id, mapping.version_id,
            p_owner, p_binding, NULL, complete_row);
        EXECUTE format('INSERT INTO %I.%I SELECT (jsonb_populate_record(NULL::%I.%I,$1)).*',
            '${serviceSchema}',table_name,'${serviceSchema}',table_name) USING complete_row;
        epoch_row := jsonb_build_object(
            'id',nextval('"${serviceSchema}".world_design_aggregate_epoch_id_seq'),
            'tenant_id',mapping.tenant_id,'version_id',mapping.version_id,
            'aggregate_type',family,'aggregate_id',mapping.private_row_key,
            'draft_revision_epoch',1,'updated_at',CURRENT_TIMESTAMP::TIMESTAMP);
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(), mapping.commit_id, 'world_design_aggregate_epoch',(epoch_row->>'id')::BIGINT,'INSERT',
            identity_row.operation_id,mapping.tenant_id,mapping.version_id,p_owner,p_binding,NULL,epoch_row);
        INSERT INTO "${serviceSchema}".world_design_aggregate_epoch
            SELECT (jsonb_populate_record(NULL::"${serviceSchema}".world_design_aggregate_epoch,epoch_row)).*;
    END LOOP;
    -- Each distinct containing fence advances once, even when multiple affected tuples share it.
    FOR revision IN SELECT DISTINCT jsonb_build_object('scope_type',value->>'scopeType','scope_id',value->>'scopeId')
        FROM jsonb_array_elements(expected_units) WHERE value->>'scopeType' <> 'AGGREGATE'
    LOOP
        epoch_row := jsonb_build_object(
            'id',nextval('"${serviceSchema}".world_design_scope_epoch_id_seq'),
            'tenant_id',identity_row.local_tenant_key,'version_id',identity_row.local_version_key,
            'scope_type',revision->>'scope_type','scope_id',revision->>'scope_id',
            'draft_scope_revision_epoch',1,'updated_at',CURRENT_TIMESTAMP::TIMESTAMP);
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(),(p_binding->>'commitId')::UUID,'world_design_scope_epoch',(epoch_row->>'id')::BIGINT,'INSERT',
            identity_row.operation_id,identity_row.local_tenant_key,identity_row.local_version_key,
            p_owner,p_binding,NULL,epoch_row);
        INSERT INTO "${serviceSchema}".world_design_scope_epoch
            SELECT (jsonb_populate_record(NULL::"${serviceSchema}".world_design_scope_epoch,epoch_row)).*;
    END LOOP;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest
        WHERE transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'Unconsumed World topology execution manifest' USING ERRCODE = '23514';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_store_guarded_uuid_topology(JSONB,JSONB,JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION world_protect_topology_identity() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_protect_mapped_topology_row() FROM PUBLIC;
-- [jooq ignore stop]
