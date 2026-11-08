-- Identity/provenance preparation only. No game_instances or World lifecycle payload is created.
CREATE TABLE game_session_private_fresh_runtime_allocations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_instance_id UUID NOT NULL UNIQUE CHECK (game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    realm_id UUID NOT NULL REFERENCES game_session_canonical_realm_catalog(realm_id) ON DELETE RESTRICT,
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision > 0),
    catalog_request_id UUID NOT NULL,
    catalog_request_digest VARCHAR(71) NOT NULL CHECK (catalog_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    catalog_receipt_digest VARCHAR(71) NOT NULL CHECK (catalog_receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    preparation_request_id UUID NOT NULL UNIQUE,
    preparation_request_digest VARCHAR(71) NOT NULL CHECK (preparation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    playtest_lifecycle_id UUID NOT NULL UNIQUE,
    playable_state_namespace_id UUID NOT NULL UNIQUE
        REFERENCES game_session_initial_playtest_preparations(playable_state_namespace_id) ON DELETE RESTRICT,
    playtest_state_generation BIGINT NOT NULL CHECK (playtest_state_generation = 1),
    canonical_version_id UUID NOT NULL,
    published_release_bundle_ref VARCHAR(1024) NOT NULL,
    published_release_bundle_digest VARCHAR(71) NOT NULL CHECK (published_release_bundle_digest ~ '^sha256:[0-9a-f]{64}$'),
    selection_request_id UUID NOT NULL REFERENCES game_session_private_playtest_selections(request_id) ON DELETE RESTRICT,
    selection_head_revision BIGINT NOT NULL CHECK (selection_head_revision > 0),
    selection_request_digest VARCHAR(71) NOT NULL CHECK (selection_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_preimage BYTEA NOT NULL CHECK (octet_length(request_preimage) BETWEEN 1 AND 8192),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_preimage), 'hex')),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (preparation_request_id, playtest_lifecycle_id)
        REFERENCES game_session_initial_playtest_preparations(request_id, playtest_lifecycle_id) ON DELETE RESTRICT,
    CHECK (game_instance_id <> realm_id AND game_instance_id <> playtest_lifecycle_id
        AND game_instance_id <> playable_state_namespace_id)
);

-- [jooq ignore start]
CREATE FUNCTION gs_private_fresh_runtime_request_bytes(a game_session_private_fresh_runtime_allocations)
RETURNS BYTEA LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE fields TEXT[]; item TEXT; framed BYTEA := ''::BYTEA;
BEGIN
    fields := ARRAY['game-session-private-fresh-runtime-request/v1', a.request_id::TEXT,
        a.preparation_request_id::TEXT, a.playtest_lifecycle_id::TEXT, a.realm_id::TEXT,
        a.catalog_revision::TEXT, a.catalog_request_id::TEXT, a.catalog_receipt_digest,
        a.selection_request_id::TEXT, a.selection_head_revision::TEXT, a.target_namespace,
        a.canonical_tenant_id::TEXT, a.world_slug, a.realm_slug, a.catalog_request_digest,
        a.preparation_request_digest, a.playable_state_namespace_id::TEXT,
        a.playtest_state_generation::TEXT, a.canonical_version_id::TEXT,
        a.published_release_bundle_ref, a.published_release_bundle_digest, a.selection_request_digest];
    FOREACH item IN ARRAY fields LOOP
        framed := framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    RETURN framed;
END;
$$;

CREATE FUNCTION gs_private_fresh_runtime_receipt_digest(a game_session_private_fresh_runtime_allocations)
RETURNS TEXT LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE fields TEXT[]; item TEXT; framed BYTEA := ''::BYTEA;
BEGIN
    fields := ARRAY['game-session-private-fresh-runtime-receipt/v1', a.request_id::TEXT,
        a.request_digest, a.game_instance_id::TEXT];
    FOREACH item IN ARRAY fields LOOP
        framed := framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    RETURN 'sha256:' || encode(sha256(framed), 'hex');
END;
$$;

