-- Private owner-local content seal; no publication ingress or readiness authority.
-- Existing authored rows are retained; no historical seal or base is synthesized.
CREATE TABLE script_patch_publication_seals (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    script_patch_version VARCHAR(128) NOT NULL CHECK (length(trim(script_patch_version)) > 0),
    is_sealed BOOLEAN NOT NULL DEFAULT FALSE,
    base_version_id BIGINT,
    manifest_schema_version INTEGER,
    content_digest_schema_version INTEGER,
    content_digest VARCHAR(64),
    snapshot_json TEXT,
    sealed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, script_patch_version),
    CHECK ((NOT is_sealed AND base_version_id IS NULL AND manifest_schema_version IS NULL
        AND content_digest_schema_version IS NULL AND content_digest IS NULL
        AND snapshot_json IS NULL AND sealed_at IS NULL)
      OR (is_sealed AND base_version_id IS NOT NULL AND base_version_id > 0
        AND manifest_schema_version IS NOT NULL AND manifest_schema_version = 1
        AND content_digest_schema_version IS NOT NULL AND content_digest_schema_version = 5 AND content_digest IS NOT NULL
        AND snapshot_json IS NOT NULL AND sealed_at IS NOT NULL))
);

-- [jooq ignore start]
ALTER TABLE script_patch_publication_seals ADD CONSTRAINT ck_script_patch_seal_complete
    CHECK (NOT is_sealed OR (content_digest ~ '^[0-9a-f]{64}$'
        AND octet_length(snapshot_json) BETWEEN 2 AND 16777216
        AND jsonb_typeof(snapshot_json::jsonb) = 'object'));

CREATE FUNCTION script_patch_seal_immutable() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}", pg_catalog AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NOT NEW.is_sealed THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND NOT OLD.is_sealed AND NEW.is_sealed
       AND NEW.tenant_id = OLD.tenant_id
       AND NEW.script_patch_version = OLD.script_patch_version THEN
        IF NOT EXISTS (SELECT 1 FROM script_patch_base_bindings
            WHERE tenant_id=NEW.tenant_id::TEXT AND script_patch_version=NEW.script_patch_version
            AND base_version_id=NEW.base_version_id) THEN
            RAISE EXCEPTION 'Script patch seal requires exact retained base' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Immutable script patch seal cannot be replaced or removed' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER script_patch_seal_retained BEFORE INSERT OR UPDATE OR DELETE
    ON script_patch_publication_seals FOR EACH ROW EXECUTE FUNCTION script_patch_seal_immutable();
CREATE TRIGGER script_patch_seal_no_truncate BEFORE TRUNCATE
    ON script_patch_publication_seals FOR EACH STATEMENT EXECUTE FUNCTION script_patch_seal_immutable();

-- One row lock is shared by raw SQL writers and the private seal owner. Lock both
-- old/new identities in stable order before permitting an identity-changing update.
CREATE FUNCTION script_patch_authored_write_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE old_row JSONB; new_row JSONB; scope RECORD; sealed BOOLEAN;
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        IF EXISTS (SELECT 1 FROM script_patch_publication_seals WHERE is_sealed) THEN
            RAISE EXCEPTION 'Sealed authored patch rows cannot be truncated' USING ERRCODE='23514';
        END IF;
        RETURN NULL;
    END IF;
    IF TG_OP <> 'INSERT' THEN old_row := to_jsonb(OLD); END IF;
    IF TG_OP <> 'DELETE' THEN new_row := to_jsonb(NEW); END IF;
    FOR scope IN
        SELECT DISTINCT (row_value->>'tenant_id')::BIGINT AS tenant,
            coalesce(row_value->>'script_patch_version',row_value->>'version') AS patch
        FROM (VALUES(old_row),(new_row)) AS images(row_value)
        WHERE row_value IS NOT NULL
        ORDER BY tenant, patch
    LOOP
        INSERT INTO script_patch_publication_seals(tenant_id,script_patch_version)
            VALUES(scope.tenant,scope.patch) ON CONFLICT DO NOTHING;
        SELECT is_sealed INTO sealed FROM script_patch_publication_seals
            WHERE tenant_id=scope.tenant AND script_patch_version=scope.patch FOR UPDATE;
        IF sealed IS DISTINCT FROM FALSE THEN
            RAISE EXCEPTION 'Authored script patch is sealed' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scripts_seal_writer BEFORE INSERT OR UPDATE OR DELETE ON scripts
    FOR EACH ROW EXECUTE FUNCTION script_patch_authored_write_guard();
