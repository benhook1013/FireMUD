-- Owner-local private FRESH mechanics only. No upstream original-operation permission producer,
-- registration, runtime admission, activation, initial OPEN or credential-lifetime policy is added.
CREATE TABLE world_private_fresh_operation (
    operation_id UUID PRIMARY KEY CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    canonical_game_instance_id UUID NOT NULL UNIQUE CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'),
    world_slug VARCHAR(120) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    canonical_realm_id UUID NOT NULL CHECK (canonical_realm_id <> '00000000-0000-0000-0000-000000000000'),
    playtest_lifecycle_id UUID NOT NULL CHECK (playtest_lifecycle_id <> '00000000-0000-0000-0000-000000000000'),
    playable_state_namespace_id UUID NOT NULL UNIQUE CHECK (playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'),
    playtest_state_generation BIGINT NOT NULL CHECK (playtest_state_generation=1),
    playable_state_scope VARCHAR(16) NOT NULL CHECK (playable_state_scope='ISOLATED'),
    public_production BOOLEAN NOT NULL CHECK (public_production IS FALSE),
    preparation_request_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL REFERENCES world_authored_version_identity(operation_id),
    capture_id UUID NOT NULL REFERENCES world_canonical_frozen_topology(capture_id),
    graph_bytes BYTEA NOT NULL CHECK (octet_length(graph_bytes)>0),
    graph_sha256 VARCHAR(64) NOT NULL CHECK (graph_sha256=encode(sha256(graph_bytes),'hex')),
    source_bytes BYTEA NOT NULL CHECK (octet_length(source_bytes) BETWEEN 1 AND 65536),
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest='sha256:'||encode(sha256(source_bytes),'hex')),
    selected_world_bytes BYTEA NOT NULL CHECK (octet_length(selected_world_bytes) BETWEEN 1 AND 4194304),
    protection_request_id UUID NOT NULL,
    protection_hold_id UUID NOT NULL,
    protection_fence BIGINT NOT NULL CHECK (protection_fence>0),
    registry_revision BIGINT NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    required_owners JSONB NOT NULL,
    input_json TEXT NOT NULL CHECK (octet_length(input_json) BETWEEN 1 AND 6291456),
    input_digest VARCHAR(71) NOT NULL CHECK (input_digest='sha256:'||encode(sha256(convert_to(input_json,'UTF8')),'hex')),
    original_operation_bytes BYTEA NOT NULL CHECK (octet_length(original_operation_bytes) BETWEEN 1 AND 262144),
    original_operation_digest VARCHAR(71) NOT NULL CHECK (original_operation_digest='sha256:'||encode(sha256(original_operation_bytes),'hex')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (target_namespace,canonical_tenant_id,preparation_request_id),
    UNIQUE (operation_id,canonical_game_instance_id),
    FOREIGN KEY (registry_revision,artifact_digest,target_namespace)
        REFERENCES world_runtime_installation_registration(revision,artifact_digest,target_namespace),
    CHECK (playtest_lifecycle_id<>playable_state_namespace_id),
    CHECK (canonical_game_instance_id<>canonical_realm_id AND canonical_game_instance_id<>playtest_lifecycle_id
        AND canonical_game_instance_id<>playable_state_namespace_id)
);

-- Public launch-shaped fields have no private substitute. Preserve their old requiredness below.
ALTER TABLE world_instance ADD COLUMN private_fresh_operation_id UUID UNIQUE
    REFERENCES world_private_fresh_operation(operation_id);
ALTER TABLE world_instance ALTER COLUMN game_template_id DROP NOT NULL;
ALTER TABLE world_instance ALTER COLUMN control_plane_request_id DROP NOT NULL;
ALTER TABLE world_instance ALTER COLUMN launch_descriptor_id DROP NOT NULL;
ALTER TABLE world_instance ALTER COLUMN generation_config_revision DROP NOT NULL;
ALTER TABLE world_instance ALTER COLUMN release_bundle_id DROP NOT NULL;
ALTER TABLE world_instance ALTER COLUMN version_state_epoch DROP NOT NULL;
ALTER TABLE world_instance ADD CONSTRAINT world_nonprivate_launch_fields CHECK (
    private_fresh_operation_id IS NOT NULL OR (game_template_id IS NOT NULL
        AND control_plane_request_id IS NOT NULL AND launch_descriptor_id IS NOT NULL
        AND generation_config_revision IS NOT NULL AND release_bundle_id IS NOT NULL
        AND version_state_epoch IS NOT NULL));
ALTER TABLE world_instance ADD CONSTRAINT world_private_association_key UNIQUE (
    id,private_fresh_operation_id,canonical_game_instance_id,canonical_target_namespace,
    canonical_tenant_id,canonical_world_slug,canonical_realm_id,playtest_lifecycle_id,
    playable_state_namespace_id,playtest_state_generation,playable_state_scope,public_production);

CREATE TABLE world_private_instance_association (
    operation_id UUID PRIMARY KEY REFERENCES world_private_fresh_operation(operation_id),
    canonical_game_instance_id UUID NOT NULL UNIQUE,
    world_instance_id BIGINT NOT NULL UNIQUE,
    private_game_instance_key BIGINT NOT NULL CHECK (private_game_instance_key>0),
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    canonical_realm_id UUID NOT NULL,
    playtest_lifecycle_id UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playtest_state_generation BIGINT NOT NULL CHECK (playtest_state_generation=1),
    playable_state_scope VARCHAR(16) NOT NULL CHECK (playable_state_scope='ISOLATED'),
    public_production BOOLEAN NOT NULL CHECK (public_production IS FALSE),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (world_instance_id,operation_id),
    FOREIGN KEY (operation_id,canonical_game_instance_id)
        REFERENCES world_private_fresh_operation(operation_id,canonical_game_instance_id),
    FOREIGN KEY (world_instance_id,operation_id,canonical_game_instance_id,target_namespace,
        canonical_tenant_id,world_slug,canonical_realm_id,playtest_lifecycle_id,
        playable_state_namespace_id,playtest_state_generation,playable_state_scope,public_production)
        REFERENCES world_instance(id,private_fresh_operation_id,canonical_game_instance_id,canonical_target_namespace,
            canonical_tenant_id,canonical_world_slug,canonical_realm_id,playtest_lifecycle_id,
            playable_state_namespace_id,playtest_state_generation,playable_state_scope,public_production)
);
ALTER TABLE world_instance ADD CONSTRAINT world_private_association_required
    FOREIGN KEY (id,private_fresh_operation_id) REFERENCES world_private_instance_association(world_instance_id,operation_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE world_canonical_instance_topology_identity ADD COLUMN private_fresh_operation_id UUID;
ALTER TABLE world_canonical_instance_topology_identity ADD CONSTRAINT world_private_topology_association
    FOREIGN KEY (world_instance_id,private_fresh_operation_id)
    REFERENCES world_private_instance_association(world_instance_id,operation_id);

CREATE TABLE world_private_fresh_terminal (
    operation_id UUID PRIMARY KEY REFERENCES world_private_fresh_operation(operation_id),
    canonical_game_instance_id UUID NOT NULL UNIQUE,
    input_digest VARCHAR(71) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPLIED','NOT_APPLIED')),
    world_instance_id BIGINT,
    private_game_instance_key BIGINT,
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes)>0),
    terminal_digest VARCHAR(71) NOT NULL CHECK (terminal_digest='sha256:'||encode(sha256(terminal_bytes),'hex')),
    terminal_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (operation_id,canonical_game_instance_id)
        REFERENCES world_private_fresh_operation(operation_id,canonical_game_instance_id),
    FOREIGN KEY (world_instance_id,operation_id)
        REFERENCES world_private_instance_association(world_instance_id,operation_id),
    CHECK (((outcome='APPLIED' AND world_instance_id IS NOT NULL AND world_instance_id>0
        AND private_game_instance_key IS NOT NULL AND private_game_instance_key>0)
        OR (outcome='NOT_APPLIED' AND world_instance_id IS NULL AND private_game_instance_key IS NULL)) IS TRUE)
);

