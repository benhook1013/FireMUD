-- Durable pending source-currentness protection only. No terminal settlement or runtime authority.
CREATE TABLE game_session_private_preparation_protection (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    hold_id UUID NOT NULL UNIQUE CHECK (hold_id <> '00000000-0000-0000-0000-000000000000'),
    fence BIGSERIAL NOT NULL UNIQUE CHECK (fence > 0),
    source_read_request_id UUID NOT NULL UNIQUE,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    realm_id UUID NOT NULL REFERENCES game_session_canonical_realm_catalog(realm_id),
    preparation_request_id UUID NOT NULL,
    playtest_lifecycle_id UUID NOT NULL,
    runtime_allocation_request_id UUID NOT NULL UNIQUE REFERENCES game_session_private_fresh_runtime_allocations(request_id),
    game_instance_id UUID NOT NULL UNIQUE REFERENCES game_session_private_fresh_runtime_allocations(game_instance_id),
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision > 0),
    catalog_request_id UUID NOT NULL,
    selection_head_revision BIGINT NOT NULL CHECK (selection_head_revision > 0),
    selection_request_id UUID NOT NULL REFERENCES game_session_private_playtest_selections(request_id),
    source_bytes BYTEA NOT NULL CHECK (octet_length(source_bytes) BETWEEN 1 AND 65536),
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest='sha256:' || encode(sha256(source_bytes),'hex')),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 65536),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (preparation_request_id,playtest_lifecycle_id)
        REFERENCES game_session_initial_playtest_preparations(request_id,playtest_lifecycle_id)
);

-- [jooq ignore start]
CREATE FUNCTION gs_private_preparation_protection_require_current(p game_session_private_preparation_protection)
RETURNS VOID LANGUAGE plpgsql SET search_path = "${serviceSchema}",pg_catalog AS $$
DECLARE a game_session_private_fresh_runtime_allocations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT a FROM game_session_private_fresh_runtime_allocations
        WHERE request_id=p.runtime_allocation_request_id FOR SHARE;
    IF a.game_instance_id IS DISTINCT FROM p.game_instance_id
        OR a.target_namespace IS DISTINCT FROM p.target_namespace
        OR a.canonical_tenant_id IS DISTINCT FROM p.canonical_tenant_id
        OR a.world_slug IS DISTINCT FROM p.world_slug OR a.realm_slug IS DISTINCT FROM p.realm_slug
        OR a.realm_id IS DISTINCT FROM p.realm_id
        OR a.preparation_request_id IS DISTINCT FROM p.preparation_request_id
        OR a.playtest_lifecycle_id IS DISTINCT FROM p.playtest_lifecycle_id
        OR a.catalog_revision IS DISTINCT FROM p.catalog_revision
        OR a.catalog_request_id IS DISTINCT FROM p.catalog_request_id
        OR a.selection_head_revision IS DISTINCT FROM p.selection_head_revision
        OR a.selection_request_id IS DISTINCT FROM p.selection_request_id THEN
        RAISE EXCEPTION 'Private protection differs from its exact retained runtime source' USING ERRCODE='23514';
    END IF;
    -- Locks both mutable source heads and rechecks all immutable source/expiry bindings.
    PERFORM gs_private_fresh_runtime_require_current(a);
END;
$$;
CREATE FUNCTION gs_private_preparation_protection_insert_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}",pg_catalog AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'serializable'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Private source protection requires writable SERIALIZABLE acquisition' USING ERRCODE='23514';
    END IF;
    PERFORM gs_private_preparation_protection_require_current(NEW);
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_private_preparation_protection_insert BEFORE INSERT
ON game_session_private_preparation_protection FOR EACH ROW
EXECUTE FUNCTION gs_private_preparation_protection_insert_guard();
CREATE FUNCTION gs_private_preparation_protection_commit_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}",pg_catalog AS $$
BEGIN
    PERFORM gs_private_preparation_protection_require_current(NEW);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_private_preparation_protection_commit AFTER INSERT
ON game_session_private_preparation_protection DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION gs_private_preparation_protection_commit_guard();

CREATE FUNCTION gs_private_preparation_protection_retained() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Pending private source protection cannot be changed or released without World terminal proof'
        USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER gs_private_preparation_protection_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON game_session_private_preparation_protection FOR EACH STATEMENT
EXECUTE FUNCTION gs_private_preparation_protection_retained();

-- Expiry is not settlement. There is deliberately no elapsed-time exception or release path.
CREATE FUNCTION gs_private_catalog_pending_protection_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}",pg_catalog AS $$
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','serializable') THEN
        RAISE EXCEPTION 'Private head writers require READ COMMITTED or SERIALIZABLE protection semantics'
            USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM game_session_private_preparation_protection WHERE realm_id=OLD.realm_id) THEN
        RAISE EXCEPTION 'Current private catalog is protected by an unresolved World preparation'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_private_catalog_pending_protection BEFORE UPDATE
ON game_session_current_private_catalog_revisions FOR EACH ROW
EXECUTE FUNCTION gs_private_catalog_pending_protection_guard();
CREATE FUNCTION gs_private_selection_pending_protection_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}",pg_catalog AS $$
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','serializable') THEN
        RAISE EXCEPTION 'Private head writers require READ COMMITTED or SERIALIZABLE protection semantics'
            USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM game_session_private_preparation_protection
        WHERE target_namespace=OLD.target_namespace AND canonical_tenant_id=OLD.canonical_tenant_id
            AND world_slug=OLD.world_slug AND realm_slug=OLD.realm_slug) THEN
        RAISE EXCEPTION 'Current private selection is protected by an unresolved World preparation'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_private_selection_pending_protection BEFORE UPDATE
ON game_session_current_private_playtests FOR EACH ROW
EXECUTE FUNCTION gs_private_selection_pending_protection_guard();
-- [jooq ignore stop]

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_private_preparation_protection','game_session_private_preparation_protection',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','IMMUTABLE_RETAINED',
    'Pending exact private source protection blocks catalog and selection supersession; no release without World terminal proof, no actor authority, expiry extension, materialization or admission.');

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
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1062__private_preparation_source_protection.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1062'
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
            AND version IS DISTINCT FROM '1061' AND version IS DISTINCT FROM '1062'
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
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1062','V1062__private_preparation_source_protection.sql'
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

-- Seal epoch 6 after all provenance guards and the complete retained census are installed.
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 6,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT gs_runtime_family_catalog_bytes(6) AS bytes) seal;
-- [jooq ignore stop]
