-- Additive owner-local metadata only. No actor, holder, source, history or sequence is rewritten.
CREATE TABLE entity_runtime_family_classification (
    family_id VARCHAR(127) PRIMARY KEY,
    relation_name VARCHAR(63) NOT NULL,
    component_name VARCHAR(63) NOT NULL DEFAULT '',
    family_role VARCHAR(32) NOT NULL CHECK (family_role IN
        ('RUNTIME_PAYLOAD','MIXED','SOURCE','IDENTITY','ASSIGNMENT','CREATION','DISCOVERY',
         'RETENTION','PROJECTION','REPLAY','AUDIT','CONTROL','PROVENANCE','ALLOCATOR','HISTORY','CATALOG')),
    state_class VARCHAR(24) NOT NULL CHECK (state_class IN
        ('S1','S2','S3','CONDITIONAL_S1_S2','CONDITIONAL_HOLDER','UNRESOLVED','NOT_PAYLOAD')),
    fresh_disposition VARCHAR(48) NOT NULL CHECK (fresh_disposition IN
        ('PROVISIONAL_NAMESPACE','TEMPLATE_MAPPING_REQUIRED','BLOCKING_NAMESPACE_REMAP',
         'BLOCKING_HOLDER_CLOSURE','BLOCKING_MIXED_NPC','BLOCKING_ALLOCATOR_CLASS',
         'GROUND_ONLY_S3','PROJECTION_S3_RETAIN_LINEAGE','AUTHORED_SOURCE_ONLY',
         'SELECTED_EMPTY_SOURCE_ONLY','IDENTITY_ONLY','ASSIGNMENT_ONLY','CREATE_ONLY',
         'DISCOVERY_ONLY','RETENTION_ONLY','REPLAY_ONLY','AUDIT_ONLY','CONTROL_ONLY',
         'PROVENANCE_ONLY','HISTORY_ONLY','CATALOG_ONLY')),
    UNIQUE (relation_name, component_name),
    CHECK (relation_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (component_name = '' OR component_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (family_id = relation_name || CASE WHEN component_name = '' THEN '' ELSE '.' || component_name END),
    CHECK (family_role = CASE fresh_disposition
        WHEN 'PROVISIONAL_NAMESPACE' THEN 'RUNTIME_PAYLOAD'
        WHEN 'TEMPLATE_MAPPING_REQUIRED' THEN 'RUNTIME_PAYLOAD'
        WHEN 'BLOCKING_NAMESPACE_REMAP' THEN 'RUNTIME_PAYLOAD'
        WHEN 'BLOCKING_HOLDER_CLOSURE' THEN 'RUNTIME_PAYLOAD'
        WHEN 'GROUND_ONLY_S3' THEN 'RUNTIME_PAYLOAD'
        WHEN 'BLOCKING_MIXED_NPC' THEN 'MIXED'
        WHEN 'BLOCKING_ALLOCATOR_CLASS' THEN 'ALLOCATOR'
        WHEN 'PROJECTION_S3_RETAIN_LINEAGE' THEN 'PROJECTION'
        WHEN 'AUTHORED_SOURCE_ONLY' THEN 'SOURCE'
        WHEN 'SELECTED_EMPTY_SOURCE_ONLY' THEN 'SOURCE'
        WHEN 'IDENTITY_ONLY' THEN 'IDENTITY' WHEN 'ASSIGNMENT_ONLY' THEN 'ASSIGNMENT'
        WHEN 'CREATE_ONLY' THEN 'CREATION' WHEN 'DISCOVERY_ONLY' THEN 'DISCOVERY'
        WHEN 'RETENTION_ONLY' THEN 'RETENTION' WHEN 'REPLAY_ONLY' THEN 'REPLAY'
        WHEN 'AUDIT_ONLY' THEN 'AUDIT' WHEN 'CONTROL_ONLY' THEN 'CONTROL'
        WHEN 'PROVENANCE_ONLY' THEN 'PROVENANCE' WHEN 'HISTORY_ONLY' THEN 'HISTORY'
        WHEN 'CATALOG_ONLY' THEN 'CATALOG' END),
    CHECK (state_class = CASE fresh_disposition
        WHEN 'PROVISIONAL_NAMESPACE' THEN 'S1' WHEN 'TEMPLATE_MAPPING_REQUIRED' THEN 'S2'
        WHEN 'BLOCKING_NAMESPACE_REMAP' THEN 'CONDITIONAL_S1_S2'
        WHEN 'BLOCKING_HOLDER_CLOSURE' THEN 'CONDITIONAL_HOLDER'
        WHEN 'BLOCKING_MIXED_NPC' THEN 'UNRESOLVED'
        WHEN 'BLOCKING_ALLOCATOR_CLASS' THEN 'UNRESOLVED'
        WHEN 'GROUND_ONLY_S3' THEN 'S3' WHEN 'PROJECTION_S3_RETAIN_LINEAGE' THEN 'S3'
        ELSE 'NOT_PAYLOAD' END)
);

CREATE TABLE entity_runtime_family_catalog_epoch (
    catalog_epoch BIGINT PRIMARY KEY CHECK (catalog_epoch > 0),
    catalog_bytes BYTEA NOT NULL CHECK (octet_length(catalog_bytes) BETWEEN 1 AND 2097152),
    catalog_digest VARCHAR(71) NOT NULL CHECK (catalog_digest = 'sha256:' || encode(sha256(catalog_bytes), 'hex')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE entity_runtime_family_catalog_snapshot (
    read_request_id UUID PRIMARY KEY CHECK (read_request_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) BETWEEN 1 AND 1024),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_bytes), 'hex')),
    snapshot_id UUID NOT NULL UNIQUE CHECK (snapshot_id <> '00000000-0000-0000-0000-000000000000'),
    catalog_epoch BIGINT NOT NULL REFERENCES entity_runtime_family_catalog_epoch(catalog_epoch),
    catalog_digest VARCHAR(71) NOT NULL,
    evidence_bytes BYTEA NOT NULL CHECK (octet_length(evidence_bytes) BETWEEN 1 AND 2101248),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest = 'sha256:' || encode(sha256(evidence_bytes), 'hex')),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- One explicit base disposition per actual installed table; components preserve mixed roles.