CREATE TABLE world_private_fresh_execution_manifest (
    transaction_id BIGINT NOT NULL,
    operation_id UUID NOT NULL REFERENCES world_private_fresh_operation(operation_id),
    table_name VARCHAR(63) NOT NULL CHECK (table_name IN ('world_instance','region_instance',
        'zone_instance','room_instance','room_instance_exit','world_canonical_instance_topology_identity')),
    row_id BIGINT NOT NULL,
    expected_new JSONB NOT NULL,
    PRIMARY KEY(transaction_id,table_name,row_id)
);
INSERT INTO world_runtime_family_classification(family_id,relation_name,family_role,state_class,fresh_disposition)
VALUES ('world_private_fresh_operation','world_private_fresh_operation','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'),
    ('world_private_instance_association','world_private_instance_association','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'),
    ('world_private_fresh_terminal','world_private_fresh_terminal','AUDIT','NOT_PAYLOAD','AUDIT_ONLY'),
    ('world_private_fresh_execution_manifest','world_private_fresh_execution_manifest','CONTROL','NOT_PAYLOAD','CONTROL_ONLY');

-- [jooq ignore start]
REVOKE ALL ON world_private_fresh_operation,world_private_instance_association,
    world_private_fresh_terminal,world_private_fresh_execution_manifest FROM PUBLIC;

CREATE FUNCTION world_private_fresh_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Private FRESH owner history is immutable' USING ERRCODE='55000'; END;
$$;
CREATE TRIGGER world_private_operation_immutable BEFORE UPDATE OR DELETE ON world_private_fresh_operation
    FOR EACH ROW EXECUTE FUNCTION world_private_fresh_immutable();
CREATE TRIGGER world_private_operation_no_truncate BEFORE TRUNCATE ON world_private_fresh_operation
    FOR EACH STATEMENT EXECUTE FUNCTION world_private_fresh_immutable();
CREATE TRIGGER world_private_association_immutable BEFORE UPDATE OR DELETE ON world_private_instance_association
    FOR EACH ROW EXECUTE FUNCTION world_private_fresh_immutable();
CREATE TRIGGER world_private_association_no_truncate BEFORE TRUNCATE ON world_private_instance_association
    FOR EACH STATEMENT EXECUTE FUNCTION world_private_fresh_immutable();
CREATE TRIGGER world_private_terminal_immutable BEFORE UPDATE OR DELETE ON world_private_fresh_terminal
    FOR EACH ROW EXECUTE FUNCTION world_private_fresh_immutable();
CREATE TRIGGER world_private_terminal_no_truncate BEFORE TRUNCATE ON world_private_fresh_terminal
    FOR EACH STATEMENT EXECUTE FUNCTION world_private_fresh_immutable();

-- Retain the exact existing nonprivate arms, including fixture and Human checks, verbatim.
DO $migration$
DECLARE old_check TEXT;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT old_check FROM pg_constraint
    WHERE conrelid='world_instance'::REGCLASS AND conname='ck_world_instance_canonical_creation_identity';
    ALTER TABLE world_instance DROP CONSTRAINT ck_world_instance_canonical_creation_identity;
    EXECUTE 'ALTER TABLE world_instance ADD CONSTRAINT ck_world_instance_canonical_creation_identity CHECK ((('
        ||old_check||') AND private_fresh_operation_id IS NULL) OR ((
            private_fresh_operation_id IS NOT NULL AND canonical_authority_kind=''PRIVATE_FRESH''
            AND canonical_game_instance_id IS NOT NULL AND canonical_target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL AND canonical_world_slug IS NOT NULL
            AND canonical_realm_id IS NOT NULL AND playtest_lifecycle_id IS NOT NULL
            AND playable_state_namespace_id IS NOT NULL AND playtest_state_generation=1
            AND playable_state_scope=''ISOLATED'' AND public_production IS FALSE
            AND canonical_launch_binding_operation_id IS NULL AND fixture_provenance_digest IS NULL
            AND game_session_tenant_id IS NULL AND game_session_game_instance_id IS NULL
            AND game_design_version_id IS NULL AND run_owned_fixture_binding_sha256 IS NULL
            AND run_owned_fixture_preparing_lifecycle_epoch IS NULL
            AND game_template_id IS NULL AND control_plane_request_id IS NULL AND launch_descriptor_id IS NULL
            AND generation_config_revision IS NULL AND release_bundle_id IS NULL AND version_state_epoch IS NULL
            AND runtime_flags_json IS NULL AND script_patch_version IS NULL AND remap_set_id IS NULL
            AND status=''PREPARING'' AND lifecycle_epoch=1 AND row_version=0
            AND failure_reason IS NULL AND termination_request_id IS NULL AND terminated_at IS NULL
        ) IS TRUE))';
END;
$migration$;

CREATE FUNCTION world_private_fresh_lock(p_id UUID) RETURNS VOID LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
BEGIN
    IF p_id IS NULL OR p_id='00000000-0000-0000-0000-000000000000'::UUID THEN
        RAISE EXCEPTION 'Private operation identity required' USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('world-private-fresh-operation:'||p_id::TEXT,0));
END;
$$;

-- Exact single-use manifests are emitted only by the private owner after retained-input checks.
CREATE FUNCTION world_consume_private_fresh_manifest(p_table TEXT,p_operation TEXT,p_old JSONB,p_new JSONB)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,"${serviceSchema}" AS $$
DECLARE consumed BIGINT;
BEGIN
    IF p_operation<>'INSERT' OR p_old IS NOT NULL OR p_new IS NULL THEN RETURN FALSE; END IF;
    DELETE FROM world_private_fresh_execution_manifest m USING world_private_fresh_operation o
    WHERE m.transaction_id=txid_current() AND m.table_name=p_table AND m.row_id=(p_new->>'id')::BIGINT
        AND m.expected_new=p_new AND o.operation_id=m.operation_id
        AND NOT EXISTS(SELECT 1 FROM world_private_fresh_terminal t WHERE t.operation_id=o.operation_id)
        AND ((p_table='world_instance' AND p_new->>'private_fresh_operation_id'=o.operation_id::TEXT
            AND p_new->>'canonical_game_instance_id'=o.canonical_game_instance_id::TEXT)
        OR (p_table<>'world_instance' AND EXISTS(SELECT 1 FROM world_instance w
            WHERE w.private_fresh_operation_id=o.operation_id AND w.canonical_game_instance_id=o.canonical_game_instance_id
                AND ((p_table='world_canonical_instance_topology_identity'
                    AND p_new->>'private_fresh_operation_id'=o.operation_id::TEXT
                    AND p_new->>'world_instance_id'=w.id::TEXT)
                OR (p_table IN ('region_instance','zone_instance','room_instance','room_instance_exit')
                    AND p_new->>'tenant_id'=w.tenant_id::TEXT AND p_new->>'game_instance_id'=w.game_instance_id::TEXT)))))
    RETURNING m.row_id INTO consumed;
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_private_fresh_manifest(TEXT,TEXT,JSONB,JSONB) FROM PUBLIC;

