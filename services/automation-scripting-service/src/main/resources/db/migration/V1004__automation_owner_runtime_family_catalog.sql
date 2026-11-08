-- Additive source prerequisite only. No gameplay/source rows, sequences or prior history change.
CREATE TABLE automation_runtime_family_classification (
    family_id VARCHAR(127) PRIMARY KEY,
    relation_name VARCHAR(63) NOT NULL,
    component_name VARCHAR(63) NOT NULL DEFAULT '',
    family_role VARCHAR(32) NOT NULL CHECK (family_role IN
        ('RUNTIME_PAYLOAD','SOURCE','CONTROL','AUDIT','PROVENANCE','CATALOG')),
    state_class VARCHAR(16) NOT NULL CHECK (state_class IN ('S2','UNKNOWN','UNRESOLVED','NOT_PAYLOAD')),
    fresh_disposition VARCHAR(48) NOT NULL CHECK (fresh_disposition IN
        ('STATE_CLASS_UNKNOWN_BLOCKING','NAMESPACE_MAPPING_BLOCKING','OWNERSHIP_DRIFT_BLOCKING',
         'RUNTIME_EVIDENCE_BLOCKING','SOURCE_ONLY','CONTROL_ONLY','AUDIT_ONLY','PROVENANCE_ONLY','CATALOG_ONLY')),
    retention_disposition VARCHAR(32) NOT NULL CHECK (retention_disposition IN
        ('OWNER_POLICY_RETAINED','UNRESOLVED_RETAINED','IMMUTABLE_RETAINED')),
    owner_consequence VARCHAR(1024) NOT NULL,
    UNIQUE (relation_name, component_name),
    CHECK (relation_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (component_name = '' OR component_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (family_id = relation_name || CASE WHEN component_name = '' THEN '' ELSE '.' || component_name END),
    CHECK (
        (fresh_disposition='STATE_CLASS_UNKNOWN_BLOCKING' AND family_role='RUNTIME_PAYLOAD' AND state_class='UNKNOWN')
        OR (fresh_disposition='NAMESPACE_MAPPING_BLOCKING' AND family_role='RUNTIME_PAYLOAD' AND state_class='S2')
        OR (fresh_disposition='OWNERSHIP_DRIFT_BLOCKING' AND family_role='RUNTIME_PAYLOAD' AND state_class='UNRESOLVED')
        OR (fresh_disposition='RUNTIME_EVIDENCE_BLOCKING' AND family_role IN ('CONTROL','AUDIT','PROVENANCE') AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='SOURCE_ONLY' AND family_role='SOURCE' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='CONTROL_ONLY' AND family_role='CONTROL' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='AUDIT_ONLY' AND family_role='AUDIT' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='PROVENANCE_ONLY' AND family_role='PROVENANCE' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='CATALOG_ONLY' AND family_role='CATALOG' AND state_class='NOT_PAYLOAD')
    ),
    CHECK (family_role <> 'CATALOG' OR retention_disposition='IMMUTABLE_RETAINED')
);

-- Known non-SQL limitations, not a Redis census or invented durable charge family.
CREATE TABLE automation_runtime_catalog_non_sql_gap (
    gap_id VARCHAR(48) PRIMARY KEY CHECK (gap_id IN
        ('CAPACITY_LEASE_ACCOUNTING','CENTRAL_RESET_ACL_REGISTRATION','DURABLE_CHARGE_MARKERS',
         'QUEUE_DISCOVERY_AND_ENCODING','QUEUE_POINTER_RETENTION')),
    disposition VARCHAR(32) NOT NULL CHECK (disposition IN ('UNRESOLVED_BLOCKING','UNSUPPORTED_BLOCKING')),
    owner_consequence VARCHAR(1024) NOT NULL
);

CREATE TABLE automation_runtime_family_catalog_epoch (
    catalog_epoch BIGINT PRIMARY KEY CHECK (catalog_epoch > 0),
    catalog_bytes BYTEA NOT NULL CHECK (octet_length(catalog_bytes) BETWEEN 1 AND 2097152),
    catalog_digest VARCHAR(71) NOT NULL CHECK (catalog_digest = 'sha256:' || encode(sha256(catalog_bytes), 'hex')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE automation_runtime_family_catalog_snapshot (
    read_request_id UUID PRIMARY KEY CHECK (read_request_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) BETWEEN 1 AND 1024),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_bytes), 'hex')),
    snapshot_id UUID NOT NULL UNIQUE CHECK (snapshot_id <> '00000000-0000-0000-0000-000000000000'),
    catalog_epoch BIGINT NOT NULL REFERENCES automation_runtime_family_catalog_epoch(catalog_epoch),
    catalog_digest VARCHAR(71) NOT NULL,
    evidence_bytes BYTEA NOT NULL CHECK (octet_length(evidence_bytes) BETWEEN 1 AND 2101248),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest = 'sha256:' || encode(sha256(evidence_bytes), 'hex')),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- PostgreSQL POSIX character classes are not simulated equivalently by DDLDatabase/H2.
