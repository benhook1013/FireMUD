-- A retained private catalog projection is not a public target conflict for a new
-- preparation/current-selection request. Stable realm selectors and immutable catalog
-- rows remain unchanged; later lifecycle catalog evolution is a separate owner boundary.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION game_session_initial_playtest_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    source game_session_canonical_realm_catalog%ROWTYPE;
    intake game_session_authored_world_source_intake%ROWTYPE;
    carrier BYTEA;
    payload_bytes BYTEA;
    payload TEXT;
    policy JSONB;
    selected_policy_count INTEGER;
    selected_policy_json TEXT;
    selected_policy JSONB;
    marker TEXT := 'game-design-published-realm-policy-carrier/v1';
    fields TEXT[];
    item TEXT;
    framed BYTEA := ''::BYTEA;
    fraction TEXT;
BEGIN
    IF NEW.fork_expiry_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3}|\.[0-9]{6}|\.[0-9]{9})?Z$'
        OR extract(epoch FROM regexp_replace(NEW.fork_expiry_text, '\.[0-9]+Z$', 'Z')::TIMESTAMPTZ) <> NEW.fork_expiry_second THEN
        RAISE EXCEPTION 'Exact fork expiry text differs from persisted seconds' USING ERRCODE = '23514';
    END IF;
    fraction := substring(NEW.fork_expiry_text FROM '\.([0-9]+)Z$');
    IF coalesce(rpad(fraction, 9, '0')::INTEGER, 0) <> NEW.fork_expiry_nano THEN
        RAISE EXCEPTION 'Exact fork expiry text differs from persisted nanoseconds' USING ERRCODE = '23514';
    END IF;
    fields := ARRAY['game-session-initial-fresh-playtest-request/v1', NEW.request_id::TEXT,
        NEW.target_namespace, NEW.canonical_tenant_id::TEXT, NEW.world_slug, NEW.realm_slug,
        NEW.source_catalog_realm_id::TEXT, NEW.source_catalog_request_id::TEXT, NEW.source_catalog_revision::TEXT,
        NEW.source_intake_operation_id::TEXT, NEW.canonical_version_id::TEXT, NEW.published_release_bundle_ref,
        NEW.published_release_bundle_digest, NEW.initialization_mode, NEW.fork_expiry_text];
    FOREACH item IN ARRAY fields LOOP
        -- int4send is the four-byte network-order length used by the shared Java frame.
        framed := framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF framed <> NEW.request_preimage THEN
        RAISE EXCEPTION 'Initial playtest request bytes differ from exact stored intent' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT source FROM game_session_canonical_realm_catalog
        WHERE target_namespace = NEW.target_namespace AND realm_id = NEW.source_catalog_realm_id FOR SHARE;
    SELECT * INTO STRICT intake FROM game_session_authored_world_source_intake
        WHERE operation_id = NEW.source_intake_operation_id FOR SHARE;
    IF NOT source.public_production OR NOT source.visible
        OR source.complete_policy_set_evidence IS NULL OR source.fixture_policy_request_bytes IS NOT NULL
        OR source.canonical_tenant_id <> NEW.canonical_tenant_id OR source.world_slug <> NEW.world_slug
        OR source.realm_slug = NEW.realm_slug
        OR source.creation_request_id <> NEW.source_catalog_request_id
        OR source.catalog_revision <> NEW.source_catalog_revision
        OR source.receipt_digest <> NEW.source_catalog_receipt_digest
        OR source.source_intake_operation_id <> NEW.source_intake_operation_id
        OR source.source_intake_receipt_digest <> NEW.source_intake_receipt_digest
        OR source.source_operation_id <> NEW.source_operation_id
        OR source.source_evidence_digest <> NEW.source_evidence_digest
        OR intake.receipt_digest <> NEW.source_intake_receipt_digest
        OR intake.target_namespace <> NEW.target_namespace OR intake.canonical_tenant_id <> NEW.canonical_tenant_id
        OR intake.world_slug <> NEW.world_slug OR intake.source_operation_id <> NEW.source_operation_id
        OR intake.source_evidence_digest <> NEW.source_evidence_digest
        OR NEW.source_policy_carrier_digest <> 'sha256:' || encode(sha256(source.complete_policy_set_evidence), 'hex')
        OR EXISTS (SELECT 1 FROM game_session_canonical_realm_catalog target
            WHERE target.target_namespace = NEW.target_namespace AND target.canonical_tenant_id = NEW.canonical_tenant_id
                AND target.world_slug = NEW.world_slug AND target.realm_slug = NEW.realm_slug
                AND target.public_production)
        OR EXISTS (SELECT 1 FROM gameplay_tenant_shared_playable_state_namespace namespace
            WHERE namespace.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM game_session_canonical_realm_catalog catalog
            WHERE catalog.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_admission_pointer pointer
            WHERE pointer.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_published_tenant_shared_playable_state_namespace namespace
            WHERE namespace.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_published_realm_catalog_entry entry
            WHERE entry.playable_state_namespace_id = NEW.playable_state_namespace_id) THEN
        RAISE EXCEPTION 'Initial playtest requires exact retained canonical public source/build evidence and a fresh private target' USING ERRCODE = '23514';
    END IF;
    -- Read only the exact retained shared carrier; Java additionally verifies its closed
    -- publication operation/capture/terminal contracts before reaching this guarded insert.
    carrier := source.complete_policy_set_evidence;
    IF octet_length(carrier) < 8 + octet_length(convert_to(marker, 'UTF8')) THEN
        RAISE EXCEPTION 'Retained build source carrier framing is invalid' USING ERRCODE = '23514';
    END IF;
    -- The carrier is exactly two shared binary frames: schema marker, then canonical JSON.
    -- Derive the actual second-frame bytes and compare both complete length headers exactly.
    payload_bytes := substring(carrier FROM 9 + octet_length(convert_to(marker, 'UTF8')));
    IF carrier <> (int4send(octet_length(convert_to(marker, 'UTF8'))) || convert_to(marker, 'UTF8')
        || int4send(octet_length(payload_bytes)) || payload_bytes) THEN
        RAISE EXCEPTION 'Retained build source carrier framing is invalid' USING ERRCODE = '23514';
    END IF;
    payload := convert_from(payload_bytes, 'UTF8');
    policy := payload::JSONB;
    IF policy->>'schema' IS DISTINCT FROM marker
        OR policy->'target'->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR policy->'target'->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR policy->'target'->>'sourceGameRowId' IS DISTINCT FROM source.source_game_row_id::TEXT
        OR policy->'target'->>'sourceGameTenantKey' IS DISTINCT FROM source.source_game_tenant_key
        OR policy->'target'->>'sourceProvenanceKind' IS DISTINCT FROM source.source_provenance_kind
        OR policy->>'publishedReleaseBundleRef' IS DISTINCT FROM NEW.published_release_bundle_ref
        OR policy->>'publishedReleaseBundleDigest' IS DISTINCT FROM NEW.published_release_bundle_digest
        OR policy->>'policySetDigest' IS DISTINCT FROM NEW.source_policy_set_digest THEN
        RAISE EXCEPTION 'Initial playtest build differs from exact retained published source' USING ERRCODE = '23514';
    END IF;
    SELECT count(*), max(policy_item.value->'source'->>'canonicalPolicyJson')
      INTO selected_policy_count, selected_policy_json
      FROM jsonb_array_elements(policy->'policies') AS policy_item(value)
     WHERE policy_item.value->>'policyId' = NEW.target_policy_id::TEXT
       AND policy_item.value->>'policyDigest' = NEW.target_policy_digest
       AND policy_item.value->'source'->>'commitId' = NEW.target_policy_source_commit_id::TEXT
       AND policy_item.value->'source'->>'revisionId' = NEW.target_policy_source_revision_id::TEXT
       AND policy_item.value->'source'->>'logicalRevisionId' = NEW.target_policy_logical_revision_id;
    IF selected_policy_count <> 1 OR selected_policy_json IS NULL
       OR convert_to(selected_policy_json, 'UTF8') IS DISTINCT FROM NEW.target_policy_canonical_json THEN
        RAISE EXCEPTION 'Selected private target policy differs from the exact retained published policy set' USING ERRCODE = '23514';
    END IF;
    selected_policy := selected_policy_json::JSONB;
    IF selected_policy->>'schemaVersion' IS DISTINCT FROM NEW.target_policy_schema_version::TEXT
       OR selected_policy->>'worldSlug' IS DISTINCT FROM NEW.target_policy_world_slug
       OR selected_policy->>'realmSlug' IS DISTINCT FROM NEW.target_policy_realm_slug
       OR selected_policy->>'visible' IS DISTINCT FROM 'true'
       OR selected_policy->>'worldDisplayName' IS DISTINCT FROM NEW.target_policy_world_display_name
       OR NEW.target_policy_world_display_name IS DISTINCT FROM intake.world_display_name
       OR selected_policy->>'publicProduction' IS DISTINCT FROM 'false'
       OR selected_policy->>'stateScope' IS DISTINCT FROM NEW.target_policy_state_scope
       OR selected_policy->>'realmDisplayName' IS DISTINCT FROM NEW.target_policy_realm_display_name
       OR selected_policy->>'entryPolicy' IS DISTINCT FROM NEW.target_policy_entry_policy THEN
        RAISE EXCEPTION 'Selected private target policy semantics differ from the exact retained policy bytes' USING ERRCODE = '23514';
    END IF;
    IF NEW.fork_expiry_second + NEW.fork_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Initial playtest fork expiry must be future' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

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
            AND version IS DISTINCT FROM '1059'
        UNION ALL
        -- Each installing migration's own row is written after its transaction. Bind distinct
        -- stable installation markers, not invented checksums. installed() separately requires
        -- all three successful exact SQL scripts. All other prior/later rows remain bound above.
        SELECT 'MIGRATION','catalog-installation:1057','V1057__game_session_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1058','V1058__game_session_catalog_view_options.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1059','V1059__private_lifecycle_preparation_after_catalog_allocation.sql'
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

-- Seal only after every routine replacement; old epochs and snapshots stay byte-for-byte
-- retained and old requests conflict with this explicitly installed next owner revision.
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes
    FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS epoch
        FROM game_session_runtime_family_catalog_epoch) next_epoch
) seal;
-- [jooq ignore stop]