-- Reused at INSERT and final commit. All copied fields are checked against their existing owners;
-- no caller-provided lifecycle, namespace, catalog or selection fact becomes new authority.
CREATE FUNCTION gs_private_fresh_runtime_require_current(a game_session_private_fresh_runtime_allocations)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    preparation game_session_initial_playtest_preparations%ROWTYPE;
    original game_session_canonical_realm_catalog%ROWTYPE;
    revision game_session_private_catalog_revision_history%ROWTYPE;
    catalog_head game_session_current_private_catalog_revisions%ROWTYPE;
    selection game_session_private_playtest_selections%ROWTYPE;
    selection_head game_session_current_private_playtests%ROWTYPE;
    source game_session_canonical_realm_catalog%ROWTYPE;
BEGIN
    SELECT * INTO STRICT preparation FROM game_session_initial_playtest_preparations
        WHERE request_id=a.preparation_request_id AND playtest_lifecycle_id=a.playtest_lifecycle_id FOR SHARE;
    SELECT * INTO STRICT original FROM game_session_canonical_realm_catalog
        WHERE realm_id=a.realm_id AND private_preparation_request_id IS NOT NULL FOR SHARE;
    SELECT * INTO STRICT catalog_head FROM game_session_current_private_catalog_revisions
        WHERE realm_id=a.realm_id FOR SHARE;
    SELECT * INTO STRICT selection_head FROM game_session_current_private_playtests
        WHERE target_namespace=a.target_namespace AND canonical_tenant_id=a.canonical_tenant_id
            AND world_slug=a.world_slug AND realm_slug=a.realm_slug FOR SHARE;
    SELECT * INTO STRICT selection FROM game_session_private_playtest_selections
        WHERE request_id=a.selection_request_id FOR SHARE;
    SELECT * INTO STRICT source FROM game_session_canonical_realm_catalog
        WHERE target_namespace=preparation.target_namespace AND realm_id=preparation.source_catalog_realm_id FOR SHARE;

    IF preparation.initialization_mode <> 'FRESH' OR preparation.preparation_state <> 'PREPARING'
        OR preparation.playtest_state_generation <> 1
        OR NOT preparation.target_policy_visible OR preparation.target_policy_public_production
        OR preparation.target_policy_state_scope <> 'ISOLATED'
        OR preparation.target_policy_world_slug <> a.world_slug
        OR preparation.target_policy_realm_slug <> a.realm_slug
        OR preparation.target_namespace <> a.target_namespace
        OR preparation.canonical_tenant_id <> a.canonical_tenant_id
        OR preparation.world_slug <> a.world_slug OR preparation.realm_slug <> a.realm_slug
        OR preparation.request_digest <> a.preparation_request_digest
        OR preparation.playable_state_namespace_id <> a.playable_state_namespace_id
        OR preparation.playtest_state_generation <> a.playtest_state_generation
        OR preparation.canonical_version_id <> a.canonical_version_id
        OR preparation.published_release_bundle_ref <> a.published_release_bundle_ref
        OR preparation.published_release_bundle_digest <> a.published_release_bundle_digest
        OR preparation.fork_expiry_second + preparation.fork_expiry_nano / 1000000000.0
            <= extract(epoch FROM clock_timestamp())
        OR original.target_namespace <> a.target_namespace
        OR original.canonical_tenant_id <> a.canonical_tenant_id
        OR original.world_slug <> a.world_slug OR original.realm_slug <> a.realm_slug
        OR original.visible OR original.public_production OR original.state_scope <> 'ISOLATED'
        OR catalog_head.catalog_revision <> a.catalog_revision
        OR catalog_head.revision_request_id <> a.catalog_request_id
        OR catalog_head.preparation_request_id <> a.preparation_request_id
        OR catalog_head.playtest_lifecycle_id <> a.playtest_lifecycle_id
        OR selection_head.head_revision <> a.selection_head_revision
        OR selection_head.selection_request_id <> a.selection_request_id
        OR selection_head.playtest_lifecycle_id <> a.playtest_lifecycle_id
        OR selection.target_namespace <> a.target_namespace OR selection.canonical_tenant_id <> a.canonical_tenant_id
        OR selection.world_slug <> a.world_slug OR selection.realm_slug <> a.realm_slug
        OR selection.preparation_request_id <> a.preparation_request_id
        OR selection.playtest_lifecycle_id <> a.playtest_lifecycle_id
        OR selection.head_revision <> a.selection_head_revision
        OR selection.request_digest <> a.selection_request_digest
        OR selection.request_digest <> 'sha256:' || encode(sha256(selection.request_preimage), 'hex') THEN
        RAISE EXCEPTION 'Private runtime allocation requires exact current unexpired FRESH catalog and selection source'
            USING ERRCODE='23514';
    END IF;

    IF a.catalog_revision=1 THEN
        IF original.creation_request_id <> a.catalog_request_id
            OR original.request_digest <> a.catalog_request_digest
            OR original.receipt_digest <> a.catalog_receipt_digest
            OR original.private_preparation_request_id <> a.preparation_request_id
            OR original.private_playtest_lifecycle_id <> a.playtest_lifecycle_id
            OR original.playable_state_namespace_id <> a.playable_state_namespace_id
            OR original.private_canonical_version_id <> a.canonical_version_id
            OR original.private_published_release_bundle_ref <> a.published_release_bundle_ref
            OR original.private_published_release_bundle_digest <> a.published_release_bundle_digest THEN
            RAISE EXCEPTION 'Private runtime allocation differs from retained original catalog binding' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO STRICT revision FROM game_session_private_catalog_revision_history
            WHERE realm_id=a.realm_id AND catalog_revision=a.catalog_revision FOR SHARE;
        IF revision.request_id <> a.catalog_request_id OR revision.request_digest <> a.catalog_request_digest
            OR revision.receipt_digest <> a.catalog_receipt_digest
            OR revision.preparation_request_id <> a.preparation_request_id
            OR revision.playtest_lifecycle_id <> a.playtest_lifecycle_id
            OR revision.playable_state_namespace_id <> a.playable_state_namespace_id THEN
            RAISE EXCEPTION 'Private runtime allocation differs from retained current catalog revision' USING ERRCODE='23514';
        END IF;
    END IF;

    IF NOT source.visible OR NOT source.public_production OR source.complete_policy_set_evidence IS NULL
        OR source.fixture_policy_request_bytes IS NOT NULL OR source.fixture_policy_seal_operation_id IS NOT NULL
        OR source.fixture_policy_seal_request_digest IS NOT NULL OR source.fixture_policy_seal_evidence_digest IS NOT NULL
        OR source.canonical_tenant_id <> a.canonical_tenant_id OR source.world_slug <> a.world_slug
        OR source.creation_request_id <> preparation.source_catalog_request_id
        OR source.catalog_revision <> preparation.source_catalog_revision
        OR source.receipt_digest <> preparation.source_catalog_receipt_digest
        OR source.source_intake_operation_id <> preparation.source_intake_operation_id
        OR source.source_intake_receipt_digest <> preparation.source_intake_receipt_digest
        OR source.source_operation_id <> preparation.source_operation_id
        OR source.source_evidence_digest <> preparation.source_evidence_digest
        OR preparation.source_policy_carrier_digest <> 'sha256:' || encode(sha256(source.complete_policy_set_evidence), 'hex') THEN
        RAISE EXCEPTION 'Private runtime allocation requires exact complete retained Human source' USING ERRCODE='23514';
    END IF;