-- Preserve all old insertion owners. Only exact private manifests reach the new branch.
DO $migration$
DECLARE original TEXT; anchor TEXT; replacement TEXT; function_name TEXT;
BEGIN
    FOREACH function_name IN ARRAY ARRAY['world_claim_legacy_numeric_tenant_key','world_validate_canonical_preparation_row'] LOOP
        SELECT pg_get_functiondef(format('%I.%I()', '${serviceSchema}',function_name)::REGPROCEDURE) INTO STRICT original;
        anchor := E'BEGIN\n';
        replacement := E'BEGIN\n    IF TG_OP=''INSERT'' AND "${serviceSchema}".world_consume_private_fresh_manifest(TG_TABLE_NAME,TG_OP,NULL,to_jsonb(NEW)) THEN RETURN NEW; END IF;\n';
        IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
            RAISE EXCEPTION 'Expected exact private manifest receiving anchor: %',function_name;
        END IF;
        EXECUTE replace(original,anchor,replacement);
    END LOOP;
    SELECT pg_get_functiondef('"${serviceSchema}".world_validate_canonical_prepare_identity()'::REGPROCEDURE) INTO STRICT original;
    anchor := E'BEGIN\n';
    replacement := $body$BEGIN
    IF TG_OP<>'INSERT' AND OLD.private_fresh_operation_id IS NOT NULL THEN
        RAISE EXCEPTION 'Private FRESH World lifecycle and creation identity remain closed' USING ERRCODE='55000';
    END IF;
    IF TG_OP<>'DELETE' AND NEW.private_fresh_operation_id IS NOT NULL THEN
        IF TG_OP<>'INSERT' OR NOT EXISTS(SELECT 1 FROM "${serviceSchema}".world_private_fresh_operation o
            JOIN "${serviceSchema}".world_authored_version_identity v ON v.operation_id=o.version_identity_operation_id
            WHERE o.operation_id=NEW.private_fresh_operation_id AND o.canonical_game_instance_id=NEW.canonical_game_instance_id
                AND o.target_namespace=NEW.canonical_target_namespace AND o.canonical_tenant_id=NEW.canonical_tenant_id
                AND o.world_slug=NEW.canonical_world_slug AND o.canonical_realm_id=NEW.canonical_realm_id
                AND o.playtest_lifecycle_id=NEW.playtest_lifecycle_id AND o.playable_state_namespace_id=NEW.playable_state_namespace_id
                AND o.playtest_state_generation=NEW.playtest_state_generation AND o.playable_state_scope=NEW.playable_state_scope
                AND o.public_production=NEW.public_production AND v.local_tenant_key=NEW.tenant_id AND v.local_version_key=NEW.version_id
                AND o.input_json::JSONB->>'publishedReleaseBundleRef'=NEW.published_release_bundle_ref
                AND (o.input_json::JSONB->>'catalogRevision')::BIGINT=NEW.catalog_revision) THEN
            RAISE EXCEPTION 'Private World instance differs from exact original operation' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
$body$;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact canonical creation private-arm anchor';
    END IF;
    EXECUTE replace(original,anchor,replacement);
END;
$migration$;

