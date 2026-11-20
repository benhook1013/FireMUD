-- Canonical run-owned fixture activation. The World lifecycle owner retains one immutable activation
-- row per fixture instance and, in the same transaction, moves the World row from its original
-- PREPARING epoch to ACTIVE. The storage guard admits only that exact CAS with its matching row;
-- every other fixture row mutation stays refused. Human activation guards are unchanged.
-- [jooq ignore start]
CREATE TABLE world_run_owned_fixture_activation (
    world_instance_id BIGINT PRIMARY KEY REFERENCES world_instance (id),
    canonical_game_instance_id UUID NOT NULL UNIQUE,
    fixture_binding_sha256 CHAR(64) NOT NULL CHECK (fixture_binding_sha256 ~ '^[0-9a-f]{64}$'),
    expected_lifecycle_epoch BIGINT NOT NULL CHECK (expected_lifecycle_epoch = 1),
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version >= 0),
    result_lifecycle_epoch BIGINT NOT NULL,
    result_row_version BIGINT NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (result_lifecycle_epoch = expected_lifecycle_epoch + 1),
    CHECK (result_row_version = expected_row_version + 1)
);

INSERT INTO world_runtime_family_classification(family_id,relation_name,family_role,state_class,fresh_disposition)
VALUES ('world_run_owned_fixture_activation','world_run_owned_fixture_activation','AUDIT','NOT_PAYLOAD','AUDIT_ONLY');

CREATE FUNCTION world_run_owned_fixture_activation_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'Run-owned fixture activation rows are immutable'
        USING ERRCODE = '23514';
END;
$$;
REVOKE ALL ON FUNCTION world_run_owned_fixture_activation_immutable() FROM PUBLIC;
CREATE TRIGGER trg_world_run_owned_fixture_activation_immutable
    BEFORE UPDATE OR DELETE ON world_run_owned_fixture_activation
    FOR EACH ROW EXECUTE FUNCTION world_run_owned_fixture_activation_immutable();
CREATE TRIGGER trg_world_run_owned_fixture_activation_no_truncate
    BEFORE TRUNCATE ON world_run_owned_fixture_activation
    FOR EACH STATEMENT EXECUTE FUNCTION world_run_owned_fixture_activation_immutable();

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        RAISE EXCEPTION 'Run-owned fixture World row mutations are disabled'
            USING ERRCODE = '23514';
    END IF;$anchor$;
    replacement TEXT := $replacement$IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        IF OLD.status = 'PREPARING'
            AND NEW.status = 'ACTIVE'
            AND OLD.lifecycle_epoch = OLD.run_owned_fixture_preparing_lifecycle_epoch
            AND NEW.lifecycle_epoch = OLD.lifecycle_epoch + 1
            AND NEW.row_version = OLD.row_version + 1
            AND (to_jsonb(OLD) - ARRAY['status', 'lifecycle_epoch', 'row_version'])
                = (to_jsonb(NEW) - ARRAY['status', 'lifecycle_epoch', 'row_version'])
            AND EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation a
                WHERE a.world_instance_id = OLD.id
                    AND a.canonical_game_instance_id = OLD.canonical_game_instance_id
                    AND a.fixture_binding_sha256 = OLD.run_owned_fixture_binding_sha256
                    AND a.expected_lifecycle_epoch = OLD.lifecycle_epoch
                    AND a.expected_row_version = OLD.row_version
                    AND a.result_lifecycle_epoch = NEW.lifecycle_epoch
                    AND a.result_row_version = NEW.row_version) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Run-owned fixture World row mutations are disabled'
            USING ERRCODE = '23514';
    END IF;$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_activation_transition()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one run-owned fixture World row-mutation anchor';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;

-- The reserved-tenant execution guard admits the same exact fixture CAS when its activation row
-- was inserted by the current transaction.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$        RAISE EXCEPTION
            'World tenant key is reserved for a canonical authored source; no exact transaction execution manifest'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_claim_legacy_numeric_tenant_key()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one reserved-tenant execution denial guard';
    END IF;
    original := replace(original,anchor,$replacement$        IF TG_TABLE_SCHEMA = '${serviceSchema}' AND TG_TABLE_NAME = 'world_instance'
            AND TG_OP = 'UPDATE'
            AND old_row->>'canonical_authority_kind' = 'RUN_OWNED_FIXTURE'
            AND old_row->>'status' = 'PREPARING' AND new_row->>'status' = 'ACTIVE'
            AND (old_row - ARRAY['status', 'lifecycle_epoch', 'row_version'])
                = (new_row - ARRAY['status', 'lifecycle_epoch', 'row_version'])
            AND EXISTS (SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation a
                WHERE a.world_instance_id = (old_row->>'id')::BIGINT
                    AND a.canonical_game_instance_id::TEXT = old_row->>'canonical_game_instance_id'
                    AND a.expected_lifecycle_epoch = (old_row->>'lifecycle_epoch')::BIGINT
                    AND a.expected_row_version = (old_row->>'row_version')::BIGINT
                    AND a.result_lifecycle_epoch = (new_row->>'lifecycle_epoch')::BIGINT
                    AND a.result_row_version = (new_row->>'row_version')::BIGINT
                    AND a.xmin::TEXT = (txid_current() % 4294967296)::TEXT) THEN
            RETURN NEW;
        END IF;
$replacement$ || anchor);
    EXECUTE original;
END;
$migration$;

-- Run-owned fixture associations may acquire a typed initial-admission hold through the World
-- owner (ACTIVE fixture row, exact association target). The V1040 refusal is removed; Human and
-- legacy acquisitions keep their existing validation.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF TG_OP = 'INSERT' AND EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_instance wi ON wi.id = a.world_instance_id
        WHERE a.schema_version = 3
            AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND (a.canonical_game_instance_id = NEW.canonical_game_instance_id
                OR (wi.tenant_id = NEW.tenant_id
                    AND wi.game_instance_id = NEW.game_instance_id
                    AND wi.version_id = NEW.version_id))) THEN
        RAISE EXCEPTION 'Run-owned fixture initial-admission holds are disabled'
            USING ERRCODE = '23514';
    END IF;
$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_initial_admission_hold()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one run-owned fixture hold refusal';
    END IF;
    EXECUTE replace(original, anchor, '');
END;
$migration$;

-- V1070 joins the catalog installation set: its own history row is sealed as an installation
-- marker, exactly as V1046-V1050, so the post-commit history row cannot drift the seal.
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
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1070'
        AND script='V1070__world_run_owned_fixture_activation.sql' AND success);
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
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1046' AND version IS DISTINCT FROM '1047' AND version IS DISTINCT FROM '1048' AND version IS DISTINCT FROM '1049' AND version IS DISTINCT FROM '1050' AND version IS DISTINCT FROM '1070'
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
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1070','V1070__world_run_owned_fixture_activation.sql'
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

-- The new owner table and guard change advance the sealed World schema catalog epoch.
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