-- Preserve the exact owner-consequence guards in PostgreSQL while exposing all table types.
-- [jooq ignore start]
ALTER TABLE automation_runtime_family_classification
    ADD CONSTRAINT ck_automation_runtime_family_consequence
    CHECK (length(btrim(owner_consequence)) > 0 AND owner_consequence !~ '[[:cntrl:]]');
ALTER TABLE automation_runtime_catalog_non_sql_gap
    ADD CONSTRAINT ck_automation_runtime_gap_consequence
    CHECK (length(btrim(owner_consequence)) > 0 AND owner_consequence !~ '[[:cntrl:]]');
-- [jooq ignore stop]

-- Exhaustive exact current table dispositions. UNKNOWN/UNRESOLVED are refusal facts, not S3.
-- The classifications describe source facts, not target-namespace absence or initialization proof.
INSERT INTO automation_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES
    ('npc_memory','npc_memory','','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Live latent behavior memory keyed by tenant/npc/key; stable namespace, typed actor scope, replacement class and required initialization are unresolved. Retain; never inherit or assume disposable.'),
    ('npc_formations','npc_formations','','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Formation leader references lack Entity-owned tenant/namespace actor proof; classification and fresh initialization remain unsupported. Do not clone formations.'),
    ('npc_formation_member','npc_formation_member','','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Membership follows a formation but lacks namespace and member NPC owner proof; preserve dependency closure and do not infer S3.'),
    ('faction_standing','faction_standing','','RUNTIME_PAYLOAD','UNRESOLVED','OWNERSHIP_DRIFT_BLOCKING','UNRESOLVED_RETAINED',
     'Live Automation player-reputation writer conflicts with canonical Social ownership. Social must own tenant/namespace/character/faction identity after retained-row disposition and consumer cutover; not Automation FRESH ownership.'),
    ('script_work_items','script_work_items','','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Admitted work and uncertain handoff share one row; namespace, sealed input/descriptor recovery and family classification are incomplete. No inherited pending work, receipts or synthetic initialization.'),
    ('script_schedule_instances','script_schedule_instances','','RUNTIME_PAYLOAD','S2','NAMESPACE_MAPPING_BLOCKING','UNRESOLVED_RETAINED',
     'Canonical durable schedules/due state are S2; live rows lack stable namespace and complete owner continuity/mapping proof. Never inherit due points, fire schedules or fabricate target initialization.'),
    ('plugin_runtime_states','plugin_runtime_states','','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Exact plugin activation/lifecycle tuples are narrower than private lifecycle/namespace/build proof. Required initialization and replacement classification are unresolved; do not activate or generate an epoch to populate evidence.'),
    ('script_event_ingress_audit','script_event_ingress_audit','','AUDIT','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Event-scope claim/admission audit mixes runtime and pre-instance readiness branches; runtime namespace and frozen handler manifest are absent. Retain original claims and never clone source audit authority.'),
    ('script_event_audit','script_event_audit','','AUDIT','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Handler, timer-candidate and readiness evidence is not a fresh manifest; namespace and complete recovery-bundle proof are missing. Existing holds and references remain authoritative for retention.'),
    ('script_handoff_events','script_handoff_events','','AUDIT','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Retain per-child handoff/remote lineage under existing work, replay and hold guards; complete source/target namespace and descriptor recovery evidence is unsupported. No inherited handoff receipts.'),
    ('script_dead_letter_replay_requests','script_dead_letter_replay_requests','','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Retained replay request identity/result is retry authority, not initialized gameplay. Minimum measured horizons, aggregate recovery claims and target namespace proof are unresolved; no clone or new cleanup.'),
    ('script_dead_letter_replay_results','script_dead_letter_replay_results','','PROVENANCE','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Generation-bound results hold their referenced work and retain rejection/failure lineage; horizon and complete recovery ledger are unresolved. Never copy or delete receipts by omission.'),
    ('plugin_runtime_request_history','plugin_runtime_request_history','','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Immutable exact lifecycle retry receipts survive no-op and later transitions; namespace binding and measured retry/recovery/restore horizons remain unresolved. Retain without cloning.'),
    ('plugin_runtime_events','plugin_runtime_events','','AUDIT','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Plugin lifecycle history is retained reconciliation/audit evidence, not pin or rollout authority. Holds, safe watermark and measured retention horizons are unresolved; no inherited chronology.'),
    ('script_patch_pin_projections','script_patch_pin_projections','','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Non-authoritative Game Session exact-pin observation; never mint a pin or infer UNPINNED from outage/absence. Stable private namespace/lifecycle evidence is missing.'),
    ('script_patch_instance_rollout_projections','script_patch_instance_rollout_projections','','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Automation convergence projection only; Game Session owns pin and rollout history. No private lifecycle/namespace manifest or inherited source timeline.'),
    ('script_patch_instance_rollout_events','script_patch_instance_rollout_events','','AUDIT','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Legacy observed rollout diagnostics remain retained non-authoritative history, not Game Session committed rollout events or a new target timeline.'),
    ('automation_admission_states','automation_admission_states','','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Local scoped Automation barrier/epoch is not actor authorization, global admission or private lifecycle proof; no source barrier is inherited.'),
    ('automation_admission_request_history','automation_admission_request_history','','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Exact barrier acknowledgements are retained retry evidence; horizon/tombstone and private namespace/lifecycle contracts remain missing. Do not copy source requests.'),
    ('scripts','scripts','','SOURCE','NOT_PAYLOAD','SOURCE_ONLY','OWNER_POLICY_RETAINED',
     'Versioned compiled definition source; production authoring/publication authority stays Game Design. Exact base/provenance and supported-version retention remain required; never clone mutable runtime state.'),
    ('script_event_bindings','script_event_bindings','','SOURCE','NOT_PAYLOAD','SOURCE_ONLY','OWNER_POLICY_RETAINED',
     'Versioned resolved event/binding source, not a frozen handler manifest or proof of a complete selected runtime owner set.'),
    ('script_schedule_definitions','script_schedule_definitions','','SOURCE','NOT_PAYLOAD','SOURCE_ONLY','OWNER_POLICY_RETAINED',
     'Authored patch-derived cadence definitions are outside S1/S2/S3; retain with supported source/version lifecycle, never equate definitions with initialized due state.'),
    ('factions','factions','','SOURCE','NOT_PAYLOAD','SOURCE_ONLY','OWNER_POLICY_RETAINED',
     'Local faction behavior definitions may remain Automation input; this does not authorize the separate player-standing writer or establish namespace-scoped Social standing.'),
    ('script_patch_readiness_projections','script_patch_readiness_projections','','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','UNRESOLVED_RETAINED',
     'Tenant readiness/generation and onLoad bookkeeping, not durable game initialization. Complete expected-handler/source-publication proof is missing; do not run onLoad to fabricate FRESH evidence.'),
    ('script_patch_base_bindings','script_patch_base_bindings','','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY','OWNER_POLICY_RETAINED',
     'Exact patch-to-base source provenance; no historical missing base is synthesized and this is not runtime pin/activation authority.'),
    ('automation_empty_source_numeric_key_reservation','automation_empty_source_numeric_key_reservation','','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
     'Retain immutable legacy/canonical numeric source custody and its writer guards; private numeric source keys are not playable namespace identity.'),
    ('automation_empty_selected_source_association','automation_empty_selected_source_association','','SOURCE','NOT_PAYLOAD','SOURCE_ONLY','OWNER_POLICY_RETAINED',
     'Selected-empty authored source association with original Account/World provenance; empty authored inventory is not empty runtime families or FRESH completeness.'),
    ('automation_empty_selected_source_receipt','automation_empty_selected_source_receipt','','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY','OWNER_POLICY_RETAINED',
     'Retain original immutable selected-source receipt and authorization/readback digests; never reuse it as a runtime manifest or release authority.'),
    ('saga_instance','saga_instance','','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
     'Installed adopter-local Saga workflow state under existing retention; not runtime payload, FRESH membership or a second lifecycle authority.'),
    ('saga_step','saga_step','','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
     'Installed adopter-local Saga step/recovery lineage remains retained with its parent; no cloning or cleanup permission.'),
    ('script_event_ingress_audit.script_id','script_event_ingress_audit','script_id','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','UNRESOLVED_RETAINED',
     'Pre-instance onLoad branch is tenant-readiness only and omits runtime identity; this mixed-table component cannot establish namespace absence.'),
    ('script_work_items.read_snapshot_token','script_work_items','read_snapshot_token','PROVENANCE','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'One legacy owner token is narrower than the sealed handler input manifest; no owner versions, causal floor, artifact and seed completeness is implied.'),
    ('script_work_items.admission_epoch','script_work_items','admission_epoch','CONTROL','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',
     'Local barrier evidence is not the missing durable full-Trigger-Identity admission/execution charge marker or private lifecycle generation.'),
    ('plugin_runtime_states.last_policy_checked_at','plugin_runtime_states','last_policy_checked_at','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','UNRESOLVED_RETAINED',
     'Signer/component-policy observation is runtime enforcement input only; accepted Game Design provenance, fresh policy and lifecycle/final-effect fences remain distinct.'),
    ('script_patch_readiness_projections.readiness_generation','script_patch_readiness_projections','readiness_generation','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','UNRESOLVED_RETAINED',
     'Tenant readiness generation is not private playtestStateGeneration, source publication ordering or durable handler-created initialization.'),
    ('automation_runtime_family_classification','automation_runtime_family_classification','','CATALOG','NOT_PAYLOAD','CATALOG_ONLY','IMMUTABLE_RETAINED',
     'Exact explicit installed family dispositions; changed classification invalidates the prior epoch, never enrollment or materialization authority.'),
    ('automation_runtime_catalog_non_sql_gap','automation_runtime_catalog_non_sql_gap','','CATALOG','NOT_PAYLOAD','CATALOG_ONLY','IMMUTABLE_RETAINED',
     'Retained known non-SQL limitation declarations only; no Redis census, key inventory or durable charge table is invented.'),
    ('automation_runtime_family_catalog_epoch','automation_runtime_family_catalog_epoch','','CATALOG','NOT_PAYLOAD','CATALOG_ONLY','IMMUTABLE_RETAINED',
     'Retained monotonic schema/classification seal; owner-local epoch only, not common registry revision or target state currentness.'),
    ('automation_runtime_family_catalog_snapshot','automation_runtime_family_catalog_snapshot','','CATALOG','NOT_PAYLOAD','CATALOG_ONLY','IMMUTABLE_RETAINED',
     'Retained exact request/snapshot/catalog bytes and digests; no TTL, FRESH completeness or actor permission.');

INSERT INTO automation_runtime_catalog_non_sql_gap VALUES
    ('CAPACITY_LEASE_ACCOUNTING','UNRESOLVED_BLOCKING',
     'Readiness/dry-run Redis reservations are not work-item-idempotent; retry can overwrite the prior lease and leak counts until expiry. Fenced/reclaimable accounting remains unproved.'),
    ('CENTRAL_RESET_ACL_REGISTRATION','UNRESOLVED_BLOCKING',
     'Live test quota/capacity and readiness capacity families are not all in the central reset/ACL inventory; no reset, resume or environment registration is authorized here.'),
    ('DURABLE_CHARGE_MARKERS','UNSUPPORTED_BLOCKING',
     'Quota/tenant-budget/capacity decisions are best-effort Redis counters/leases. Durable full-Trigger-Identity charge rows and exactly-once admission/execution-start markers do not exist.'),
    ('QUEUE_DISCOVERY_AND_ENCODING','UNRESOLVED_BLOCKING',
     'Live queue discovery uses database-wide KEYS and raw nonblank identity builders; cluster-safe owner index, canonical tuple encoding and collision/brace proof are missing.'),
    ('QUEUE_POINTER_RETENTION','UNSUPPORTED_BLOCKING',
     'Live RPUSH/LPOP queue pointer lists have no pointer TTL or maximum-length bound; owner-reconciled durable re-drive/reset gate is unavailable. SQL census does not enumerate these Redis keys.');

-- Flyway 13.12 built-in placeholder selects the ACTUAL configured history table.
-- Narrow PostgreSQL-only block: all ordinary table DDL above stays visible to jOOQ.
-- [jooq ignore start]
INSERT INTO automation_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('${flyway:table}','${flyway:table}','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Actual configured owner Flyway history is retained installation evidence, not gameplay state or enrollment.');

CREATE FUNCTION automation_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1004' AND script='V1004__automation_owner_runtime_family_catalog.sql' AND success);
$$;

CREATE FUNCTION automation_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
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
        SELECT 'VIEW',c.relname::TEXT,pg_get_viewdef(c.oid,true) FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1004'
        UNION ALL
        -- Own row is post-transaction. Exact installed definitions plus this stable marker bind
        -- installation; the separate installed() check requires the later successful exact script.
        -- This is deliberately NOT a fabricated checksum for the not-yet-written Flyway row.
        SELECT 'MIGRATION','catalog-installation:1004','V1004__automation_owner_runtime_family_catalog.sql'
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

CREATE FUNCTION automation_runtime_catalog_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Automation runtime catalog evidence is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER automation_runtime_classification_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON automation_runtime_family_classification FOR EACH STATEMENT EXECUTE FUNCTION automation_runtime_catalog_immutable();
CREATE TRIGGER automation_runtime_non_sql_gap_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON automation_runtime_catalog_non_sql_gap FOR EACH STATEMENT EXECUTE FUNCTION automation_runtime_catalog_immutable();
CREATE TRIGGER automation_runtime_epoch_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON automation_runtime_family_catalog_epoch FOR EACH STATEMENT EXECUTE FUNCTION automation_runtime_catalog_immutable();
CREATE TRIGGER automation_runtime_snapshot_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON automation_runtime_family_catalog_snapshot FOR EACH STATEMENT EXECUTE FUNCTION automation_runtime_catalog_immutable();

CREATE FUNCTION automation_runtime_catalog_epoch_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
BEGIN
    IF NEW.catalog_epoch<>coalesce((SELECT max(catalog_epoch) FROM automation_runtime_family_catalog_epoch),0)+1
        OR NEW.catalog_bytes<>automation_runtime_family_catalog_bytes(NEW.catalog_epoch) THEN
        RAISE EXCEPTION 'Automation epoch must bind the exact classified installed schema' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER automation_runtime_epoch_exact BEFORE INSERT ON automation_runtime_family_catalog_epoch
FOR EACH ROW EXECUTE FUNCTION automation_runtime_catalog_epoch_guard();

CREATE FUNCTION automation_runtime_catalog_snapshot_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_epoch automation_runtime_family_catalog_epoch%ROWTYPE; v_request BYTEA; v_evidence BYTEA;
BEGIN
    SELECT * INTO STRICT v_epoch FROM automation_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1;
    IF NOT automation_runtime_family_catalog_installed()
        OR NEW.catalog_epoch<>v_epoch.catalog_epoch OR NEW.catalog_digest<>v_epoch.catalog_digest
        OR automation_runtime_family_catalog_bytes(v_epoch.catalog_epoch)<>v_epoch.catalog_bytes THEN
        RAISE EXCEPTION 'Automation runtime catalog changed; new owner epoch required' USING ERRCODE='23514';
    END IF;
    -- Exact ACR1/ACE1 binary frames match the closed Java codec, including the original request.
    v_request := decode('41435231','hex') || uuid_send(NEW.read_request_id)
        || int4send(octet_length(convert_to(NEW.target_namespace,'UTF8'))) || convert_to(NEW.target_namespace,'UTF8');
    v_evidence := decode('41434531','hex') || int4send(octet_length(v_request)) || v_request
        || uuid_send(NEW.snapshot_id) || int4send(octet_length(convert_to(v_epoch.catalog_digest,'UTF8')))
        || convert_to(v_epoch.catalog_digest,'UTF8') || int4send(octet_length(v_epoch.catalog_bytes)) || v_epoch.catalog_bytes;
    IF NEW.request_bytes<>v_request OR NEW.evidence_bytes<>v_evidence THEN
        RAISE EXCEPTION 'Automation snapshot must bind complete exact request and evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER automation_runtime_snapshot_current BEFORE INSERT ON automation_runtime_family_catalog_snapshot
FOR EACH ROW EXECUTE FUNCTION automation_runtime_catalog_snapshot_guard();
-- [jooq ignore stop]

-- PostgreSQL-only bootstrap, not an H2/embedded simulation of pg_catalog completeness.
-- [jooq ignore start]
INSERT INTO automation_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 1,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT automation_runtime_family_catalog_bytes(1) AS bytes) seal;
-- [jooq ignore stop]