END;
$$;

CREATE FUNCTION gs_private_fresh_runtime_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'serializable'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Private runtime allocation requires writable SERIALIZABLE owner transaction' USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('private-fresh-preparation/' || NEW.preparation_request_id::TEXT,0));
    PERFORM pg_advisory_xact_lock(hashtextextended('private-fresh-runtime-request/' || NEW.request_id::TEXT,0));
    IF NEW.request_preimage IS DISTINCT FROM gs_private_fresh_runtime_request_bytes(NEW)
        OR NEW.receipt_digest IS DISTINCT FROM gs_private_fresh_runtime_receipt_digest(NEW) THEN
        RAISE EXCEPTION 'Private runtime allocation request or receipt bytes differ from retained intent' USING ERRCODE='23514';
    END IF;
    PERFORM gs_private_fresh_runtime_require_current(NEW);
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_private_fresh_runtime_insert_guard BEFORE INSERT
    ON game_session_private_fresh_runtime_allocations FOR EACH ROW
    EXECUTE FUNCTION gs_private_fresh_runtime_insert_guard();

CREATE FUNCTION gs_private_fresh_runtime_commit_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM gs_private_fresh_runtime_require_current(NEW);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_private_fresh_runtime_commit_guard AFTER INSERT
    ON game_session_private_fresh_runtime_allocations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION gs_private_fresh_runtime_commit_guard();