-- "character" is the logical family; characters is its sole physical and wire spelling.
INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition)
VALUES
    ('characters','characters','','RUNTIME_PAYLOAD','S1','PROVISIONAL_NAMESPACE'),
    ('characters.body_layout_key','characters','body_layout_key','RUNTIME_PAYLOAD','S2','TEMPLATE_MAPPING_REQUIRED'),
    ('characters.authored_initial_core_json','characters','authored_initial_core_json','RUNTIME_PAYLOAD','S2','TEMPLATE_MAPPING_REQUIRED'),
    ('character_friend','character_friend','','RUNTIME_PAYLOAD','S1','PROVISIONAL_NAMESPACE'),
    ('inventory','inventory','','RUNTIME_PAYLOAD','S2','TEMPLATE_MAPPING_REQUIRED'),
    ('character_equipment','character_equipment','','RUNTIME_PAYLOAD','S2','TEMPLATE_MAPPING_REQUIRED'),
    ('actor_resource_states','actor_resource_states','','RUNTIME_PAYLOAD','CONDITIONAL_S1_S2','BLOCKING_NAMESPACE_REMAP'),
    ('actor_active_conditions','actor_active_conditions','','RUNTIME_PAYLOAD','CONDITIONAL_S1_S2','BLOCKING_NAMESPACE_REMAP'),
    ('container_instances','container_instances','','RUNTIME_PAYLOAD','CONDITIONAL_HOLDER','BLOCKING_HOLDER_CLOSURE'),
    ('item_instances','item_instances','','RUNTIME_PAYLOAD','CONDITIONAL_HOLDER','BLOCKING_HOLDER_CLOSURE'),
    ('item_stacks','item_stacks','','RUNTIME_PAYLOAD','CONDITIONAL_HOLDER','BLOCKING_HOLDER_CLOSURE'),
    ('room_ground_inventory','room_ground_inventory','','RUNTIME_PAYLOAD','S3','GROUND_ONLY_S3'),
    ('npcs','npcs','','MIXED','UNRESOLVED','BLOCKING_MIXED_NPC'),
    ('npcs.last_defeated_at','npcs','last_defeated_at','MIXED','UNRESOLVED','BLOCKING_MIXED_NPC'),
    ('item_visible_ref_counters','item_visible_ref_counters','','ALLOCATOR','UNRESOLVED','BLOCKING_ALLOCATOR_CLASS'),
    ('entity_preseeded_actor_runtime_projections','entity_preseeded_actor_runtime_projections','','PROJECTION','S3','PROJECTION_S3_RETAIN_LINEAGE'),
    ('entity_player_created_runtime_projections','entity_player_created_runtime_projections','','PROJECTION','S3','PROJECTION_S3_RETAIN_LINEAGE');

INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition)
SELECT 'characters.'||component,'characters',component,'PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'
FROM (VALUES ('account_id'),('tenant_id'),('playable_state_key'),('actor_identity_status'),
    ('actor_identity_quarantine_reason'),('creation_target_namespace'),('creation_operation_id'),
    ('actor_entry_policy'),('creation_descriptor_json'),('creation_descriptor_digest')) AS retained(component);

INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition)
SELECT 'npcs.'||component,'npcs',component,'SOURCE','NOT_PAYLOAD','AUTHORED_SOURCE_ONLY'
FROM (VALUES ('id'),('name'),('behavior'),('respawn_delay'),('tenant_id'),('version'),('version_id')) AS authored(component);

INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition)
SELECT name,name,'SOURCE','NOT_PAYLOAD','AUTHORED_SOURCE_ONLY' FROM (VALUES
    ('items'),('crafting_recipes'),('crafting_ingredients'),
    ('equipment_slot_definitions'),('body_layout_slot_definitions')) AS authored(name);

INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition)
SELECT name,name,'SOURCE','NOT_PAYLOAD','SELECTED_EMPTY_SOURCE_ONLY' FROM (VALUES
    ('entity_empty_source_other_actor_template_roots'),('entity_empty_source_equipment_capabilities'),
    ('entity_empty_source_equipment_attachment_rules'),('entity_empty_source_equipment_compatibility_rules'),
    ('entity_empty_source_equipment_occupancy_rules'),('entity_empty_source_actor_body_layout_assignments'),
    ('entity_empty_source_loot_table_roots'),('entity_empty_source_loot_item_mappings'),
    ('entity_empty_source_inbound_loot_bindings'),('entity_empty_source_balance_curve_roots'),
    ('entity_empty_source_balance_curve_attachments'),('entity_empty_source_archetype_roots'),
    ('entity_empty_source_archetype_constraints'),('entity_empty_source_archetype_assignments')) AS empty_source(name);

INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition)
VALUES
    ('entity_playable_state_namespace_scopes','entity_playable_state_namespace_scopes','IDENTITY','NOT_PAYLOAD','IDENTITY_ONLY'),
    ('entity_tenant_identities','entity_tenant_identities','IDENTITY','NOT_PAYLOAD','IDENTITY_ONLY'),
    ('entity_preseeded_actor_assignment_operations','entity_preseeded_actor_assignment_operations','ASSIGNMENT','NOT_PAYLOAD','ASSIGNMENT_ONLY'),
    ('entity_character_create_roster_guards','entity_character_create_roster_guards','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_character_create_operations','entity_character_create_operations','CREATION','NOT_PAYLOAD','CREATE_ONLY'),
    ('entity_canonical_gameplay_roster_snapshots','entity_canonical_gameplay_roster_snapshots','DISCOVERY','NOT_PAYLOAD','DISCOVERY_ONLY'),
    ('entity_canonical_gameplay_roster_snapshot_actors','entity_canonical_gameplay_roster_snapshot_actors','DISCOVERY','NOT_PAYLOAD','DISCOVERY_ONLY'),
    ('entity_canonical_roster_original_selections','entity_canonical_roster_original_selections','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'),
    ('entity_canonical_roster_admissions','entity_canonical_roster_admissions','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_canonical_roster_disposal_receipts','entity_canonical_roster_disposal_receipts','RETENTION','NOT_PAYLOAD','RETENTION_ONLY'),
    ('entity_canonical_roster_retirement_transfers','entity_canonical_roster_retirement_transfers','RETENTION','NOT_PAYLOAD','RETENTION_ONLY'),
    ('entity_canonical_roster_retention_lifecycle','entity_canonical_roster_retention_lifecycle','RETENTION','NOT_PAYLOAD','RETENTION_ONLY'),
    ('entity_canonical_roster_retention_references','entity_canonical_roster_retention_references','RETENTION','NOT_PAYLOAD','RETENTION_ONLY'),
    ('entity_canonical_roster_retention_releases','entity_canonical_roster_retention_releases','RETENTION','NOT_PAYLOAD','RETENTION_ONLY'),
    ('entity_canonical_roster_target_classifications','entity_canonical_roster_target_classifications','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_canonical_roster_retired_targets','entity_canonical_roster_retired_targets','RETENTION','NOT_PAYLOAD','RETENTION_ONLY'),
    ('entity_canonical_roster_snapshot_targets','entity_canonical_roster_snapshot_targets','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_empty_source_numeric_key_reservation','entity_empty_source_numeric_key_reservation','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_empty_selected_source_association','entity_empty_selected_source_association','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'),
    ('entity_empty_selected_source_family_state','entity_empty_selected_source_family_state','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_empty_selected_source_receipt','entity_empty_selected_source_receipt','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'),
    ('entity_mutation_effects','entity_mutation_effects','REPLAY','NOT_PAYLOAD','REPLAY_ONLY'),
    ('item_transfer_audits','item_transfer_audits','AUDIT','NOT_PAYLOAD','AUDIT_ONLY'),
    ('saga_instance','saga_instance','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('saga_step','saga_step','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('entity_runtime_family_classification','entity_runtime_family_classification','CATALOG','NOT_PAYLOAD','CATALOG_ONLY'),
    ('entity_runtime_family_catalog_epoch','entity_runtime_family_catalog_epoch','CATALOG','NOT_PAYLOAD','CATALOG_ONLY'),
    ('entity_runtime_family_catalog_snapshot','entity_runtime_family_catalog_snapshot','CATALOG','NOT_PAYLOAD','CATALOG_ONLY');

-- Flyway 13 supplies the ACTUAL configured history identifier. No deployment-name fallback.
-- Ordinary tables/metadata above remain visible to jOOQ. Only PostgreSQL census/guards/seed below
-- are excluded from DDLDatabase simulation. This is not an H2 simulation of pg_catalog.
-- [jooq ignore start]
INSERT INTO entity_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition)
VALUES ('${flyway:table}','${flyway:table}','HISTORY','NOT_PAYLOAD','HISTORY_ONLY');