CREATE FUNCTION world_private_fresh_assert_inputs(p JSONB) RETURNS VOID LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
DECLARE
    v world_authored_version_identity%ROWTYPE;
    f world_canonical_frozen_topology%ROWTYPE;
    i world_authored_source_intake%ROWTYPE;
    publication_owner world_design_publication_fence_owner%ROWTYPE;
    installed world_runtime_required_owner_snapshot%ROWTYPE;
    counts RECORD;
    catalog world_runtime_family_catalog_epoch%ROWTYPE;
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'Private FRESH requires writable READ COMMITTED' USING ERRCODE='23514';
    END IF;
    IF NOT ((p->>'schema'='world-private-fresh-input/v1' AND p->>'stateScope'='ISOLATED'
        AND jsonb_typeof(p->'publicProduction')='boolean' AND p->>'publicProduction'='false' AND p->>'generation'='1'
        AND p->>'targetNamespace' ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'
        AND length(p->>'targetNamespace') BETWEEN 1 AND 63
        AND p->>'worldSlug' ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND p->>'realmSlug' ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND length(p->>'worldSlug') BETWEEN 1 AND 120 AND length(p->>'realmSlug') BETWEEN 1 AND 120
        AND (p->>'catalogRevision')::BIGINT>0 AND (p->>'protectionFence')::BIGINT>0
        AND (p->>'forkExpiresAt')::TIMESTAMPTZ>clock_timestamp()
        AND jsonb_typeof(p->'regionCount')='number' AND jsonb_typeof(p->'zoneCount')='number'
        AND jsonb_typeof(p->'roomCount')='number' AND jsonb_typeof(p->'exitCount')='number'
        AND (p->>'regionCount')::BIGINT>0 AND (p->>'zoneCount')::BIGINT>0 AND (p->>'roomCount')::BIGINT>0
        AND (p->>'exitCount')::BIGINT>=0) IS TRUE) THEN
        RAISE EXCEPTION 'Exact nonpublic generation-one FRESH target required' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM unnest(ARRAY['operationId','canonicalGameInstanceId','canonicalTenantId','realmId',
        'lifecycleId','namespaceId','preparationRequestId','canonicalVersionId','versionIdentityOperationId','captureId',
        'sourceOperationId','worldIntakeOperationId','worldIntakeRequestId','gameSessionIntakeOperationId','targetPolicyId','protectionRequestId','protectionHoldId']) k
        WHERE p->>k IS NULL OR (p->>k)::UUID='00000000-0000-0000-0000-000000000000'::UUID)
        OR EXISTS(SELECT 1 FROM unnest(ARRAY['sourceDigest','sourceEvidenceDigest','worldIntakeReceiptDigest','gameSessionIntakeReceiptDigest',
            'publishedReleaseBundleDigest','policySetDigest','policyCarrierDigest','targetPolicyDigest','installationArtifactDigest']) k
            WHERE p->>k IS NULL OR p->>k !~ '^sha256:[0-9a-f]{64}$') THEN
        RAISE EXCEPTION 'Complete private identity and provenance required' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT v FROM world_authored_version_identity WHERE operation_id=(p->>'versionIdentityOperationId')::UUID;
    SELECT * INTO STRICT f FROM world_canonical_frozen_topology WHERE capture_id=(p->>'captureId')::UUID;
    SELECT * INTO STRICT i FROM world_authored_source_intake WHERE operation_id=v.intake_operation_id;
    IF v.target_namespace IS DISTINCT FROM p->>'targetNamespace'
        OR v.canonical_tenant_id IS DISTINCT FROM (p->>'canonicalTenantId')::UUID
        OR v.world_slug IS DISTINCT FROM p->>'worldSlug'
        OR v.canonical_version_id IS DISTINCT FROM (p->>'canonicalVersionId')::UUID
        OR v.source_operation_id IS DISTINCT FROM (p->>'sourceOperationId')::UUID
        OR v.source_evidence_digest IS DISTINCT FROM p->>'sourceEvidenceDigest'
        OR i.operation_id IS DISTINCT FROM (p->>'worldIntakeOperationId')::UUID
        OR i.receipt_digest IS DISTINCT FROM p->>'worldIntakeReceiptDigest'
        OR i.intake_request_id IS DISTINCT FROM (p->>'worldIntakeRequestId')::UUID
        OR i.source_provenance_kind IS DISTINCT FROM 'NEW_GAME_ROW'
        OR f.version_identity_operation_id IS DISTINCT FROM v.operation_id
        OR f.freeze_request_json::JSONB IS DISTINCT FROM p->'freeze'
        OR f.capture_status IS DISTINCT FROM 'CAPTURED_UNVERIFIED'
        OR f.graph_sha256 IS DISTINCT FROM encode(sha256(f.graph_bytes),'hex')
        OR 'sha256:'||encode(sha256(decode(p->>'sourceBytes','base64')),'hex') IS DISTINCT FROM p->>'sourceDigest'
        OR NOT EXISTS(SELECT 1 FROM world_authored_source_tenant_key_reservation r
            WHERE r.tenant_key=v.local_tenant_key AND r.claim_kind='CANONICAL_AUTHORED_SOURCE') THEN
        RAISE EXCEPTION 'Private FRESH differs from retained source, Version or frozen graph' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT publication_owner FROM world_design_publication_fence_owner
        WHERE local_tenant_key=v.local_tenant_key AND version_id=v.local_version_key FOR SHARE;
    IF publication_owner.owner_freeze_phase IS DISTINCT FROM 'PUBLISHED'
        OR publication_owner.current_publication_fence IS DISTINCT FROM f.publication_fence
        OR NOT EXISTS(SELECT 1 FROM world_design_publication_terminal t
            WHERE t.publication_fence=f.publication_fence AND t.outcome='PUBLISHED'
                AND t.target_namespace=v.target_namespace AND t.canonical_tenant_id=v.canonical_tenant_id
                AND t.canonical_version_id=v.canonical_version_id
                AND t.published_release_bundle_ref=p->>'publishedReleaseBundleRef'
                AND t.published_release_bundle_digest=p->>'publishedReleaseBundleDigest'
                AND t.world_evidence_bytes=decode(p->>'selectedWorldBytes','base64')) THEN
        RAISE EXCEPTION 'Private FRESH requires exact retained PUBLISHED World selector and release' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT catalog FROM world_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1;
    IF NOT world_runtime_family_catalog_installed()
        OR catalog.catalog_bytes IS DISTINCT FROM world_runtime_family_catalog_bytes(catalog.catalog_epoch) THEN
        RAISE EXCEPTION 'Private FRESH World catalog changed or is unavailable' USING ERRCODE='23514';
    END IF;
    SELECT s.* INTO installed FROM world_runtime_required_owner_snapshot s
        JOIN world_runtime_installation_current c ON c.target_namespace=s.target_namespace
            AND c.revision=s.registry_revision AND c.artifact_digest=s.artifact_digest
        JOIN world_runtime_installation_registration r ON r.revision=c.revision
        WHERE s.canonical_game_instance_id=(p->>'canonicalGameInstanceId')::UUID
            AND s.target_namespace=p->>'targetNamespace' AND s.registry_revision=(p->>'installationRevision')::BIGINT
            AND s.artifact_digest=p->>'installationArtifactDigest'
            AND s.required_owners=world_runtime_required_owners(r.artifact_bytes)
            AND EXISTS(SELECT 1 FROM jsonb_array_elements(s.required_owners) m
                CROSS JOIN LATERAL jsonb_array_elements(m->'catalogSources') cs
                WHERE m->>'ownerId'='world-management-service' AND cs->>'contract'='world-runtime-family-catalog/v1'
                    AND cs->>'epoch'=catalog.catalog_epoch::TEXT AND cs->>'digest'=catalog.catalog_digest
                    AND NOT EXISTS(SELECT family_id FROM world_runtime_family_classification WHERE state_class='S3'
                        EXCEPT SELECT family->>'familyId' FROM jsonb_array_elements(m->'families') family
                            WHERE family->>'catalogSourceId'=cs->>'sourceId' AND family->>'replacementClass'='S3')
                    AND NOT EXISTS(SELECT family->>'familyId' FROM jsonb_array_elements(m->'families') family
                            WHERE family->>'catalogSourceId'=cs->>'sourceId'
                        EXCEPT SELECT family_id FROM world_runtime_family_classification WHERE state_class='S3'))
        FOR SHARE OF c;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Private FRESH requires the complete current frozen installation owner snapshot' USING ERRCODE='23514';
    END IF;
    SELECT count(*) FILTER(WHERE family='REGION') regions,count(*) FILTER(WHERE family='ZONE') zones,
        count(*) FILTER(WHERE family='ROOM') rooms,count(*) FILTER(WHERE family='ROOM_EXIT') exits,
        count(*) FILTER(WHERE family NOT IN ('REGION','ZONE','ROOM','ROOM_EXIT')) unsupported INTO counts
        FROM world_authored_topology_identity WHERE request_id=f.request_id AND commit_id=f.commit_id
            AND version_identity_operation_id=v.operation_id;
    IF counts.unsupported<>0 OR counts.regions IS DISTINCT FROM (p->>'regionCount')::BIGINT
        OR counts.zones IS DISTINCT FROM (p->>'zoneCount')::BIGINT OR counts.rooms IS DISTINCT FROM (p->>'roomCount')::BIGINT
        OR counts.exits IS DISTINCT FROM (p->>'exitCount')::BIGINT
        OR EXISTS(SELECT 1 FROM region r JOIN world_authored_topology_identity m ON m.private_row_key=r.id AND m.family='REGION'
            WHERE m.request_id=f.request_id AND m.commit_id=f.commit_id AND m.version_identity_operation_id=v.operation_id
                AND r.tenant_id=v.local_tenant_key AND r.version_id=v.local_version_key
                AND (coalesce(r.weather,'')<>'' OR coalesce(r.generator_type,'')<>'' OR coalesce(r.generator_params,'')<>'')) THEN
        RAISE EXCEPTION 'Private FRESH rejects incomplete topology and unsupported generation, spawn or weather intent' USING ERRCODE='23514';
    END IF;
END;
$$;

CREATE FUNCTION world_private_fresh_intent(p_json TEXT,p_digest TEXT,p_original BYTEA) RETURNS UUID
LANGUAGE plpgsql SET search_path=pg_catalog,"${serviceSchema}" AS $$
DECLARE p JSONB:=p_json::JSONB; prior world_private_fresh_operation%ROWTYPE; f world_canonical_frozen_topology%ROWTYPE;
    owners world_runtime_required_owner_snapshot%ROWTYPE; result UUID:=(p->>'operationId')::UUID;