CREATE FUNCTION gs_private_fresh_runtime_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Private runtime allocation provenance is immutable and retained' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER gs_private_fresh_runtime_immutable BEFORE UPDATE OR DELETE
    ON game_session_private_fresh_runtime_allocations FOR EACH ROW
    EXECUTE FUNCTION gs_private_fresh_runtime_no_mutation();
CREATE TRIGGER gs_private_fresh_runtime_no_truncate BEFORE TRUNCATE
    ON game_session_private_fresh_runtime_allocations FOR EACH STATEMENT
    EXECUTE FUNCTION gs_private_fresh_runtime_no_mutation();
-- [jooq ignore stop]

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_private_fresh_runtime_allocations','game_session_private_fresh_runtime_allocations',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','IMMUTABLE_RETAINED',
    'Immutable private FRESH runtime UUID and exact source/request provenance only; no running instance, World materialization, actor, credential, CLOSED, activation or admission authority.');

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1057__game_session_owner_runtime_family_catalog.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1057'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1058__game_session_catalog_view_options.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1058'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1059__private_lifecycle_preparation_after_catalog_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1059'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1060__private_catalog_lifecycle_revisions.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1060'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1061__private_fresh_runtime_identity_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1061'
    );
$$;

CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_families JSONB; v_facts JSONB; v_gaps JSONB; v_bytes BYTEA;
BEGIN
    IF p_epoch IS NULL OR p_epoch<=0 THEN
        RAISE EXCEPTION 'Positive Game Session catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM game_session_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM game_session_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Game Session schema family' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,'component',component_name,
        'role',family_role,'stateClass',state_class,'freshDisposition',fresh_disposition,
        'retentionDisposition',retention_disposition,'ownerConsequence',owner_consequence)
        ORDER BY family_id COLLATE "C") INTO v_families FROM game_session_runtime_family_classification;
    SELECT jsonb_agg(jsonb_build_object('id',gap_id,'disposition',disposition,'ownerConsequence',owner_consequence)
        ORDER BY gap_id COLLATE "C") INTO v_gaps FROM game_session_runtime_catalog_non_sql_gap;

    -- Definitions only, not rows, OIDs, sequence positions, statistics or installation timestamps.
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
        SELECT 'VIEW',c.relname::TEXT,jsonb_build_array(pg_get_viewdef(c.oid,true),c.reloptions)::TEXT
        FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1057' AND version IS DISTINCT FROM '1058'
            AND version IS DISTINCT FROM '1059' AND version IS DISTINCT FROM '1060'
            AND version IS DISTINCT FROM '1061'
        UNION ALL
        -- The five installer rows are independently required as exact successful SQL scripts.
        -- Stable markers bind their installation without inventing future Flyway checksums.
        SELECT 'MIGRATION','catalog-installation:1057','V1057__game_session_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1058','V1058__game_session_catalog_view_options.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1059','V1059__private_lifecycle_preparation_after_catalog_allocation.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1060','V1060__private_catalog_lifecycle_revisions.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1061','V1061__private_fresh_runtime_identity_allocation.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;
    IF v_families IS NULL OR v_facts IS NULL OR v_gaps IS NULL
        OR jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384
        OR jsonb_array_length(v_gaps)>32 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    v_bytes := convert_to(jsonb_build_object('schema','game-session-runtime-family-catalog/v1',
        'owner','game-session-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts,'nonSqlGaps',v_gaps)::TEXT,'UTF8');
    IF octet_length(v_bytes)>2097152 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds byte bound' USING ERRCODE='23514';
    END IF;
    RETURN v_bytes;
END;
$$;

-- Seal epoch 5 after all provenance guards and the complete retained census are installed.
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 5,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT gs_runtime_family_catalog_bytes(5) AS bytes) seal;
-- [jooq ignore stop]