CREATE FUNCTION entity_runtime_family_catalog_installed() RETURNS BOOLEAN LANGUAGE SQL STABLE
SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1025' AND script='V1025__entity_owner_runtime_family_catalog.sql' AND success);
$$;

CREATE FUNCTION entity_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_families JSONB; v_facts JSONB;
BEGIN
    IF p_epoch IS NULL OR p_epoch<=0 THEN
        RAISE EXCEPTION 'Positive Entity catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM entity_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM entity_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Entity schema family' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,
        'component',component_name,'role',family_role,'stateClass',state_class,
        'freshDisposition',fresh_disposition) ORDER BY family_id COLLATE "C")
    INTO v_families FROM entity_runtime_family_classification;

    WITH relations AS (
        SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${serviceSchema}'
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
        SELECT 'POLICY',c.relname||'.'||p.polname,jsonb_build_array(p.polcmd,p.polpermissive,
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
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1025'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1025','V1025__entity_owner_runtime_family_catalog.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;
    IF jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384 THEN
        RAISE EXCEPTION 'Complete Entity catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    RETURN convert_to(jsonb_build_object('schema','entity-runtime-family-catalog/v1',
        'owner','entity-management-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts)::TEXT,'UTF8');
END;
$$;

CREATE FUNCTION entity_runtime_catalog_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Entity runtime catalog evidence is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER entity_runtime_classification_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON entity_runtime_family_classification FOR EACH STATEMENT EXECUTE FUNCTION entity_runtime_catalog_immutable();
CREATE TRIGGER entity_runtime_epoch_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON entity_runtime_family_catalog_epoch FOR EACH STATEMENT EXECUTE FUNCTION entity_runtime_catalog_immutable();
CREATE TRIGGER entity_runtime_snapshot_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON entity_runtime_family_catalog_snapshot FOR EACH STATEMENT EXECUTE FUNCTION entity_runtime_catalog_immutable();

CREATE FUNCTION entity_runtime_catalog_epoch_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
BEGIN
    IF NEW.catalog_epoch<>coalesce((SELECT max(catalog_epoch) FROM entity_runtime_family_catalog_epoch),0)+1
       OR NEW.catalog_bytes<>entity_runtime_family_catalog_bytes(NEW.catalog_epoch) THEN
        RAISE EXCEPTION 'Entity epoch must bind the exact classified installed schema' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER entity_runtime_epoch_exact BEFORE INSERT ON entity_runtime_family_catalog_epoch
FOR EACH ROW EXECUTE FUNCTION entity_runtime_catalog_epoch_guard();

CREATE FUNCTION entity_runtime_catalog_snapshot_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_epoch entity_runtime_family_catalog_epoch%ROWTYPE;
BEGIN
    SELECT * INTO STRICT v_epoch FROM entity_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1;
    IF NEW.catalog_epoch<>v_epoch.catalog_epoch OR NEW.catalog_digest<>v_epoch.catalog_digest
       OR entity_runtime_family_catalog_bytes(v_epoch.catalog_epoch)<>v_epoch.catalog_bytes
       OR NOT entity_runtime_family_catalog_installed() THEN
        RAISE EXCEPTION 'Entity runtime catalog changed; new owner epoch required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER entity_runtime_snapshot_current BEFORE INSERT ON entity_runtime_family_catalog_snapshot
FOR EACH ROW EXECUTE FUNCTION entity_runtime_catalog_snapshot_guard();

-- Flyway writes V1025's own history row AFTER this transaction. The stable marker above replaces
-- that row in the seal; readers separately require its successful exact script installation.
-- Its post-transaction checksum/timing is not falsely claimed as part of this initial preimage.
INSERT INTO entity_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 1,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT entity_runtime_family_catalog_bytes(1) AS bytes) seal;
-- [jooq ignore stop]