BEGIN
    IF p_json IS NULL OR p_digest IS NULL OR p_digest IS DISTINCT FROM 'sha256:'||encode(sha256(convert_to(p_json,'UTF8')),'hex')
        OR p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 262144
        OR p_original=convert_to(p_json,'UTF8') OR p_original=decode(p->>'sourceBytes','base64')
        OR p_original=decode(p->>'selectedWorldBytes','base64') THEN
        RAISE EXCEPTION 'Exact input and independent original-operation permission bytes required' USING ERRCODE='23514';
    END IF;
    PERFORM world_private_fresh_lock(result);
    SELECT * INTO prior FROM world_private_fresh_operation WHERE operation_id=result;
    IF FOUND THEN
        IF prior.input_json IS DISTINCT FROM p_json OR prior.input_digest IS DISTINCT FROM p_digest
            OR prior.original_operation_bytes IS DISTINCT FROM p_original THEN
            RAISE EXCEPTION 'Private operation already binds different exact original inputs' USING ERRCODE='23505';
        END IF;
        RETURN result;
    END IF;
    PERFORM world_private_fresh_assert_inputs(p);
    PERFORM pg_advisory_xact_lock(hashtextextended('world-private-fresh-runtime:'||(p->>'canonicalGameInstanceId'),0));
    SELECT * INTO STRICT f FROM world_canonical_frozen_topology WHERE capture_id=(p->>'captureId')::UUID;
    SELECT * INTO STRICT owners FROM world_runtime_required_owner_snapshot
        WHERE canonical_game_instance_id=(p->>'canonicalGameInstanceId')::UUID;
    INSERT INTO world_private_fresh_operation(operation_id,canonical_game_instance_id,target_namespace,canonical_tenant_id,
        world_slug,realm_slug,canonical_realm_id,playtest_lifecycle_id,playable_state_namespace_id,playtest_state_generation,
        playable_state_scope,public_production,preparation_request_id,canonical_version_id,version_identity_operation_id,
        capture_id,graph_bytes,graph_sha256,source_bytes,source_digest,selected_world_bytes,protection_request_id,
        protection_hold_id,protection_fence,registry_revision,artifact_digest,required_owners,input_json,input_digest,
        original_operation_bytes,original_operation_digest)
    VALUES(result,(p->>'canonicalGameInstanceId')::UUID,p->>'targetNamespace',(p->>'canonicalTenantId')::UUID,
        p->>'worldSlug',p->>'realmSlug',(p->>'realmId')::UUID,(p->>'lifecycleId')::UUID,(p->>'namespaceId')::UUID,1,'ISOLATED',FALSE,
        (p->>'preparationRequestId')::UUID,(p->>'canonicalVersionId')::UUID,(p->>'versionIdentityOperationId')::UUID,
        f.capture_id,f.graph_bytes,f.graph_sha256,decode(p->>'sourceBytes','base64'),p->>'sourceDigest',decode(p->>'selectedWorldBytes','base64'),
        (p->>'protectionRequestId')::UUID,(p->>'protectionHoldId')::UUID,(p->>'protectionFence')::BIGINT,
        owners.registry_revision,owners.artifact_digest,owners.required_owners,p_json,p_digest,p_original,
        'sha256:'||encode(sha256(p_original),'hex'));
    RETURN result;
END;
$$;

CREATE FUNCTION world_private_fresh_insert_row(p_operation UUID,p_table TEXT,p_row JSONB) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,"${serviceSchema}" AS $$
BEGIN
    IF p_table NOT IN ('world_instance','region_instance','zone_instance','room_instance','room_instance_exit',
        'world_canonical_instance_topology_identity') THEN
        RAISE EXCEPTION 'Unsupported private FRESH row family' USING ERRCODE='23514';
    END IF;
    INSERT INTO world_private_fresh_execution_manifest VALUES(txid_current(),p_operation,p_table,(p_row->>'id')::BIGINT,p_row);
    EXECUTE format('INSERT INTO %I.%I SELECT (jsonb_populate_record(NULL::%I.%I,$1)).*',
        '${serviceSchema}',p_table,'${serviceSchema}',p_table) USING p_row;
    IF EXISTS(SELECT 1 FROM world_private_fresh_execution_manifest WHERE transaction_id=txid_current()) THEN
        RAISE EXCEPTION 'Unconsumed private FRESH execution manifest' USING ERRCODE='23514';
    END IF;
END;
$$;

CREATE FUNCTION world_private_fresh_finish(p_operation UUID,p_abort BOOLEAN) RETURNS UUID
LANGUAGE plpgsql SET search_path=pg_catalog,"${serviceSchema}" AS $$
DECLARE
    o world_private_fresh_operation%ROWTYPE;
    prior world_private_fresh_terminal%ROWTYPE;
    p JSONB;
    identity_row world_authored_version_identity%ROWTYPE;
    capture_row world_canonical_frozen_topology%ROWTYPE;
    world_row world_instance%ROWTYPE;
    region_runtime region_instance%ROWTYPE;
    zone_runtime zone_instance%ROWTYPE;
    room_runtime room_instance%ROWTYPE;
    exit_runtime room_instance_exit%ROWTYPE;
    topology_identity world_canonical_instance_topology_identity%ROWTYPE;
    source_region RECORD; source_zone RECORD; source_room RECORD; source_exit RECORD; parent_row RECORD; other_row RECORD;
    local_game_instance_key BIGINT; new_world_instance_id BIGINT; runtime_id BIGINT; runtime_uuid UUID;
    full_row JSONB; result_bytes BYTEA;
