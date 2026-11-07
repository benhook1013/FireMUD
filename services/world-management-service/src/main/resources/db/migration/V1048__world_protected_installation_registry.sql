-- Complete protected installation registration; no external authority or runtime wiring is enabled.
CREATE TABLE world_runtime_installation_registration (
    revision BIGSERIAL PRIMARY KEY,
    artifact_id UUID NOT NULL UNIQUE CHECK (artifact_id <> '00000000-0000-0000-0000-000000000000'),
    installation_id UUID NOT NULL UNIQUE CHECK (installation_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL,
    artifact_bytes BYTEA NOT NULL CHECK (octet_length(artifact_bytes) BETWEEN 1 AND 4194304),
    artifact_digest VARCHAR(71) NOT NULL CHECK (artifact_digest = 'sha256:' || encode(sha256(artifact_bytes),'hex')),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (revision, artifact_digest, target_namespace)
);

CREATE TABLE world_runtime_installation_current (
    target_namespace VARCHAR(63) PRIMARY KEY,
    revision BIGINT NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    FOREIGN KEY (revision, artifact_digest, target_namespace)
        REFERENCES world_runtime_installation_registration(revision, artifact_digest, target_namespace)
);

CREATE TABLE world_runtime_required_owner_snapshot (
    canonical_game_instance_id UUID PRIMARY KEY CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL,
    registry_revision BIGINT NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    required_owners JSONB NOT NULL,
    frozen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (registry_revision, artifact_digest, target_namespace)
        REFERENCES world_runtime_installation_registration(revision, artifact_digest, target_namespace)
);

INSERT INTO world_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition)
VALUES
    ('world_runtime_installation_registration','world_runtime_installation_registration','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY'),
    ('world_runtime_installation_current','world_runtime_installation_current','CONTROL','NOT_PAYLOAD','CONTROL_ONLY'),
    ('world_runtime_required_owner_snapshot','world_runtime_required_owner_snapshot','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY');

-- PostgreSQL JSON array predicate is not executable in the jOOQ H2 DDL interpreter.
-- [jooq ignore start]
ALTER TABLE world_runtime_required_owner_snapshot ADD CONSTRAINT world_required_owner_array
    CHECK (jsonb_typeof(required_owners)='array' AND jsonb_array_length(required_owners)>0);
CREATE FUNCTION world_runtime_registry_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Runtime installation and required-owner history is immutable' USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER world_runtime_installation_immutable BEFORE UPDATE OR DELETE ON world_runtime_installation_registration
    FOR EACH ROW EXECUTE FUNCTION world_runtime_registry_immutable();
CREATE TRIGGER world_runtime_installation_no_truncate BEFORE TRUNCATE ON world_runtime_installation_registration
    FOR EACH STATEMENT EXECUTE FUNCTION world_runtime_registry_immutable();
CREATE TRIGGER world_runtime_required_owners_immutable BEFORE UPDATE OR DELETE ON world_runtime_required_owner_snapshot
    FOR EACH ROW EXECUTE FUNCTION world_runtime_registry_immutable();
CREATE TRIGGER world_runtime_required_owners_no_truncate BEFORE TRUNCATE ON world_runtime_required_owner_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION world_runtime_registry_immutable();

-- SQL derives the frozen set from exact retained bytes, never a caller-supplied owner subset.
CREATE FUNCTION world_runtime_required_owners(p_bytes BYTEA) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    artifact JSONB := convert_from(p_bytes,'UTF8')::JSONB;
    owners JSONB;
BEGIN
    IF artifact->>'schema' IS DISTINCT FROM 'firemud-runtime-installation-inventory/v1'
        OR jsonb_typeof(artifact->'members') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Complete runtime installation artifact required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(artifact->'members') m
        CROSS JOIN LATERAL jsonb_array_elements(m->'families') f
        WHERE f->>'replacementClass' IS NULL OR f->>'replacementClass' NOT IN ('S1','S2','S3')
            OR jsonb_typeof(f->'cleanupContract') IS DISTINCT FROM 'object'
            OR EXISTS (SELECT 1 FROM (VALUES ('operation'),('requestIdentity'),('acknowledgement'),('retention')) k(name)
                WHERE coalesce(length(f->'cleanupContract'->>k.name),0)=0)) THEN
        RAISE EXCEPTION 'Unresolved runtime family or cleanup contract' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('ownerId',m->>'ownerId','catalogSources',m->'catalogSources',
        'families',m->'families','workloadApiVersion',m->>'workloadApiVersion',
        'workloadKind',m->>'workloadKind','workloadName',m->>'workloadName',
        'containerKind',m->>'containerKind','containerName',m->>'containerName')
        ORDER BY m->>'ownerId' COLLATE "C",m->>'workloadApiVersion' COLLATE "C",
            m->>'workloadKind' COLLATE "C",m->>'workloadName' COLLATE "C",
            m->>'containerKind' COLLATE "C",m->>'containerName' COLLATE "C")
    INTO owners FROM jsonb_array_elements(artifact->'members') m WHERE m->>'ownership'='RUNTIME_OWNER';
    IF owners IS NULL OR jsonb_array_length(owners)=0 THEN
        RAISE EXCEPTION 'Complete runtime required-owner set is unavailable' USING ERRCODE='23514';
    END IF;
    RETURN owners;