CREATE TRIGGER script_bindings_seal_writer BEFORE INSERT OR UPDATE OR DELETE ON script_event_bindings
    FOR EACH ROW EXECUTE FUNCTION script_patch_authored_write_guard();
CREATE TRIGGER script_base_seal_writer BEFORE INSERT OR UPDATE OR DELETE ON script_patch_base_bindings
    FOR EACH ROW EXECUTE FUNCTION script_patch_authored_write_guard();
CREATE TRIGGER scripts_seal_no_truncate BEFORE TRUNCATE ON scripts
    FOR EACH STATEMENT EXECUTE FUNCTION script_patch_authored_write_guard();
CREATE TRIGGER script_bindings_seal_no_truncate BEFORE TRUNCATE ON script_event_bindings
    FOR EACH STATEMENT EXECUTE FUNCTION script_patch_authored_write_guard();
CREATE TRIGGER script_base_seal_no_truncate BEFORE TRUNCATE ON script_patch_base_bindings
    FOR EACH STATEMENT EXECUTE FUNCTION script_patch_authored_write_guard();

INSERT INTO automation_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('script_patch_publication_seals','script_patch_publication_seals','','SOURCE','NOT_PAYLOAD','SOURCE_ONLY','IMMUTABLE_RETAINED',
    'Private exact authored content and publication-request binding; not a readiness manifest, runtime initialization, or publication authority. Retain sealed bytes and shared writer locks.');

CREATE OR REPLACE FUNCTION automation_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1004' AND script='V1004__automation_owner_runtime_family_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1005' AND script='V1005__automation_catalog_view_options.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1006' AND script='V1006__private_script_patch_snapshot_seal.sql' AND success);
$$;

CREATE OR REPLACE FUNCTION automation_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_families JSONB; v_facts JSONB; v_gaps JSONB;
BEGIN
    IF p_epoch IS NULL OR p_epoch<=0 THEN
        RAISE EXCEPTION 'Positive Automation catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM automation_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM automation_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Automation schema family' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,'component',component_name,
        'role',family_role,'stateClass',state_class,'freshDisposition',fresh_disposition,
        'retentionDisposition',retention_disposition,'ownerConsequence',owner_consequence)
        ORDER BY family_id COLLATE "C") INTO v_families FROM automation_runtime_family_classification;
    SELECT jsonb_agg(jsonb_build_object('id',gap_id,'disposition',disposition,'ownerConsequence',owner_consequence)
        ORDER BY gap_id COLLATE "C") INTO v_gaps FROM automation_runtime_catalog_non_sql_gap;

    -- Live definitions, never payload rows, OIDs, sequence positions, statistics or installation times.
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
        SELECT 'VIEW',c.relname::TEXT,jsonb_build_array(pg_get_viewdef(c.oid,true),c.reloptions)::TEXT FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1004' AND version IS DISTINCT FROM '1005' AND version IS DISTINCT FROM '1006'
        UNION ALL
        -- Catalog history rows are post-transaction. Distinct stable installation markers bind
        -- those scripts; installed() separately requires both successful exact script identities.
        -- These markers do not fabricate post-transaction Flyway checksums.
        SELECT 'MIGRATION','catalog-installation:1004','V1004__automation_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1005','V1005__automation_catalog_view_options.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1006','V1006__private_script_patch_snapshot_seal.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;
    IF v_families IS NULL OR v_facts IS NULL OR v_gaps IS NULL
        OR jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384
        OR jsonb_array_length(v_gaps)>32 THEN
        RAISE EXCEPTION 'Complete Automation catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    RETURN convert_to(jsonb_build_object('schema','automation-runtime-family-catalog/v1',
        'owner','automation-scripting-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts,'nonSqlGaps',v_gaps)::TEXT,'UTF8');
END;
$$;

-- Replace every affected routine before sealing the next exact owner-local epoch.
INSERT INTO automation_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM automation_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT automation_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