BEGIN
    IF p_abort IS NULL THEN RAISE EXCEPTION 'Explicit terminal operation required' USING ERRCODE='23514'; END IF;
    PERFORM world_private_fresh_lock(p_operation);
    SELECT * INTO STRICT o FROM world_private_fresh_operation WHERE operation_id=p_operation;
    SELECT * INTO prior FROM world_private_fresh_terminal WHERE operation_id=p_operation;
    IF FOUND THEN
        IF (prior.outcome='NOT_APPLIED') IS DISTINCT FROM p_abort THEN
            RAISE EXCEPTION 'Private operation already settled to a different terminal outcome' USING ERRCODE='23505';
        END IF;
        RETURN p_operation;
    END IF;
    p:=o.input_json::JSONB;
    IF p_abort THEN
        IF EXISTS(SELECT 1 FROM world_instance WHERE private_fresh_operation_id=p_operation)
            OR EXISTS(SELECT 1 FROM world_private_instance_association WHERE operation_id=p_operation) THEN
            RAISE EXCEPTION 'Cannot abort a materialized private operation' USING ERRCODE='23514';
        END IF;
        result_bytes:=convert_to(jsonb_build_object('schema','world-private-fresh-terminal/v1','operationId',p_operation,
            'canonicalGameInstanceId',o.canonical_game_instance_id,'inputDigest',o.input_digest,'outcome','NOT_APPLIED',
            'admissionAllowed',FALSE)::TEXT,'UTF8');
        INSERT INTO world_private_fresh_terminal VALUES(p_operation,o.canonical_game_instance_id,o.input_digest,
            'NOT_APPLIED',NULL,NULL,result_bytes,'sha256:'||encode(sha256(result_bytes),'hex'),clock_timestamp());
        RETURN p_operation;
    END IF;
    PERFORM world_private_fresh_assert_inputs(p);
    SELECT * INTO STRICT identity_row FROM world_authored_version_identity WHERE operation_id=o.version_identity_operation_id;
    SELECT * INTO STRICT capture_row FROM world_canonical_frozen_topology WHERE capture_id=o.capture_id;
    IF o.graph_bytes IS DISTINCT FROM capture_row.graph_bytes OR o.graph_sha256 IS DISTINCT FROM capture_row.graph_sha256 THEN
        RAISE EXCEPTION 'Private operation frozen graph changed' USING ERRCODE='23514';
    END IF;
    LOOP
        local_game_instance_key:=nextval('world_canonical_private_game_instance_key_seq');
        EXIT WHEN NOT EXISTS(SELECT 1 FROM world_instance WHERE tenant_id=identity_row.local_tenant_key AND game_instance_id=local_game_instance_key);
    END LOOP;
    new_world_instance_id:=nextval('world_instance_id_seq');
    world_row:=jsonb_populate_record(NULL::world_instance,jsonb_build_object(
        'id',new_world_instance_id,'tenant_id',identity_row.local_tenant_key,'game_instance_id',local_game_instance_key,
        'version_id',identity_row.local_version_key,'published_release_bundle_ref',p->>'publishedReleaseBundleRef',
        'status','PREPARING','lifecycle_epoch',1,'row_version',0,'created_at',CURRENT_TIMESTAMP::TIMESTAMP,'updated_at',CURRENT_TIMESTAMP::TIMESTAMP,
        'canonical_game_instance_id',o.canonical_game_instance_id,'canonical_target_namespace',o.target_namespace,
        'canonical_tenant_id',o.canonical_tenant_id,'canonical_world_slug',o.world_slug,'canonical_realm_id',o.canonical_realm_id,
        'catalog_revision',(p->>'catalogRevision')::BIGINT,'playtest_lifecycle_id',o.playtest_lifecycle_id,
        'playable_state_namespace_id',o.playable_state_namespace_id,'playtest_state_generation',1,'playable_state_scope','ISOLATED',
        'public_production',FALSE,'canonical_authority_kind','PRIVATE_FRESH','private_fresh_operation_id',p_operation));
    PERFORM world_private_fresh_insert_row(p_operation,'world_instance',to_jsonb(world_row));
    INSERT INTO world_private_instance_association(operation_id,canonical_game_instance_id,world_instance_id,private_game_instance_key,
        target_namespace,canonical_tenant_id,world_slug,canonical_realm_id,playtest_lifecycle_id,playable_state_namespace_id,
        playtest_state_generation,playable_state_scope,public_production)
    VALUES(p_operation,o.canonical_game_instance_id,new_world_instance_id,local_game_instance_key,o.target_namespace,o.canonical_tenant_id,
        o.world_slug,o.canonical_realm_id,o.playtest_lifecycle_id,o.playable_state_namespace_id,1,'ISOLATED',FALSE);
    FOR source_region IN
        SELECT m.template_id, m.private_row_key, r.shard_id, r.name, r.weather,
            r.generation_seed, r.generator_type, r.generator_params, r.spacing_multiplier
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".region r ON r.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'REGION'
            AND r.tenant_id = identity_row.local_tenant_key AND r.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        runtime_id := nextval('"${serviceSchema}".region_instance_id_seq');
        runtime_uuid := gen_random_uuid();
        region_runtime := jsonb_populate_record(NULL::"${serviceSchema}".region_instance,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key, 'world_instance_id', new_world_instance_id,
                'shard_id', source_region.shard_id, 'name', source_region.name,
                'weather', source_region.weather, 'generation_seed', source_region.generation_seed,
                'generator_type', source_region.generator_type,
                'generator_params', source_region.generator_params,
                'spacing_multiplier', source_region.spacing_multiplier, 'version', 0,
                'canonical_region_instance_id', runtime_uuid));
        full_row := to_jsonb(region_runtime);
        PERFORM world_private_fresh_insert_row(p_operation,'region_instance',full_row);

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id, 'private_fresh_operation_id', p_operation,
                'canonical_game_instance_id', o.canonical_game_instance_id,
                'family', 'REGION', 'template_id', source_region.template_id,
                'template_private_row_key', source_region.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', runtime_uuid,
                'runtime_room_instance_id', NULL));
        full_row := to_jsonb(topology_identity);
        PERFORM world_private_fresh_insert_row(p_operation,'world_canonical_instance_topology_identity',full_row);
    END LOOP;

    FOR source_zone IN
        SELECT m.template_id, m.private_row_key, z.region_id, z.name
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".zone z ON z.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'ZONE'
            AND z.tenant_id = identity_row.local_tenant_key AND z.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        SELECT ti.runtime_row_id INTO STRICT parent_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'REGION'
            AND ti.template_private_row_key = source_zone.region_id;
        runtime_id := nextval('"${serviceSchema}".zone_instance_id_seq');
        runtime_uuid := gen_random_uuid();
        zone_runtime := jsonb_populate_record(NULL::"${serviceSchema}".zone_instance,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key, 'zone_instance_id', runtime_id,
                'template_zone_id', source_zone.private_row_key,
                'region_instance_id', parent_row.runtime_row_id,
                'name', source_zone.name, 'version', 0,
                'canonical_zone_instance_id', runtime_uuid));
        full_row := to_jsonb(zone_runtime);
        PERFORM world_private_fresh_insert_row(p_operation,'zone_instance',full_row);

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id, 'private_fresh_operation_id', p_operation,
                'canonical_game_instance_id', o.canonical_game_instance_id,
                'family', 'ZONE', 'template_id', source_zone.template_id,
                'template_private_row_key', source_zone.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', runtime_uuid,
                'runtime_room_instance_id', NULL));
        full_row := to_jsonb(topology_identity);
        PERFORM world_private_fresh_insert_row(p_operation,'world_canonical_instance_topology_identity',full_row);
    END LOOP;

    FOR source_room IN
        SELECT m.template_id, m.private_row_key, r.zone_id, r.name, r.description,
            r.name_localized_variants_json, r.description_localized_variants_json
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room r ON r.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'ROOM'
            AND r.tenant_id = identity_row.local_tenant_key AND r.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        SELECT ti.runtime_row_id INTO STRICT parent_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'ZONE'
            AND ti.template_private_row_key = source_room.zone_id;
        SELECT ti.runtime_row_id INTO STRICT other_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        JOIN "${serviceSchema}".zone z ON z.id = source_room.zone_id
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'REGION'
            AND ti.template_private_row_key = z.region_id;
        runtime_id := nextval('"${serviceSchema}".room_instance_id_seq');
        room_runtime := jsonb_populate_record(NULL::"${serviceSchema}".room_instance,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key, 'room_instance_row_id', runtime_id,
                'template_room_id', source_room.private_row_key,
                'region_instance_id', other_row.runtime_row_id,
                'zone_instance_id', parent_row.runtime_row_id,
                'name', source_room.name, 'description', source_room.description,
                'name_localized_variants_json', source_room.name_localized_variants_json,
                'description_localized_variants_json', source_room.description_localized_variants_json,
                'version', 0));
        full_row := to_jsonb(room_runtime);
        PERFORM world_private_fresh_insert_row(p_operation,'room_instance',full_row);

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id, 'private_fresh_operation_id', p_operation,
                'canonical_game_instance_id', o.canonical_game_instance_id,
                'family', 'ROOM', 'template_id', source_room.template_id,
                'template_private_row_key', source_room.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', NULL,
                'runtime_room_instance_id', runtime_id));
        full_row := to_jsonb(topology_identity);
        PERFORM world_private_fresh_insert_row(p_operation,'world_canonical_instance_topology_identity',full_row);
    END LOOP;

    FOR source_exit IN
        SELECT m.template_id, m.private_row_key, e.from_room_id, e.to_room_id, e.direction, e.cost
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room_exit e ON e.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'ROOM_EXIT'
            AND e.tenant_id = identity_row.local_tenant_key AND e.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        SELECT ti.runtime_room_instance_id INTO STRICT parent_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'ROOM'
            AND ti.template_private_row_key = source_exit.from_room_id;
        SELECT ti.runtime_room_instance_id INTO STRICT other_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'ROOM'
            AND ti.template_private_row_key = source_exit.to_room_id;
        runtime_id := nextval('"${serviceSchema}".room_instance_exit_id_seq');
        exit_runtime := jsonb_populate_record(NULL::"${serviceSchema}".room_instance_exit,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key,
                'from_room_instance_record_id', parent_row.runtime_room_instance_id,
                'to_room_instance_record_id', other_row.runtime_room_instance_id,
                'direction', source_exit.direction, 'cost', source_exit.cost, 'version', 0));
        full_row := to_jsonb(exit_runtime);
        PERFORM world_private_fresh_insert_row(p_operation,'room_instance_exit',full_row);

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id, 'private_fresh_operation_id', p_operation,
                'canonical_game_instance_id', o.canonical_game_instance_id,
                'family', 'ROOM_EXIT', 'template_id', source_exit.template_id,
                'template_private_row_key', source_exit.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', NULL,
                'runtime_room_instance_id', NULL));
        full_row := to_jsonb(topology_identity);
        PERFORM world_private_fresh_insert_row(p_operation,'world_canonical_instance_topology_identity',full_row);
    END LOOP;

    IF (SELECT count(*) FROM world_canonical_instance_topology_identity WHERE private_fresh_operation_id=p_operation)
        IS DISTINCT FROM ((p->>'regionCount')::BIGINT+(p->>'zoneCount')::BIGINT+(p->>'roomCount')::BIGINT+(p->>'exitCount')::BIGINT)
        OR EXISTS(SELECT 1 FROM world_private_fresh_execution_manifest WHERE transaction_id=txid_current()) THEN
        RAISE EXCEPTION 'Private FRESH did not materialize every exact selected topology row' USING ERRCODE='23514';
    END IF;
    result_bytes:=convert_to(jsonb_build_object('schema','world-private-fresh-terminal/v1','operationId',p_operation,
        'canonicalGameInstanceId',o.canonical_game_instance_id,'inputDigest',o.input_digest,'outcome','APPLIED',
        'worldInstanceId',new_world_instance_id::TEXT,'privateGameInstanceKey',local_game_instance_key::TEXT,
        'captureId',o.capture_id,'graphDigest','sha256:'||o.graph_sha256,'registryRevision',o.registry_revision::TEXT,
        'artifactDigest',o.artifact_digest,'requiredOwners',o.required_owners,'status','PREPARING','lifecycleEpoch','1',
        'admissionAllowed',FALSE)::TEXT,'UTF8');
    INSERT INTO world_private_fresh_terminal VALUES(p_operation,o.canonical_game_instance_id,o.input_digest,
        'APPLIED',new_world_instance_id,local_game_instance_key,result_bytes,'sha256:'||encode(sha256(result_bytes),'hex'),clock_timestamp());
    RETURN p_operation;