END;
$$;

CREATE FUNCTION world_runtime_registration_binding_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}",pg_catalog AS $$
DECLARE a JSONB := convert_from(NEW.artifact_bytes,'UTF8')::JSONB;
BEGIN
    IF (a->>'artifactId')::UUID IS DISTINCT FROM NEW.artifact_id
        OR (a->>'installationId')::UUID IS DISTINCT FROM NEW.installation_id
        OR a->>'namespace' IS DISTINCT FROM NEW.target_namespace THEN
        RAISE EXCEPTION 'Runtime installation identity does not match retained exact bytes' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_runtime_registration_binding BEFORE INSERT ON world_runtime_installation_registration
    FOR EACH ROW EXECUTE FUNCTION world_runtime_registration_binding_guard();

CREATE FUNCTION world_runtime_current_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}",pg_catalog AS $$
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'Current runtime installation cannot be deleted' USING ERRCODE='55000';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.target_namespace IS DISTINCT FROM OLD.target_namespace OR NEW.revision<=OLD.revision) THEN
        RAISE EXCEPTION 'Current runtime installation must advance monotonically' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_runtime_current_monotonic BEFORE INSERT OR UPDATE OR DELETE ON world_runtime_installation_current
    FOR EACH ROW EXECUTE FUNCTION world_runtime_current_guard();
CREATE TRIGGER world_runtime_current_no_truncate BEFORE TRUNCATE ON world_runtime_installation_current
    FOR EACH STATEMENT EXECUTE FUNCTION world_runtime_current_guard();

CREATE FUNCTION world_runtime_snapshot_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}",pg_catalog AS $$
DECLARE r world_runtime_installation_registration%ROWTYPE;
BEGIN
    -- Lock the current pointer through the launch transaction; a concurrent change waits.
    SELECT registration.* INTO STRICT r FROM world_runtime_installation_current c
        JOIN world_runtime_installation_registration registration ON registration.revision=c.revision
        WHERE c.target_namespace=NEW.target_namespace FOR SHARE OF c;
    IF r.revision IS DISTINCT FROM NEW.registry_revision OR r.artifact_digest IS DISTINCT FROM NEW.artifact_digest
        OR world_runtime_required_owners(r.artifact_bytes) IS DISTINCT FROM NEW.required_owners THEN
        RAISE EXCEPTION 'Frozen owners must match the complete current installation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_runtime_snapshot_exact BEFORE INSERT ON world_runtime_required_owner_snapshot
    FOR EACH ROW EXECUTE FUNCTION world_runtime_snapshot_guard();

CREATE FUNCTION world_require_registered_launch_owners() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}",pg_catalog AS $$
DECLARE snapshot world_runtime_required_owner_snapshot%ROWTYPE;
BEGIN
    IF NEW.canonical_game_instance_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND OLD.canonical_game_instance_id IS NOT DISTINCT FROM NEW.canonical_game_instance_id
        AND OLD.canonical_target_namespace IS NOT DISTINCT FROM NEW.canonical_target_namespace
        AND NOT (NEW.status='ACTIVE' AND OLD.status IS DISTINCT FROM NEW.status) THEN
        RETURN NEW;
    END IF;
    SELECT s.* INTO snapshot FROM world_runtime_required_owner_snapshot s
        JOIN world_runtime_installation_current c ON c.target_namespace=s.target_namespace
            AND c.revision=s.registry_revision AND c.artifact_digest=s.artifact_digest
        JOIN world_runtime_installation_registration r ON r.revision=c.revision
        WHERE s.canonical_game_instance_id=NEW.canonical_game_instance_id
            AND s.target_namespace=NEW.canonical_target_namespace
            AND s.required_owners=world_runtime_required_owners(r.artifact_bytes)
        FOR SHARE OF c;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Canonical launch requires complete current frozen runtime owners' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER world_instance_registered_launch_owners BEFORE INSERT OR UPDATE ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_require_registered_launch_owners();

CREATE OR REPLACE FUNCTION world_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1046' AND script='V1046__world_owner_runtime_family_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1047' AND script='V1047__world_catalog_snapshot_exact_frames.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1048' AND script='V1048__world_protected_installation_registry.sql' AND success);
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
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1046' AND version IS DISTINCT FROM '1047' AND version IS DISTINCT FROM '1048'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1046','V1046__world_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1047','V1047__world_catalog_snapshot_exact_frames.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1048','V1048__world_protected_installation_registry.sql'
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
