-- Preserve V1049 and all retained owner rows, history, epochs and snapshots.
-- Qualify the complete private identity before joining its exact association.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_private_fresh_require_terminal() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}" AS $$
BEGIN
    IF NEW.private_fresh_operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM world_private_fresh_terminal t
        JOIN world_private_fresh_operation o ON o.operation_id=t.operation_id
            AND o.canonical_game_instance_id=t.canonical_game_instance_id
        JOIN world_private_instance_association a ON a.operation_id=o.operation_id
            AND a.canonical_game_instance_id=o.canonical_game_instance_id
        WHERE t.operation_id=NEW.private_fresh_operation_id AND t.outcome='APPLIED'
            AND t.world_instance_id=NEW.id AND a.world_instance_id=NEW.id
            AND t.private_game_instance_key=NEW.game_instance_id AND a.private_game_instance_key=NEW.game_instance_id
            AND t.input_digest=o.input_digest) THEN
        RAISE EXCEPTION 'Private World materialization cannot commit without exact APPLIED terminal' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;

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
        AND script='V1050__world_private_fresh_settlement_binding.sql' AND success);
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
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1046' AND version IS DISTINCT FROM '1047' AND version IS DISTINCT FROM '1048' AND version IS DISTINCT FROM '1049' AND version IS DISTINCT FROM '1050'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1046','V1046__world_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1047','V1047__world_catalog_snapshot_exact_frames.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1048','V1048__world_protected_installation_registry.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1049','V1049__world_private_fresh_operation.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1050','V1050__world_private_fresh_settlement_binding.sql'
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