END;
$$;

-- A private World row cannot commit without its exact association AND append-only APPLIED result.
CREATE FUNCTION world_private_fresh_require_terminal() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
BEGIN
    IF NEW.private_fresh_operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM world_private_fresh_terminal t
        JOIN world_private_fresh_operation o USING(operation_id)
        JOIN world_private_instance_association a USING(operation_id,canonical_game_instance_id)
        WHERE t.operation_id=NEW.private_fresh_operation_id AND t.outcome='APPLIED'
            AND t.world_instance_id=NEW.id AND a.world_instance_id=NEW.id
            AND t.private_game_instance_key=NEW.game_instance_id AND a.private_game_instance_key=NEW.game_instance_id
            AND t.input_digest=o.input_digest) THEN
        RAISE EXCEPTION 'Private World materialization cannot commit without exact APPLIED terminal' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER world_private_fresh_settlement AFTER INSERT ON world_instance
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION world_private_fresh_require_terminal();

CREATE FUNCTION world_private_manifest_empty_at_commit() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM world_private_fresh_execution_manifest WHERE transaction_id=NEW.transaction_id) THEN
        RAISE EXCEPTION 'Private FRESH manifest cannot survive its owner transaction' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER world_private_manifest_no_commit AFTER INSERT ON world_private_fresh_execution_manifest
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION world_private_manifest_empty_at_commit();

REVOKE ALL ON FUNCTION world_private_fresh_lock(UUID),world_private_fresh_assert_inputs(JSONB),
    world_private_fresh_intent(TEXT,TEXT,BYTEA),world_private_fresh_insert_row(UUID,TEXT,JSONB),
    world_private_fresh_finish(UUID,BOOLEAN) FROM PUBLIC;


CREATE FUNCTION world_private_fresh_operation_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
DECLARE p JSONB:=NEW.input_json::JSONB; f world_canonical_frozen_topology%ROWTYPE; owners world_runtime_required_owner_snapshot%ROWTYPE;
BEGIN
    PERFORM world_private_fresh_assert_inputs(p);
    SELECT * INTO STRICT f FROM world_canonical_frozen_topology WHERE capture_id=NEW.capture_id;
    SELECT * INTO STRICT owners FROM world_runtime_required_owner_snapshot WHERE canonical_game_instance_id=NEW.canonical_game_instance_id;
    IF ROW(NEW.operation_id,NEW.canonical_game_instance_id,NEW.target_namespace,NEW.canonical_tenant_id,NEW.world_slug,NEW.realm_slug,
        NEW.canonical_realm_id,NEW.playtest_lifecycle_id,NEW.playable_state_namespace_id,NEW.preparation_request_id,
        NEW.canonical_version_id,NEW.version_identity_operation_id,NEW.capture_id,NEW.protection_request_id,NEW.protection_hold_id,NEW.protection_fence)
        IS DISTINCT FROM ROW((p->>'operationId')::UUID,(p->>'canonicalGameInstanceId')::UUID,p->>'targetNamespace',
        (p->>'canonicalTenantId')::UUID,p->>'worldSlug',p->>'realmSlug',(p->>'realmId')::UUID,(p->>'lifecycleId')::UUID,
        (p->>'namespaceId')::UUID,(p->>'preparationRequestId')::UUID,(p->>'canonicalVersionId')::UUID,
        (p->>'versionIdentityOperationId')::UUID,(p->>'captureId')::UUID,(p->>'protectionRequestId')::UUID,
        (p->>'protectionHoldId')::UUID,(p->>'protectionFence')::BIGINT)
        OR NEW.graph_bytes IS DISTINCT FROM f.graph_bytes OR NEW.graph_sha256 IS DISTINCT FROM f.graph_sha256
        OR NEW.source_bytes IS DISTINCT FROM decode(p->>'sourceBytes','base64') OR NEW.source_digest IS DISTINCT FROM p->>'sourceDigest'
        OR NEW.selected_world_bytes IS DISTINCT FROM decode(p->>'selectedWorldBytes','base64')
        OR NEW.registry_revision IS DISTINCT FROM (p->>'installationRevision')::BIGINT
        OR NEW.artifact_digest IS DISTINCT FROM p->>'installationArtifactDigest'
        OR NEW.registry_revision IS DISTINCT FROM owners.registry_revision OR NEW.artifact_digest IS DISTINCT FROM owners.artifact_digest
        OR NEW.required_owners IS DISTINCT FROM owners.required_owners
        OR NEW.original_operation_bytes=NEW.source_bytes OR NEW.original_operation_bytes=NEW.selected_world_bytes
        OR NEW.original_operation_bytes=NEW.graph_bytes OR NEW.original_operation_bytes=convert_to(NEW.input_json,'UTF8') THEN
        RAISE EXCEPTION 'Private operation must retain its exact complete original input binding' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_private_operation_binding BEFORE INSERT ON world_private_fresh_operation
    FOR EACH ROW EXECUTE FUNCTION world_private_fresh_operation_guard();

