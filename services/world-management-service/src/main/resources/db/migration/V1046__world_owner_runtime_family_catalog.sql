-- Additive source only: never reads or changes gameplay payload rows or lifecycle authority.
CREATE TABLE world_runtime_family_classification (
    family_id VARCHAR(127) PRIMARY KEY,
    relation_name VARCHAR(63) NOT NULL,
    component_name VARCHAR(63) NOT NULL DEFAULT '',
    family_role VARCHAR(32) NOT NULL CHECK (family_role IN
        ('RUNTIME_PAYLOAD','LIFECYCLE','SOURCE','CONTROL','AUDIT','PROVENANCE','EXCLUDED','CATALOG')),
    state_class VARCHAR(16) NOT NULL CHECK (state_class IN ('S3','NOT_PAYLOAD')),
    fresh_disposition VARCHAR(48) NOT NULL CHECK (fresh_disposition IN
        ('REQUIRED_TOPOLOGY','REQUIRED_LIFECYCLE','NOT_INITIALIZED_ACTOR_LOCATION',
         'UNSUPPORTED_WEATHER','OWNER_EVENT_PROOF_REQUIRED','LEGACY_EXCLUDED',
         'SOURCE_ONLY','CONTROL_ONLY','AUDIT_ONLY','PROVENANCE_ONLY','CATALOG_ONLY')),
    UNIQUE (relation_name, component_name),
    CHECK (relation_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (component_name = '' OR component_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (family_id = relation_name || CASE WHEN component_name = '' THEN '' ELSE '.' || component_name END),
    CHECK ((family_role IN ('RUNTIME_PAYLOAD','LIFECYCLE','EXCLUDED')) = (state_class = 'S3')),
    CHECK (family_role = CASE fresh_disposition
        WHEN 'REQUIRED_TOPOLOGY' THEN 'RUNTIME_PAYLOAD'
        WHEN 'NOT_INITIALIZED_ACTOR_LOCATION' THEN 'RUNTIME_PAYLOAD'
        WHEN 'UNSUPPORTED_WEATHER' THEN 'RUNTIME_PAYLOAD'
        WHEN 'OWNER_EVENT_PROOF_REQUIRED' THEN 'RUNTIME_PAYLOAD'
        WHEN 'REQUIRED_LIFECYCLE' THEN 'LIFECYCLE'
        WHEN 'LEGACY_EXCLUDED' THEN 'EXCLUDED'
        WHEN 'SOURCE_ONLY' THEN 'SOURCE' WHEN 'CONTROL_ONLY' THEN 'CONTROL'
        WHEN 'AUDIT_ONLY' THEN 'AUDIT' WHEN 'PROVENANCE_ONLY' THEN 'PROVENANCE'
        WHEN 'CATALOG_ONLY' THEN 'CATALOG' END)
);

CREATE TABLE world_runtime_family_catalog_epoch (
    catalog_epoch BIGINT PRIMARY KEY CHECK (catalog_epoch > 0),
    catalog_bytes BYTEA NOT NULL CHECK (octet_length(catalog_bytes) BETWEEN 1 AND 2097152),
    catalog_digest VARCHAR(71) NOT NULL CHECK (catalog_digest = 'sha256:' || encode(sha256(catalog_bytes), 'hex')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE world_runtime_family_catalog_snapshot (
    read_request_id UUID PRIMARY KEY CHECK (read_request_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) BETWEEN 1 AND 1024),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_bytes), 'hex')),
    snapshot_id UUID NOT NULL UNIQUE CHECK (snapshot_id <> '00000000-0000-0000-0000-000000000000'),
    catalog_epoch BIGINT NOT NULL REFERENCES world_runtime_family_catalog_epoch(catalog_epoch),
    catalog_digest VARCHAR(71) NOT NULL,
    evidence_bytes BYTEA NOT NULL CHECK (octet_length(evidence_bytes) BETWEEN 1 AND 2101248),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest = 'sha256:' || encode(sha256(evidence_bytes), 'hex')),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Explicit current owner metadata, not a name-prefix or absent-row inference. Future tables
-- have no default disposition. Source/control/provenance records are never clone payload.
INSERT INTO world_runtime_family_classification
    (family_id, relation_name, component_name, family_role, state_class, fresh_disposition)
VALUES
    ('world_instance','world_instance','','LIFECYCLE','S3','REQUIRED_LIFECYCLE'),
    ('region_instance','region_instance','','RUNTIME_PAYLOAD','S3','REQUIRED_TOPOLOGY'),
    ('zone_instance','zone_instance','','RUNTIME_PAYLOAD','S3','REQUIRED_TOPOLOGY'),
    ('room_instance','room_instance','','RUNTIME_PAYLOAD','S3','REQUIRED_TOPOLOGY'),
    ('room_instance_exit','room_instance_exit','','RUNTIME_PAYLOAD','S3','REQUIRED_TOPOLOGY'),
    ('region_instance.weather','region_instance','weather','RUNTIME_PAYLOAD','S3','UNSUPPORTED_WEATHER'),
    ('world_event','world_event','','RUNTIME_PAYLOAD','S3','OWNER_EVENT_PROOF_REQUIRED'),
    ('character_location','character_location','','RUNTIME_PAYLOAD','S3','NOT_INITIALIZED_ACTOR_LOCATION'),
    ('instance','instance','','EXCLUDED','S3','LEGACY_EXCLUDED');

INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
SELECT name, name, 'SOURCE', 'NOT_PAYLOAD', 'SOURCE_ONLY' FROM (VALUES
    ('region'),('zone'),('room'),('room_exit'),('generation_rule'),('world_entity_spawn_binding'),
    ('world_authored_graph_snapshot'),('world_authored_source_intake'),
    ('world_authored_topology_identity'),('world_authored_version_identity'),
    ('world_canonical_frozen_topology'),('world_complete_launch_binding'),
    ('world_draft_start_location_receipt'),('world_selected_publication_artifact_inventory')
) AS source(name);

INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
SELECT name, name, 'CONTROL', 'NOT_PAYLOAD', 'CONTROL_ONLY' FROM (VALUES
    ('initial_admission_bind_hold'),('world_authored_source_tenant_association'),
    ('world_authored_source_tenant_key_reservation'),('world_canonical_activation_execution_manifest'),
    ('world_canonical_initial_admission_hold_finalization_manifest'),
    ('world_canonical_instance_activation_fence'),('world_canonical_instance_activation_operation'),
    ('world_canonical_instance_preparation_execution_manifest'),('world_canonical_player_admission_hold'),
    ('world_design_aggregate_epoch'),('world_design_scope_epoch'),
    ('world_design_publication_account_binding'),('world_design_publication_fence_attempt'),
    ('world_design_publication_fence_owner'),('world_region_draft_execution_manifest'),
    ('saga_instance'),('saga_step')
) AS control(name);

INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
SELECT name, name, 'AUDIT', 'NOT_PAYLOAD', 'AUDIT_ONLY' FROM (VALUES
    ('world_design_revision_ledger'),('world_design_publication_terminal'),
    ('world_draft_graph_application'),('world_draft_graph_terminal_identity'),
    ('world_draft_terminal_outcome'),('world_region_draft_commit'),('world_topology_draft_commit')
) AS audit(name);

INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
SELECT name, name, 'PROVENANCE', 'NOT_PAYLOAD', 'PROVENANCE_ONLY' FROM (VALUES
    ('world_canonical_initial_player_location_operation'),('world_canonical_instance_association'),
    ('world_canonical_instance_execution'),('world_canonical_instance_preparation'),
    ('world_canonical_instance_topology_identity'),('world_canonical_preparation_start_location')
) AS provenance(name);

INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
SELECT name, name, 'CATALOG', 'NOT_PAYLOAD', 'CATALOG_ONLY' FROM (VALUES
    ('world_runtime_family_classification'),('world_runtime_family_catalog_epoch'),
    ('world_runtime_family_catalog_snapshot')
) AS catalog(name);

-- The parser-independent seal is generated from live pg_catalog and installed migration facts.
-- Definition hashes omit row data, sequence positions, OIDs, statistics and installation times.
-- This migration's own Flyway row is written AFTER this transaction. Its stable installation
-- marker is included instead; all prior and subsequent migration rows bind script/checksum/success.
-- [jooq ignore start]
-- Flyway supplies the actual configured history identifier; never assume a deployment schema.
INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
VALUES ('${flyway:table}', '${flyway:table}', 'CONTROL', 'NOT_PAYLOAD', 'CONTROL_ONLY');

CREATE FUNCTION world_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1046' AND script='V1046__world_owner_runtime_family_catalog.sql' AND success);
$$;

CREATE FUNCTION world_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
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
        SELECT 'VIEW',c.relname::TEXT,pg_get_viewdef(c.oid,true) FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1046'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1046','V1046__world_owner_runtime_family_catalog.sql'
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

CREATE FUNCTION world_runtime_catalog_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'World runtime catalog evidence is immutable' USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER world_runtime_classification_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON world_runtime_family_classification FOR EACH STATEMENT EXECUTE FUNCTION world_runtime_catalog_immutable();
CREATE TRIGGER world_runtime_epoch_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON world_runtime_family_catalog_epoch FOR EACH STATEMENT EXECUTE FUNCTION world_runtime_catalog_immutable();
CREATE TRIGGER world_runtime_snapshot_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON world_runtime_family_catalog_snapshot FOR EACH STATEMENT EXECUTE FUNCTION world_runtime_catalog_immutable();

CREATE FUNCTION world_runtime_catalog_epoch_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
BEGIN
    IF NEW.catalog_epoch<>coalesce((SELECT max(catalog_epoch) FROM world_runtime_family_catalog_epoch),0)+1
       OR NEW.catalog_bytes<>world_runtime_family_catalog_bytes(NEW.catalog_epoch) THEN
        RAISE EXCEPTION 'World epoch must bind the exact classified installed schema' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_runtime_epoch_exact BEFORE INSERT ON world_runtime_family_catalog_epoch
FOR EACH ROW EXECUTE FUNCTION world_runtime_catalog_epoch_guard();

CREATE FUNCTION world_runtime_catalog_snapshot_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_epoch world_runtime_family_catalog_epoch%ROWTYPE;
BEGIN
    SELECT * INTO STRICT v_epoch FROM world_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1;
    IF NEW.catalog_epoch<>v_epoch.catalog_epoch OR NEW.catalog_digest<>v_epoch.catalog_digest
       OR world_runtime_family_catalog_bytes(v_epoch.catalog_epoch)<>v_epoch.catalog_bytes THEN
        RAISE EXCEPTION 'World runtime catalog changed; new owner epoch required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_runtime_snapshot_current BEFORE INSERT ON world_runtime_family_catalog_snapshot
FOR EACH ROW EXECUTE FUNCTION world_runtime_catalog_snapshot_guard();
-- [jooq ignore stop]

-- PostgreSQL-only bootstrap invokes the pg_catalog census function above; H2 cannot simulate it.
-- [jooq ignore start]
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 1,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (SELECT world_runtime_family_catalog_bytes(1) AS bytes) seal;
-- [jooq ignore stop]