CREATE FUNCTION world_private_fresh_terminal_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
DECLARE o world_private_fresh_operation%ROWTYPE; evidence JSONB:=convert_from(NEW.terminal_bytes,'UTF8')::JSONB; a world_private_instance_association%ROWTYPE;
BEGIN
    PERFORM world_private_fresh_lock(NEW.operation_id);
    SELECT * INTO STRICT o FROM world_private_fresh_operation WHERE operation_id=NEW.operation_id;
    IF NEW.input_digest IS DISTINCT FROM o.input_digest OR NEW.canonical_game_instance_id IS DISTINCT FROM o.canonical_game_instance_id
        OR jsonb_typeof(evidence) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(evidence)) IS DISTINCT FROM (CASE WHEN NEW.outcome='APPLIED' THEN 15 ELSE 6 END)
        OR NOT ((evidence->>'schema'='world-private-fresh-terminal/v1' AND evidence->>'operationId'=o.operation_id::TEXT
            AND evidence->>'canonicalGameInstanceId'=o.canonical_game_instance_id::TEXT AND evidence->>'inputDigest'=o.input_digest
            AND evidence->>'outcome'=NEW.outcome AND evidence->>'admissionAllowed'='false') IS TRUE) THEN
        RAISE EXCEPTION 'Private terminal differs from exact retained operation' USING ERRCODE='23514';
    END IF;
    SELECT * INTO a FROM world_private_instance_association WHERE operation_id=o.operation_id;
    IF NEW.outcome='NOT_APPLIED' THEN
        IF a.operation_id IS NOT NULL OR EXISTS(SELECT 1 FROM world_instance WHERE private_fresh_operation_id=o.operation_id)
            OR evidence ? 'worldInstanceId' OR evidence ? 'privateGameInstanceKey' THEN
            RAISE EXCEPTION 'NOT_APPLIED private terminal cannot retain materialized state' USING ERRCODE='23514';
        END IF;
    ELSIF a.operation_id IS NULL OR NEW.world_instance_id IS DISTINCT FROM a.world_instance_id
        OR NEW.private_game_instance_key IS DISTINCT FROM a.private_game_instance_key
        OR NOT ((evidence->>'worldInstanceId'=a.world_instance_id::TEXT AND evidence->>'privateGameInstanceKey'=a.private_game_instance_key::TEXT
            AND evidence->>'captureId'=o.capture_id::TEXT AND evidence->>'graphDigest'='sha256:'||o.graph_sha256
            AND evidence->>'registryRevision'=o.registry_revision::TEXT AND evidence->>'artifactDigest'=o.artifact_digest
            AND evidence->'requiredOwners'=o.required_owners AND evidence->>'status'='PREPARING' AND evidence->>'lifecycleEpoch'='1') IS TRUE)
        OR NOT EXISTS(SELECT 1 FROM world_instance WHERE id=a.world_instance_id AND private_fresh_operation_id=o.operation_id
            AND status='PREPARING' AND lifecycle_epoch=1 AND row_version=0 AND game_instance_id=a.private_game_instance_key)
        OR (SELECT count(*) FROM world_canonical_instance_topology_identity WHERE private_fresh_operation_id=o.operation_id)
            IS DISTINCT FROM ((o.input_json::JSONB->>'regionCount')::BIGINT+(o.input_json::JSONB->>'zoneCount')::BIGINT
                +(o.input_json::JSONB->>'roomCount')::BIGINT+(o.input_json::JSONB->>'exitCount')::BIGINT) THEN
        RAISE EXCEPTION 'APPLIED private terminal requires complete PREPARING materialization' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_private_terminal_binding BEFORE INSERT ON world_private_fresh_terminal
    FOR EACH ROW EXECUTE FUNCTION world_private_fresh_terminal_guard();

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
        AND script='V1049__world_private_fresh_operation.sql' AND success);
$$;

CREATE OR REPLACE FUNCTION world_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
DECLARE
    v_families JSONB;
    v_facts JSONB;
BEGIN
    IF p_epoch IS NULL OR p_epoch <= 0 THEN
        RAISE EXCEPTION 'Positive World catalog epoch required' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM world_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM world_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed World schema family' USING ERRCODE = '23514';
    END IF;

    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,
        'component',component_name,'role',family_role,'stateClass',state_class,
        'freshDisposition',fresh_disposition) ORDER BY family_id COLLATE "C")
    INTO v_families FROM world_runtime_family_classification;

    WITH relations AS (
        SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}'
    ), definitions(kind,identity,definition) AS (
        SELECT 'TABLE',c.relname::TEXT,jsonb_build_array(c.relkind,c.relpersistence,
            c.relrowsecurity,c.relforcerowsecurity,c.reloptions,pg_get_partkeydef(c.oid))::TEXT
        FROM relations c WHERE c.relkind IN ('r','p','f')
        UNION ALL
        SELECT 'COLUMN',c.relname||'.'||a.attname,jsonb_build_array(a.attnum,
            format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
            pg_get_expr(d.adbin,d.adrelid),co.collname)::TEXT
        FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        LEFT JOIN pg_collation co ON co.oid=a.attcollation
        WHERE c.relkind IN ('r','p','f','v','m') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'CONSTRAINT',c.relname||'.'||k.conname,
            jsonb_build_array(pg_get_constraintdef(k.oid,true),k.convalidated,k.condeferrable,k.condeferred)::TEXT
        FROM relations c JOIN pg_constraint k ON k.conrelid=c.oid
        UNION ALL
        SELECT 'INDEX',c.relname::TEXT,jsonb_build_array(pg_get_indexdef(c.oid),i.indisvalid,i.indisready)::TEXT
        FROM relations c JOIN pg_index i ON i.indexrelid=c.oid
        UNION ALL
        SELECT 'TRIGGER',c.relname||'.'||t.tgname,jsonb_build_array(pg_get_triggerdef(t.oid,true),t.tgenabled)::TEXT
        FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid WHERE NOT t.tgisinternal
        UNION ALL
        SELECT 'POLICY',c.relname||'.'||p.polname,
            jsonb_build_array(p.polcmd,p.polpermissive,
                (SELECT jsonb_agg(r.rolname ORDER BY r.rolname COLLATE "C") FROM pg_roles r WHERE r.oid=ANY(p.polroles)),
                pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid))::TEXT
        FROM relations c JOIN pg_policy p ON p.polrelid=c.oid
        UNION ALL
        SELECT 'RULE',c.relname||'.'||r.rulename,pg_get_ruledef(r.oid,true)
        FROM relations c JOIN pg_rewrite r ON r.ev_class=c.oid
        UNION ALL
        SELECT 'ROUTINE',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='${serviceSchema}' AND p.prokind IN ('f','p')
        UNION ALL
        SELECT 'SEQUENCE',c.relname::TEXT,(to_jsonb(s)-'seqrelid')::TEXT
        FROM relations c JOIN pg_sequence s ON s.seqrelid=c.oid
        UNION ALL
        SELECT 'VIEW',c.relname::TEXT,jsonb_build_array(pg_get_viewdef(c.oid,true),c.reloptions)::TEXT
        FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1046' AND version IS DISTINCT FROM '1047' AND version IS DISTINCT FROM '1048' AND version IS DISTINCT FROM '1049'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1046','V1046__world_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1047','V1047__world_catalog_snapshot_exact_frames.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1048','V1048__world_protected_installation_registry.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1049','V1049__world_private_fresh_operation.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;

    IF jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384 THEN
        RAISE EXCEPTION 'Complete World catalog exceeds source bounds' USING ERRCODE = '23514';
    END IF;
    RETURN convert_to(jsonb_build_object('schema','world-runtime-family-catalog/v1',
        'owner','world-management-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts)::TEXT,'UTF8');
END;
$$;

INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
